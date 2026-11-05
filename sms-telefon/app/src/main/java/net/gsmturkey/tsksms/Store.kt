package net.gsmturkey.tsksms

import android.content.Context
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ayarlar + gönderim günlüğü (telefonda, SharedPreferences). */
object Store {
    private const val PREFS = "tsk_sms"
    const val USER = "tsk"
    const val DEFAULT_PORT = 8080

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun password(ctx: Context): String {
        val cur = p(ctx).getString("pass", null)
        if (!cur.isNullOrEmpty()) return cur
        return newPassword(ctx)
    }

    fun newPassword(ctx: Context): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val pw = (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        p(ctx).edit().putString("pass", pw).apply()
        return pw
    }

    /** 1.7: TSK'nın eşleştirme bağlantısındaki SMS şifresi (p=) telefona yazılır: iki tarafta aynı olur. */
    fun setPassword(ctx: Context, pw: String) {
        p(ctx).edit().putString("pass", pw).apply()
    }

    fun port(ctx: Context): Int = p(ctx).getInt("port", DEFAULT_PORT)

    /** Bu kurulumun kalıcı kimliği: TSK telefonu ağda bulunca adresi değişse de aynı telefonu tanır. */
    fun deviceId(ctx: Context): String {
        val cur = p(ctx).getString("device_id", null)
        if (!cur.isNullOrEmpty()) return cur
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        p(ctx).edit().putString("device_id", id).apply()
        return id
    }

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("enabled", on).apply()
    }

    fun log(ctx: Context, line: String) {
        val stamp = SimpleDateFormat("dd.MM HH:mm", Locale("tr")).format(Date())
        val old = p(ctx).getString("log", "") ?: ""
        val lines = ("$stamp  $line\n$old").lines().take(40).joinToString("\n")
        p(ctx).edit().putString("log", lines).apply()
    }

    fun logText(ctx: Context): String = p(ctx).getString("log", "") ?: ""

    /** 1.2: son gönderilenler (tam numarayla, yalnız bu telefonda) — listeden dokununca Mesajlar'da açılır. */
    fun addRecent(ctx: Context, to: String, text: String) {
        val stamp = SimpleDateFormat("dd.MM HH:mm", Locale("tr")).format(Date())
        val clean = text.replace("\n", " ").replace("\t", " ").take(60)
        val old = p(ctx).getString("recent", "") ?: ""
        val lines = ("$stamp\t$to\t$clean\n$old").lines().filter { it.isNotBlank() }.take(30)
            .joinToString("\n")
        p(ctx).edit().putString("recent", lines).apply()
    }

    /** [(zaman, numara, metin)] */
    fun recent(ctx: Context): List<Triple<String, String, String>> =
        (p(ctx).getString("recent", "") ?: "").lines().mapNotNull {
            val parts = it.split("\t")
            if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else null
        }

    /** 1.4: internet üzerinden bağlantı (TSK'nın uzaktan erişim bağlantısından): köprü, oda, uçtan uca anahtar. */
    data class Remote(val base: String, val room: String, val key: String, val shop: String)

    fun remote(ctx: Context): Remote? {
        val parts = (p(ctx).getString("remote", "") ?: "").split("\t")
        if (parts.size < 3 || parts[0].isEmpty()) return null
        return Remote(parts[0], parts[1], parts[2], parts.getOrElse(3) { "" })
    }

    fun setRemote(ctx: Context, r: Remote?) {
        p(ctx).edit().putString("remote", if (r == null) "" else
            listOf(r.base, r.room, r.key, r.shop.replace("\t", " ")).joinToString("\t")).apply()
    }

    /** TSK'dan gelen bağlantıyı çözer: https://<köprü>/#r=<oda>&k=<anahtar>&n=<dükkân> (WhatsApp metni içinde de). */
    fun parseLink(text: String): Remote? {
        val start = text.indexOf("http")
        if (start < 0) return null
        val link = text.substring(start).split(Regex("\\s")).first()
        val hash = link.indexOf('#')
        if (hash < 0) return null
        val base = link.substring(0, hash).trimEnd('/').removeSuffix("/sms").trimEnd('/')    // 1.7: …/sms#… (QR)
        val params = link.substring(hash + 1).split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to try {
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (_: Exception) { it.substring(i + 1) }
        }.toMap()
        val room = params["r"] ?: return null
        val key = params["k"] ?: return null
        if (!Regex("^[0-9a-f]{24}$").matches(room) || key.length < 20) return null
        return Remote(base, room, key, params["n"] ?: "")
    }

    /** 1.7: bağlantının '#' sonrasındaki bir alan (ör. p = SMS şifresi). */
    fun linkParam(text: String, name: String): String? {
        val start = text.indexOf("http")
        if (start < 0) return null
        val link = text.substring(start).split(Regex("\\s")).first()
        val hash = link.indexOf('#')
        if (hash < 0) return null
        for (part in link.substring(hash + 1).split("&")) {
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == name) {
                return try { java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8") } catch (_: Exception) { part.substring(i + 1) }
            }
        }
        return null
    }

    fun count(ctx: Context): Int = p(ctx).getInt("sent", 0)

    fun addCount(ctx: Context) {
        p(ctx).edit().putInt("sent", count(ctx) + 1).apply()
    }
}
