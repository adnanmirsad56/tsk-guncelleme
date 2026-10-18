package net.gsmturkey.tsksms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TSK SMS — dükkân telefonunu SMS kapısı yapar. Ekranda yazan adres, kullanıcı ve şifre TSK'da
 * Ayarlar → SMS'e yazılır; TSK müşteriye SMS'i bu telefonun KENDİ hattından gönderir.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var creds: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        root.addView(text(26f, true).apply { text = "TSK SMS"; gravity = Gravity.START })
        root.addView(text(14f, color = Color.rgb(148, 163, 184)).apply {
            text = "Dükkân programı (TSK) müşteriye SMS'i bu telefonun kendi hattından gönderir. " +
                    "Aşağıdaki bilgileri TSK'da Ayarlar → SMS bölümüne yazın."
        })
        status = text(18f, true)
        address = text(22f, true, Color.rgb(96, 165, 250))
        creds = text(18f, true)
        root.addView(status)
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply { text = "Telefon adresi (TSK'ya yazılacak)" })
        root.addView(address)
        root.addView(creds)
        toggle = button("Başlat") { toggleServer() }
        root.addView(toggle)
        root.addView(button("İzinleri ver (SMS, telefon, bildirim)") { askPermissions() })
        root.addView(button("Arka planda kapanmasın (pil ayarı)") { askBattery() })
        root.addView(button("Yeni şifre üret") {
            Store.newPassword(this)
            if (Store.enabled(this)) { GatewayService.stop(this); GatewayService.start(this) }
            refresh()
        })
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply {
            text = "Telefon dükkândaki Wi-Fi'ye bağlı ve şarjda dursun. Bilgiler yalnız yerel ağda kullanılır."
        })
        root.addView(text(15f, true).apply { text = "Son gönderimler" })
        log = text(13f, color = Color.rgb(203, 213, 225))
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.rgb(15, 23, 42)) })
        if (!hasSmsPermission()) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun hasSmsPermission() = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val want = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(want.toTypedArray(), 7)
    }

    private fun askBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toggleServer() {
        if (Store.enabled(this)) {
            Store.setEnabled(this, false)
            GatewayService.stop(this)
        } else {
            if (!hasSmsPermission()) { askPermissions(); return }
            Store.setEnabled(this, true)
            GatewayService.start(this)
        }
        refresh()
    }

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: "Wi-Fi'ye bağlı değil"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun refresh() {
        val on = Store.enabled(this)
        status.text = if (on) "● Açık — SMS göndermeye hazır" else "○ Kapalı"
        status.setTextColor(if (on) Color.rgb(34, 197, 94) else Color.rgb(239, 68, 68))
        toggle.text = if (on) "Durdur" else "Başlat"
        address.text = "${localIp()}   port ${Store.port(this)}"
        creds.text = "Kullanıcı: ${Store.USER}     Şifre: ${Store.password(this)}"
        val perm = if (hasSmsPermission()) "" else "\n⚠ SMS izni verilmedi — 'İzinleri ver'e basın."
        log.text = "Gönderilen: ${Store.count(this)}$perm\n\n" + Store.logText(this)
    }
}
