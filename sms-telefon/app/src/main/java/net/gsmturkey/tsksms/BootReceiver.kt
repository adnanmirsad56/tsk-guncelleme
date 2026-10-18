package net.gsmturkey.tsksms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon açılınca (ya da uygulama güncellenince) sunucu açıktıysa kendiliğinden yeniden başlar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Store.enabled(ctx)) {
            try { GatewayService.start(ctx) } catch (_: Exception) {}
        }
    }
}
