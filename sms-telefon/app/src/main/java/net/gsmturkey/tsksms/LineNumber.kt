package net.gsmturkey.tsksms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 1.10: Bu hattın (SMS'in gittiği SIM'in) numarası — TSK'ya 'dükkân numarası' olarak bildirilir.
 * Sıra: 1) SIM'den oku (READ_PHONE_NUMBERS)  2) okunamazsa *101# (USSD; Türkiye'de numarayı söyler, CALL_PHONE izni)
 * 3) o da olmazsa kullanıcı elle girer. Bulunan numara bir kez kaydedilir (Store.line).
 */
object LineNumber {
    @Volatile var status: String = ""

    private fun granted(c: Context, p: String) =
        Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Metindeki Türk cep numarası → 10 hane (5xx…); yoksa null. */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = Regex("(?:\\+?90|0)?[\\s\\-.]*(5\\d{2})[\\s\\-.]*(\\d{3})[\\s\\-.]*(\\d{2})[\\s\\-.]*(\\d{2})(?!\\d)").find(text)
            ?: return null
        return m.groupValues.drop(1).joinToString("")
    }

    fun pretty(n: String): String = if (n.length == 10) "0${n.substring(0, 3)} ${n.substring(3, 6)} ${n.substring(6, 8)} ${n.substring(8)}" else n

    private fun smsSubId(): Int = try {
        if (Build.VERSION.SDK_INT >= 22) SmsManager.getDefaultSmsSubscriptionId() else -1
    } catch (_: Exception) { -1 }

    private fun tm(c: Context): TelephonyManager {
        val base = c.getSystemService(TelephonyManager::class.java)
        val sub = smsSubId()
        return if (Build.VERSION.SDK_INT >= 24 && sub >= 0) base.createForSubscriptionId(sub) else base
    }

    /** 1) SIM kartın kayıtlı numarası (operatör yazmadıysa boş gelir). */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun fromSim(c: Context): String? {
        val ok = granted(c, Manifest.permission.READ_PHONE_STATE) ||
                (Build.VERSION.SDK_INT >= 26 && granted(c, Manifest.permission.READ_PHONE_NUMBERS))
        if (!ok) return null
        try {
            if (Build.VERSION.SDK_INT >= 33 && granted(c, Manifest.permission.READ_PHONE_NUMBERS)) {
                val sm = c.getSystemService(SubscriptionManager::class.java)
                val sub = smsSubId()
                parse(sm.getPhoneNumber(if (sub >= 0) sub else SubscriptionManager.getDefaultSubscriptionId()))?.let { return it }
            }
        } catch (_: Exception) {}
        return try { parse(tm(c).line1Number) } catch (_: Exception) { null }
    }

    /** 2) *101# — yanıt metnindeki numara. Sonuç ana iş parçacığında done(numara ya da null). */
    @SuppressLint("MissingPermission")
    fun fromUssd(c: Context, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 26) { main.post { done(null) }; return }
        if (!granted(c, Manifest.permission.CALL_PHONE)) { main.post { done(null) }; return }
        var finished = false
        val finish = { n: String? -> if (!finished) { finished = true; done(n) } }
        main.postDelayed({ finish(null) }, 25_000)            // operatör yanıt vermezse
        try {
            tm(c).sendUssdRequest("*101#", object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                    finish(parse(response.toString()))
                }
                override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                    finish(null)
                }
            }, main)
        } catch (_: Exception) {
            main.post { finish(null) }
        }
    }

    /** Numara kayıtlı değilse sırayla dener (SIM → *101#); bulunca kaydeder ve TSK'ya hemen bildirir. */
    fun detect(c: Context, allowUssd: Boolean, done: (String?) -> Unit) {
        val app = c.applicationContext
        status = "Numara SIM'den okunuyor…"
        fromSim(app)?.let { save(app, it, "SIM"); done(it); return }
        if (!allowUssd) { status = "SIM'de numara yazılı değil"; done(null); return }
        status = "*101# ile numara soruluyor…"
        fromUssd(app) { n ->
            if (n != null) save(app, n, "*101#") else status = "Numara bulunamadı — elle girin"
            done(n)
        }
    }

    fun save(c: Context, n: String, how: String) {
        Store.setLine(c, n)
        status = "✓ ${pretty(n)} ($how)"
        Store.log(c, "Dükkân numarası: ${pretty(n)} ($how)")
        RemoteLink.helloNow()
    }

    /** Yerel ağ adresi (TSK aynı ağdaysa bu adresten gönderir). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    } catch (_: Exception) { null }
}
