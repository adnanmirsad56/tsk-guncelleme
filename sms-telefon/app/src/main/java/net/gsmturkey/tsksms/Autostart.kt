package net.gsmturkey.tsksms

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 1.2 — Üretici "otomatik başlatma / arka plan" ayar ekranı. Xiaomi (MIUI / HyperOS), Oppo / Realme (ColorOS),
 * Vivo, Huawei / Honor, Samsung kendi pil yöneticileriyle uygulamayı kapatabiliyor; bu ekranlarda TSK SMS'e izin
 * verilince telefon açıkken servis kapanmaz. Tanınmayan telefonda uygulama bilgileri ekranı açılır.
 */
object Autostart {
    private val targets = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
    )

    fun brand(): String = "${Build.MANUFACTURER}".lowercase()

    /** Bu telefon kendi pil yöneticisi olan bir marka mı (kullanıcıya ayrıca hatırlatılır)? */
    fun needed(): Boolean = listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo",
        "huawei", "honor", "samsung").any { brand().contains(it) }

    fun open(a: Activity) {
        for ((pkg, cls) in targets) {
            try {
                // Android 11+ başka uygulamaları göremeyebilir (resolveActivity null) → doğrudan denenir
                a.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}                    // bu telefonda yok / izin yok: sıradaki
        }
        try {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${a.packageName}")))
        } catch (_: Exception) {}
    }
}
