package net.gsmturkey.tsksms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Arka planda sürekli çalışan servis: yerel SMS sunucusunu açık tutar (bildirimde "TSK SMS hazır"). */
class GatewayService : Service() {
    private var server: SmsServer? = null
    private var discovery: Discovery? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (server == null) {
            try {
                server = SmsServer(this, Store.port(this)).also { it.start() }
                Store.log(this, "Sunucu açıldı (port ${Store.port(this)})")
            } catch (e: Exception) {
                Store.log(this, "✗ Sunucu açılamadı: ${e.message}")
            }
            try {
                discovery = Discovery(this, Store.port(this)).also { it.start() }
            } catch (e: Exception) {
                Store.log(this, "✗ Ağda bulunma açılamadı: ${e.message}")
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsksms:server").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tsksms:wifi").apply { acquire() }
            multicast = wm.createMulticastLock("tsksms:find").apply { setReferenceCounted(false); acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
        try { multicast?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        Store.log(this, "Sunucu kapandı")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("tsk", "TSK SMS", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "tsk") else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("TSK SMS hazır")
            .setContentText("Dükkân programı bu telefondan SMS gönderebilir")
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }
}
