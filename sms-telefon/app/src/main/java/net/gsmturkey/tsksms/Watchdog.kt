package net.gsmturkey.tsksms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 1.2 — BEKÇİ: telefonun pil yönetimi (Xiaomi / Oppo / Vivo …) servisi kapatsa da birkaç dakikada bir uyanıp servisi
 * yeniden başlatır; servis açıksa sunucunun gerçekten yanıt verip vermediğini yoklar (vermiyorsa yeniden açar).
 * Ekran kapalıyken / Doze'da da çalışır (setAndAllowWhileIdle).
 */
object Watchdog {
    private const val EVERY_MS = 5 * 60 * 1000L
    const val ACTION = "net.gsmturkey.tsksms.WATCHDOG"

    private fun intent(ctx: Context): PendingIntent {
        val i = Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(ctx, 3, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(ctx: Context, delayMs: Long = EVERY_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(ctx))
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(ctx))
            }
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(intent(ctx)) } catch (_: Exception) {}
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Store.enabled(ctx)) return
        try {
            GatewayService.start(ctx, check = true)       // çalışıyorsa yalnız yoklama, kapandıysa yeniden başlar
        } catch (e: Exception) {
            Store.log(ctx, "✗ Bekçi servisi başlatamadı (pil kısıtlaması?): ${e.javaClass.simpleName}")
        }
        Watchdog.schedule(ctx)
    }
}
