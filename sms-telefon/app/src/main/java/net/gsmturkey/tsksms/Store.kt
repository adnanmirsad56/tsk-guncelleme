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

    fun count(ctx: Context): Int = p(ctx).getInt("sent", 0)

    fun addCount(ctx: Context) {
        p(ctx).edit().putInt("sent", count(ctx) + 1).apply()
    }
}
