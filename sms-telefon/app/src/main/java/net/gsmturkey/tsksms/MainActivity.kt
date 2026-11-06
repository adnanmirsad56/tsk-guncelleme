package net.gsmturkey.tsksms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var battery: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var remoteStatus: TextView
    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private lateinit var remoteRemove: Button
    private lateinit var lineText: TextView
    private var recentSig = ""
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
                    "TSK'daki 'Telefonu eşleştir' QR'ını okutunca adres, kullanıcı, şifre ve bu hattın numarası " +
                    "TSK'ya kendiliğinden gider (1.10) — elle yazmak gerekmez."
        })
        status = text(18f, true)
        address = text(22f, true, Color.rgb(96, 165, 250))
        creds = text(18f, true)
        root.addView(status)
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply { text = "Telefon adresi (TSK'ya yazılacak)" })
        root.addView(address)
        root.addView(creds)
        // 1.10: bu hattın numarası (TSK'da dükkân numarası boşsa oraya yazılır)
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply { text = "Bu hattın numarası (TSK'ya dükkân numarası olarak gider)" })
        lineText = text(18f, true)
        root.addView(lineText)
        root.addView(button("Numarayı bul (SIM'den, olmazsa *101#)") { findLine(true) })
        root.addView(button("Numarayı elle gir") { typeLine() })
        toggle = button("Başlat") { toggleServer() }
        root.addView(toggle)
        root.addView(button("İzinleri ver (SMS, telefon, bildirim)") { askPermissions() })
        root.addView(button("Arka planda kapanmasın (pil ayarı)") { askBattery() })
        // 1.2: Xiaomi / Oppo / Vivo … kendi pil yöneticisi: otomatik başlatma + 'kısıtlama yok'
        root.addView(button("Otomatik başlatma / arka plan izni (Xiaomi, Oppo, Vivo…)") { Autostart.open(this) })
        battery = text(13f, color = Color.rgb(148, 163, 184))
        root.addView(battery)
        root.addView(button("Yeni şifre üret") {
            Store.newPassword(this)
            if (Store.enabled(this)) { GatewayService.stop(this); GatewayService.start(this) }
            refresh()
        })
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply {
            text = "TSK'da Ayarlar → SMS → 'Telefonu bul' bu telefonu ağda kendiliğinden bulur (adres değişse de). " +
                    "Telefon şarjda dursun."
        })
        // 1.4: internet üzerinden (telefon dükkân Wi-Fi'sinde olmasa da, mobil veride de)
        root.addView(text(17f, true).apply { text = "İnternet üzerinden (dükkân dışından)" })
        root.addView(text(13f, color = Color.rgb(148, 163, 184)).apply {
            text = "En kolayı: aşağıdaki '📷 QR okut'a basıp TSK'da Ayarlar → SMS → 'Telefonu eşleştir' QR'ını okutun " +
                    "(ya da telefonun kamerasıyla okutup 'TSK SMS ile aç' deyin) — eşleşme, şifre ve başlatma " +
                    "kendiliğinden olur. Ya da oradaki bağlantıyı " +
                    "(WhatsApp'la gelen) kopyalayıp aşağıdaki düğmeye basın. Telefon dükkân Wi-Fi'sinde olmasa da, mobil " +
                    "veride de SMS gider. Mesajlar uçtan uca şifrelidir."
        })
        remoteStatus = text(15f, true)
        root.addView(remoteStatus)
        root.addView(button("📷 QR okut (TSK → Ayarlar → SMS → Telefonu eşleştir)") { scanQr() })
        root.addView(button("Bağlantıyı yapıştır (TSK'dan gelen)") { pasteRemote() })
        root.addView(button("Bağlantıyı elle gir") { typeRemote() })
        remoteRemove = button("İnternet bağlantısını kaldır") {
            Store.setRemote(this, null)
            RemoteLink.stop()
            refresh()
        }
        root.addView(remoteRemove)
        // 1.5: kendi kendini güncelleme
        root.addView(text(17f, true).apply { text = "Güncelleme" })
        updateText = text(14f, color = Color.rgb(203, 213, 225))
        root.addView(updateText)
        updateBtn = button("Güncellemeleri denetle") { updateClicked() }
        root.addView(updateBtn)
        root.addView(text(15f, true).apply { text = "Son gönderimler (dokununca Mesajlar'da açılır)" })
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)
        root.addView(text(15f, true).apply { text = "Kayıt" })
        log = text(13f, color = Color.rgb(203, 213, 225))
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.rgb(15, 23, 42)) })
        if (!hasSmsPermission()) askPermissions() else autoLine()
        handleLink(intent)
    }

    // 1.7: QR (kamera) ya da bağlantıya dokununca: https://app.tskservis.com/sms#r=…&k=…&n=…&p=…
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val data = i.dataString ?: return
        applyLink(data)
        i.data = null                                       // döndürünce ikinci kez uygulanmasın
    }

    /** 1.7: TSK'daki eşleştirme QR'ını uygulama içinden okutur (Google kod tarayıcısı; kamera izni istemez). */
    private fun scanQr() {
        val fail = { why: String ->
            Toast.makeText(this, "QR okutulamadı ($why). Telefonun kendi kamerasıyla okutup 'TSK SMS ile aç'ı da " +
                    "seçebilirsiniz.", Toast.LENGTH_LONG).show()
        }
        try {
            val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b -> applyLink(b.rawValue ?: "") }
                .addOnFailureListener { e -> fail(e.message ?: "hata") }
        } catch (e: Exception) {
            fail(e.message ?: "tarayıcı yok")
        }
    }

    private fun typeRemote() {
        val box = EditText(this).apply { hint = "https://app.tskservis.com/sms#r=…" ; setSingleLine(false) }
        AlertDialog.Builder(this)
            .setTitle("TSK bağlantısı")
            .setView(box)
            .setPositiveButton("Eşleştir") { _, _ -> applyLink(box.text.toString()) }
            .setNegativeButton("Vazgeç", null)
            .show()
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
        val want = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 26) want.add(Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(want.toTypedArray(), 7)
    }

    // 5 Kas 2026 (S23 Ultra): Play Store dışından kurulan uygulamaya Android 13+ SMS iznini "kısıtlanmış ayar" diye
    // kapatıyor; izin penceresi hiç açılmıyor ya da "erişim reddedildi" diyor. Uygulama bu kilidi kendisi kaldıramaz:
    // kullanıcıya adım adım yol gösterilir ve Uygulama bilgileri ekranı doğrudan açılır.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && !hasSmsPermission()) showRestrictedHelp()
        if (requestCode == 7 && Store.line(this).isEmpty()) autoLine()
    }

    /** 1.10: numara kayıtlı değilse kendiliğinden: SIM → (bir kez) *101#. */
    private fun autoLine() {
        if (Store.line(this).isNotEmpty()) return
        val ussd = !Store.ussdTried(this)
        if (ussd) Store.setUssdTried(this)
        LineNumber.detect(this, ussd) { runOnUiThread { refresh() } }
    }

    private fun findLine(ussd: Boolean) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        LineNumber.detect(this, ussd) { n ->
            runOnUiThread {
                refresh()
                if (n == null) {
                    Toast.makeText(this, "Numara bulunamadı (SIM'de yazılı değil, *101# yanıt vermedi). Elle girin.", Toast.LENGTH_LONG).show()
                    typeLine()
                }
            }
        }
        refresh()
    }

    private fun typeLine() {
        val box = EditText(this).apply {
            hint = "05xx xxx xx xx"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(Store.line(this@MainActivity).let { if (it.isEmpty()) "" else LineNumber.pretty(it) })
        }
        AlertDialog.Builder(this)
            .setTitle("Bu hattın numarası")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val n = LineNumber.parse(box.text.toString())
                if (n == null) Toast.makeText(this, "Geçerli bir cep numarası yazın (05xx xxx xx xx).", Toast.LENGTH_LONG).show()
                else { LineNumber.save(this, n, "elle"); refresh() }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showRestrictedHelp() {
        val steps = if (Build.VERSION.SDK_INT >= 33)
            "SMS izni verilemedi. Android, Play Store dışından kurulan uygulamalarda bu izni kilitliyor. Kilidi açmak için:\n\n" +
            "1) Aşağıdaki 'Uygulama bilgilerini aç' düğmesine basın.\n" +
            "2) Sağ üstteki ⋮ (üç nokta) menüsüne dokunun.\n" +
            "3) 'Kısıtlanmış ayarlara izin ver'e dokunun ve telefon şifrenizle onaylayın.\n" +
            "4) Aynı ekranda İzinler → SMS → 'İzin ver'i seçin.\n" +
            "5) TSK SMS'e geri dönün.\n\n" +
            "⋮ menüsünde seçenek görünmüyorsa önce bir kez 'İzinler → SMS'e dokunup uyarıyı kapatın, sonra tekrar ⋮'e bakın."
        else
            "SMS izni verilemedi.\n\n1) 'Uygulama bilgilerini aç'a basın.\n2) İzinler → SMS → 'İzin ver'i seçin.\n3) TSK SMS'e geri dönün."
        AlertDialog.Builder(this)
            .setTitle("SMS izni gerekli")
            .setMessage(steps)
            .setPositiveButton("Uygulama bilgilerini aç") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Kapat", null)
            .show()
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
            if (Build.VERSION.SDK_INT >= 23 && !getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(packageName)) askBattery()     // 1.2: ilk başlatmada sorulur
        }
        refresh()
    }

    private fun updateClicked() {
        val known = Updater.latest
        Updater.status = if (known == null) "Denetleniyor…" else "İndiriliyor…"
        refresh()
        Thread({
            val info = known ?: Updater.check(this)
            if (info == null) {
                Updater.status = "✓ Güncel (sürüm ${Updater.currentName(this)})"
            } else {
                Updater.downloadAndInstall(this, info)
            }
            runOnUiThread { refresh() }
        }, "tsk-sms-update").start()
    }

    private fun pasteRemote() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = try { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (_: Exception) { "" }
        applyLink(clip)
    }

    /** 1.7: bağlantıyı uygular — internet eşleşmesi + (varsa) TSK'nın SMS şifresi + gönderimi başlatma. */
    private fun applyLink(text: String) {
        val r = Store.parseLink(text)
        if (r == null) {
            Toast.makeText(this, "TSK bağlantısı tanınmadı. TSK'da Ayarlar → SMS → 'Telefonu eşleştir' QR'ını okutun " +
                    "ya da oradaki bağlantıyı (https://…#r=…&k=…) kopyalayıp tekrar deneyin.", Toast.LENGTH_LONG).show()
            return
        }
        val pw = Store.linkParam(text, "p")
        val pwChanged = pw != null && pw.length in 4..64 && pw != Store.password(this)
        if (pw != null && pw.length in 4..64) Store.setPassword(this, pw)
        Store.setRemote(this, r)
        if (Store.enabled(this)) {
            if (pwChanged) { GatewayService.stop(this); GatewayService.start(this) }   // yerel ağdaki şifre de yenilensin
            RemoteLink.restart(this)
        } else if (hasSmsPermission()) {
            toggleServer()                                  // başlatır (RemoteLink servisle birlikte açılır)
            RemoteLink.restart(this)
        } else {
            askPermissions()
        }
        Toast.makeText(this, "Eşleşti" + (if (r.shop.isNotEmpty()) ": ${r.shop}" else "") +
                (if (Store.enabled(this)) "" else " — SMS izni verip 'Başlat'a basın"), Toast.LENGTH_LONG).show()
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
        val ln = Store.line(this)
        lineText.text = (if (ln.isEmpty()) "— (bulunamadı)" else LineNumber.pretty(ln)) +
            (if (LineNumber.status.isNotEmpty() && !LineNumber.status.startsWith("✓")) "\n" + LineNumber.status else "")
        creds.text = "Kullanıcı: ${Store.USER}     Şifre: ${Store.password(this)}"
        val perm = if (hasSmsPermission()) "" else "\n⚠ SMS izni verilmedi — 'İzinleri ver'e basın."
        log.text = "Gönderilen: ${Store.count(this)}$perm\n\n" + Store.logText(this)
        val r = Store.remote(this)
        remoteStatus.text = when {
            r == null -> "○ Eşleşmedi"
            !on -> "○ Eşleşti" + (if (r.shop.isNotEmpty()) " (${r.shop})" else "") + " — 'Başlat'a basın"
            else -> RemoteLink.state + (if (r.shop.isNotEmpty()) "\n${r.shop}" else "")
        }
        remoteStatus.setTextColor(if (r != null && on && RemoteLink.state.startsWith("✓")) Color.rgb(34, 197, 94)
                                  else Color.rgb(148, 163, 184))
        remoteRemove.visibility = if (r == null) android.view.View.GONE else android.view.View.VISIBLE
        val up = Updater.latest
        updateText.text = "Bu sürüm: ${Updater.currentName(this)}" +
            (if (up != null) "\nYeni sürüm var: ${up.version}" else "") +
            (if (Updater.status.isNotEmpty()) "\n${Updater.status}" else "")
        updateBtn.text = if (up != null) "Güncelle (${up.version})" else "Güncellemeleri denetle"
        val pm = getSystemService(PowerManager::class.java)
        val freeBattery = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
        battery.text = (if (freeBattery) "✓ Pil kısıtlaması kaldırılmış" else
            "⚠ Pil kısıtlaması açık — 'Arka planda kapanmasın'a basıp İzin ver deyin (yoksa SMS'ler gecikir)") +
            (if (Autostart.needed()) "\n• ${Build.MANUFACTURER}: 'Otomatik başlatma' ekranında TSK SMS'i açın ve pil " +
                "ayarını 'Kısıtlama yok' yapın." else "")
        battery.setTextColor(if (freeBattery) Color.rgb(34, 197, 94) else Color.rgb(245, 158, 11))
        showRecent()
    }

    /** 1.2: son gönderilenler — satıra dokununca telefonun Mesajlar uygulamasında o kişinin yazışması açılır. */
    private fun showRecent() {
        val items = Store.recent(this)
        val sig = items.joinToString("|") { it.first + it.second }
        if (sig == recentSig) return                      // 2 sn'de bir yenilenir: değişmediyse yeniden kurulmaz
        recentSig = sig
        recentBox.removeAllViews()
        if (items.isEmpty()) {
            recentBox.addView(text(13f, color = Color.rgb(148, 163, 184)).apply { text = "Henüz gönderim yok." })
            return
        }
        for ((stamp, to, msg) in items.take(15)) {
            recentBox.addView(text(14f, color = Color.rgb(203, 213, 225)).apply {
                text = "$stamp   $to\n$msg"
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.rgb(30, 41, 59))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                isClickable = true
                setOnClickListener { openConversation(to) }
            })
        }
    }

    private fun openConversation(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + Uri.encode(number))))
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))) }
            catch (_: Exception) {}
        }
    }
}
