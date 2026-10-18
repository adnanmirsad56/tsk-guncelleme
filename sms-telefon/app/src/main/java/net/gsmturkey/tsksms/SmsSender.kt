package net.gsmturkey.tsksms

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** SMS'i telefonun kendi hattından gönderir (çift SIM'de 1 / 2 seçilebilir). */
object SmsSender {

    @SuppressLint("MissingPermission")
    private fun manager(ctx: Context, sim: Int?): SmsManager {
        val default: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (sim == null || sim < 1) return default
        return try {
            val subs = ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            val info = subs?.firstOrNull { it.simSlotIndex == sim - 1 } ?: return default
            if (Build.VERSION.SDK_INT >= 31) {
                default.createForSubscriptionId(info.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(info.subscriptionId)
            }
        } catch (e: Exception) {
            default
        }
    }

    fun send(ctx: Context, to: String, text: String, sim: Int?) {
        val sm = manager(ctx, sim)
        val parts = sm.divideMessage(text)
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            sm.sendTextMessage(to, null, text, null, null)
        }
    }
}
