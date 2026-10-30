package net.gsmturkey.tsksms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 1.5: kendi kendini güncelleme. GitHub'daki 'sms-telefon' ön sürümünde sms-version.json (sürüm, derleme no, APK
 * adresi) okunur; daha yeni derleme varsa bildirim / düğme. 'Güncelle' → APK indirilir, Android'in kurulum onayı açılır
 * (Play Store dışı uygulama sessiz kurulamaz: kullanıcı bir kez 'Yükle'ye basar). Kurulunca servis kendiliğinden
 * yeniden başlar (BootReceiver: MY_PACKAGE_REPLACED).
 */
object Updater {
    private const val INFO_URL =
        "https://github.com/adnanmirsad56/tsk-guncelleme/releases/download/sms-telefon/sms-version.json"

    data class Info(val version: String, val code: Long, val url: String)

    @Volatile var latest: Info? = null
        private set
    @Volatile var status: String = ""

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    }

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    fun currentName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    /** Yeni sürüm var mı (ağ — ana iş parçacığında çağırmayın). Döner: yeni sürüm ya da null (güncel / ulaşılamadı). */
    fun check(ctx: Context): Info? {
        val found: Info? = try {
            http.newCall(Request.Builder().url(INFO_URL).header("Cache-Control", "no-cache").build()).execute()
                .use { r ->
                    val body = if (r.isSuccessful) r.body?.string() else null
                    if (body == null) null else {
                        val j = JSONObject(body)
                        Info(j.optString("version"), j.optLong("code"), j.optString("url"))
                    }
                }
        } catch (_: Exception) {
            null
        }
        latest = if (found != null && found.code > currentCode(ctx) && found.url.startsWith("https://")) found else null
        return latest
    }

    /** APK'yı indirip Android'in kurulum onayını açar (ana iş parçacığında çağırmayın). */
    fun downloadAndInstall(ctx: Context, info: Info) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                status = "Önce bu uygulamaya 'bilinmeyen uygulamaları yükleme' izni verin, sonra tekrar basın"
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            status = "İndiriliyor… (${info.version})"
            val installer = ctx.packageManager.packageInstaller
            val sid = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(sid).use { session ->
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("indirme hatası (${r.code})")
                    val body = r.body ?: throw IllegalStateException("boş yanıt")
                    session.openWrite("TSK-SMS.apk", 0, body.contentLength()).use { out ->
                        body.byteStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val flags = if (Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
                val pi = PendingIntent.getBroadcast(ctx, sid, Intent(ctx, InstallReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
            status = "Kurulum onayı bekleniyor — açılan ekranda 'Yükle'ye basın"
        } catch (e: Exception) {
            status = "✗ Güncellenemedi: ${e.message?.take(60)}"
            Store.log(ctx, status)
        }
    }
}

/** Kurulum sonucu: onay gerekiyorsa Android'in onay ekranı açılır; başarı / hata kayda yazılır. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Store.log(ctx, "✓ Uygulama güncellendi")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Updater.status = "✗ Güncelleme kurulmadı $msg".trim()
                Store.log(ctx, Updater.status)
            }
        }
    }
}
