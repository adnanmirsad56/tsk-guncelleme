package net.gsmturkey.tsksms

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 1.4: İnternet üzerinden SMS. Telefon, TSK'nın uzaktan erişim köprüsüne (dükkânın odasına) dışarıya doğru bağlanır;
 * kasa telefonu yerel ağda bulamazsa SMS işini bu kanaldan gönderir. İçerik uçtan uca AES-GCM şifreli (anahtar yalnız
 * kasada ve bu telefonda — köprü okuyamaz). Kasa telefonu SMS şifresiyle tanır (yerel ağdaki gibi).
 *
 * İletiler (şifreli JSON): telefon → kasa  {"t":"gw-hello", id, name, ver, pw}  {"t":"gw-result", job, ok, error}
 *                          kasa → telefon  {"t":"gw-ok"}  {"t":"gw-err", error}  {"t":"sms", job, to, text, sim}
 */
object RemoteLink {
    @Volatile var state: String = "Kapalı"
        private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val rnd = SecureRandom()
    private var backoff = 5_000L
    private const val HELLO_MS = 30_000L
    private const val VERSION = "1.4"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }
    private val helloTick = object : Runnable {
        override fun run() {
            sendHello()
            main.postDelayed(this, HELLO_MS)          // kasa yeniden bağlanınca da telefonu tanısın
        }
    }
    private val reconnect = Runnable { connect() }

    fun start(c: Context) {
        ctx = c.applicationContext
        if (running) return
        if (Store.remote(c) == null) {
            state = "Eşleşmedi — TSK'dan gelen bağlantıyı yapıştırın"
            return
        }
        running = true
        backoff = 5_000L
        main.post { connect() }
    }

    fun stop() {
        running = false
        main.removeCallbacks(helloTick)
        main.removeCallbacks(reconnect)
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        state = "Kapalı"
    }

    fun restart(c: Context) {
        stop()
        start(c)
    }

    private fun connect() {
        val c = ctx ?: return
        if (!running) return
        val p = Store.remote(c)
        if (p == null) {
            running = false
            state = "Eşleşmedi"
            return
        }
        state = "Bağlanıyor…"
        // 1.9: köprü odaya girişte cihaz jetonu ister (dükkân anahtarından HMAC — tarayıcıyla aynı); yoksa 403
        val url = p.base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/') +
                "/client?room=" + p.room + "&t=" + joinToken(p.key)
        val req = try {
            Request.Builder().url(url).header("User-Agent", "TSK SMS/$VERSION").build()
        } catch (e: Exception) {
            running = false
            state = "✗ Bağlantı adresi geçersiz"
            return
        }
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoff = 5_000L
                state = "Bağlandı — kasa bekleniyor…"
                main.removeCallbacks(helloTick)
                main.post(helloTick)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (m.optString("t")) {
                    "hello" -> if (!m.optBoolean("online")) state = "Bağlandı — dükkândaki kasa şu an kapalı"
                    "offline" -> state = "Bağlandı — dükkândaki kasa şu an kapalı"
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val c2 = ctx ?: return
                val obj = decrypt(c2, bytes.toByteArray()) ?: return
                when (obj.optString("t")) {
                    "gw-ok" -> state = "✓ Bağlı — kasa internetten SMS gönderebilir"
                    "gw-err" -> state = "✗ " + obj.optString("error")
                    "sms" -> pool.execute { handleSms(c2, webSocket, obj) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket, "Bağlantı kapandı")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val body = try { response?.body?.string()?.take(160) ?: "" } catch (_: Exception) { "" }
                if (code == 403 || code == 429) backoff = maxOf(backoff, 300_000L)   // ret / sınır: sık deneme engel yemesin
                dropped(webSocket, when {
                    code == 400 -> "Bağlantı geçersiz"
                    code == 403 -> "Köprü kabul etmedi" + (if (body.isNotBlank()) ": $body" else " (TSK'da QR'ı yeniden okutun)")
                    code == 429 -> "Çok fazla deneme" + (if (body.isNotBlank()) ": $body" else "")
                    code > 0 -> "Köprü hatası ($code)"
                    else -> "İnternet bağlantısı yok"
                })
            }
        })
    }

    private fun dropped(w: WebSocket, why: String) {
        main.post {
            if (ws !== w) return@post
            ws = null
            main.removeCallbacks(helloTick)
            if (!running) return@post
            state = "$why — ${backoff / 1000} sn sonra yeniden denenecek"
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun sendHello() {
        val c = ctx ?: return
        val w = ws ?: return
        send(c, w, JSONObject().put("t", "gw-hello").put("id", Store.deviceId(c))
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("ver", VERSION).put("pw", Store.password(c)))
    }

    private fun handleSms(c: Context, w: WebSocket, job: JSONObject) {
        val res = JSONObject().put("t", "gw-result").put("job", job.optString("job"))
        val to = job.optString("to")
        val text = job.optString("text")
        val sim = if (!job.has("sim") || job.isNull("sim")) null else job.optInt("sim")
        try {
            if (to.isEmpty() || text.isEmpty()) throw IllegalArgumentException("boş mesaj")
            SmsSender.send(c, to, text, sim)
            Store.addCount(c)
            Store.log(c, "→ (internet) ${mask(to)}  ${text.take(40)}")
            Store.addRecent(c, to, text)
            res.put("ok", true)
        } catch (e: SecurityException) {
            Store.log(c, "✗ SMS izni yok")
            res.put("ok", false).put("error", "Telefonda SMS izni verilmemiş")
        } catch (e: Exception) {
            Store.log(c, "✗ ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            res.put("ok", false).put("error", e.message ?: "hata")
        }
        send(c, w, res)
    }

    private fun mask(n: String) =
        if (n.length > 6) n.take(n.length - 4).replace(Regex("\\d(?=\\d{3})"), "•") + n.takeLast(4) else n

    // -------------------------------------------------- uçtan uca şifre (TSK / telefon sayfasıyla aynı: IV 12 + AES-GCM)
    /** Köprüye gösterilen jeton: base64url(HMAC-SHA256(dükkân anahtarı, "tsk-client-join")) — köprü yalnız özetini bilir. */
    private fun joinToken(k0: String): String {
        var k = k0
        while (k.length % 4 != 0) k += "="
        return try {
            val raw = Base64.decode(k, Base64.URL_SAFE or Base64.NO_WRAP)
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(raw, "HmacSHA256"))
            Base64.encodeToString(mac.doFinal("tsk-client-join".toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) { "" }
    }

    private fun key(c: Context): SecretKeySpec? {
        var k = Store.remote(c)?.key ?: return null
        while (k.length % 4 != 0) k += "="
        val raw = try { Base64.decode(k, Base64.URL_SAFE or Base64.NO_WRAP) } catch (_: Exception) { return null }
        if (raw.size != 16 && raw.size != 24 && raw.size != 32) return null
        return SecretKeySpec(raw, "AES")
    }

    private fun send(c: Context, w: WebSocket, obj: JSONObject) {
        val k = key(c) ?: return
        try {
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
            val ct = ci.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
            w.send((iv + ct).toByteString())
        } catch (_: Exception) {}
    }

    private fun decrypt(c: Context, data: ByteArray): JSONObject? {
        if (data.size < 12 + 16) return null
        val k = key(c) ?: return null
        return try {
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            JSONObject(String(ci.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
