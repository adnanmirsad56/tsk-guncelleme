package net.gsmturkey.tsksms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Arka planda sürekli çalışan servis: yerel SMS sunucusunu açık tutar (bildirimde "TSK SMS hazır").
 * 1.2: kendi kendini yoklar (sunucu yanıt vermezse yeniden açar), Wi-Fi gelince / adres değişince sunucuyu yeniler,
 * hazır olunca ağa "hazırım" duyurur (TSK kuyruktaki SMS'leri hemen gönderir), bekçi alarmıyla kapansa da geri gelir.
 */
class GatewayService : Service() {
    private var server: SmsServer? = null
    private var discovery: Discovery? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var openedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val selfCheck = object : Runnable {
        override fun run() {
            checkServer()
            main.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (server == null) {
            openServer()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsksms:server").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tsksms:wifi").apply { acquire() }
            multicast = wm.createMulticastLock("tsksms:find").apply { setReferenceCounted(false); acquire() }
            watchNetwork()
            main.postDelayed(selfCheck, CHECK_MS)
        } else if (intent?.getBooleanExtra("check", false) == true) {
            checkServer()                                   // bekçi uyandırdı: sunucu gerçekten yanıt veriyor mu
        }
        Watchdog.schedule(this)
        return START_STICKY
    }

    private fun openServer() {
        openedAt = android.os.SystemClock.elapsedRealtime()
        try {
            server = SmsServer(this, Store.port(this)).also { it.start() }
            Store.log(this, "Sunucu açıldı (port ${Store.port(this)})")
        } catch (e: Exception) {
            server = null
            Store.log(this, "✗ Sunucu açılamadı: ${e.message}")
        }
        try {
            discovery = Discovery(this, Store.port(this)).also { it.start() }
        } catch (e: Exception) {
            Store.log(this, "✗ Ağda bulunma açılamadı: ${e.message}")
        }
        announce()
    }

    private fun closeServer() {
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
    }

    /** Yerel bağlantıyla sunucuyu dene; yanıt yoksa kapatıp yeniden aç. */
    private fun checkServer() {
        Thread({
            val ok = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", Store.port(this)), 1500) }
                true
            } catch (_: Exception) {
                false
            }
            if (!ok) main.post {
                Store.log(this, "Sunucu yanıt vermiyordu — yeniden açıldı")
                closeServer()
                openServer()
            }
        }, "tsk-sms-check").start()
    }

    /** Wi-Fi gelince / değişince (modem yeniden başladı, adres değişti) sunucu ve ağda bulunma yenilenir. */
    private fun watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // kayıt anında gelen ilk bildirim (sunucu zaten yeni açıldı) atlanır
                if (android.os.SystemClock.elapsedRealtime() - openedAt < 5000) return
                main.postDelayed({
                    closeServer()
                    openServer()
                    Store.log(this@GatewayService, "Ağ bağlandı — sunucu yenilendi")
                }, 2500)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    /** "Hazırım" duyurusu (UDP 47816): TSK duyunca kuyruktaki SMS'leri beklemeden gönderir. */
    private fun announce() {
        Thread({
            try {
                val msg = JSONObject().put("app", "TSK SMS").put("ready", 1).put("id", Store.deviceId(this))
                    .put("port", Store.port(this)).toString().toByteArray(Charsets.UTF_8)
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(3) {
                        s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), READY_PORT))
                        Thread.sleep(400)
                    }
                }
            } catch (_: Exception) {}
        }, "tsk-sms-ready").start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Son uygulamalardan kaydırılıp kapatılınca: bekçi birkaç saniye sonra servisi geri getirir
        if (Store.enabled(this)) Watchdog.schedule(this, 3000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 24) {
            try { netCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
            catch (_: Exception) {}
        }
        closeServer()
        try { multicast?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        if (Store.enabled(this)) Watchdog.schedule(this, 5000) else Watchdog.cancel(this)
        Store.log(this, "Sunucu kapandı")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            // 1.2: uygulama simgesinde sürekli '1' rozeti görünmesin (kanal ayarı sonradan değişmediği için yeni kanal)
            nm.deleteNotificationChannel("tsk")
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TSK SMS", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("TSK SMS hazır")
            .setContentText("Dükkân programı bu telefondan SMS gönderebilir")
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHECK_MS = 60_000L
        private const val CHANNEL = "tsk_service"
        const val READY_PORT = 47816

        fun start(ctx: Context, check: Boolean = false) {
            val i = Intent(ctx, GatewayService::class.java).putExtra("check", check)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }
}
