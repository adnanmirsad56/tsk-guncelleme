package net.gsmturkey.tsksms

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Yerel ağ sunucusu (TSK ile uyumlu):
 *   POST /message   Basic auth (tsk / şifre)  {"message": "...", "phoneNumbers": ["+90..."], "simNumber": 1}
 *   GET  /health    {"status": "ok"}
 * Yalnız yerel ağdan erişilir (telefonun Wi-Fi adresi); internete açılmaz.
 */
class SmsServer(private val ctx: Context, private val port: Int) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "tsk-sms-server").start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            c.soTimeout = 10000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            try {
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val req = lines.first().split(" ")
                if (req.size < 2) return respond(out, 400, """{"error":"bad request"}""")
                val method = req[0].uppercase()
                val path = req[1].substringBefore("?")
                val headers = lines.drop(1).mapNotNull {
                    val i = it.indexOf(':')
                    if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
                }.toMap()
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                if (method == "GET" && path == "/health") {
                    return respond(out, 200, """{"status":"ok","app":"TSK SMS"}""")
                }
                if (!authorized(headers["authorization"])) {
                    return respond(out, 401, """{"error":"unauthorized"}""", "WWW-Authenticate: Basic realm=\"TSK SMS\"\r\n")
                }
                if (method == "POST" && path == "/message") {
                    val json = JSONObject(String(body, Charsets.UTF_8))
                    val text = json.optString("message", "").trim()
                    val numbers: JSONArray = json.optJSONArray("phoneNumbers") ?: JSONArray()
                    val sim = if (json.has("simNumber")) json.optInt("simNumber") else null
                    if (text.isEmpty() || numbers.length() == 0) {
                        return respond(out, 400, """{"error":"message and phoneNumbers required"}""")
                    }
                    for (i in 0 until numbers.length()) {
                        val to = numbers.getString(i)
                        SmsSender.send(ctx, to, text, sim)
                        Store.addCount(ctx)
                        Store.log(ctx, "→ ${mask(to)}  ${text.take(40)}")
                        Store.addRecent(ctx, to, text)          // 1.2: dokununca Mesajlar'da o yazışma açılır
                    }
                    return respond(out, 202, """{"state":"Pending"}""")
                }
                respond(out, 404, """{"error":"not found"}""")
            } catch (e: SecurityException) {
                Store.log(ctx, "✗ SMS izni yok")
                respond(out, 500, """{"error":"SMS permission missing"}""")
            } catch (e: Exception) {
                Store.log(ctx, "✗ ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                respond(out, 500, JSONObject().put("error", e.message ?: "error").toString())
            }
        }
    }

    private fun mask(n: String) = if (n.length > 6) n.take(n.length - 4).replace(Regex("\\d(?=\\d{3})"), "•") + n.takeLast(4) else n

    private fun authorized(h: String?): Boolean {
        if (h == null || !h.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val expected = "${Store.USER}:${Store.password(ctx)}"
        return MessageDigest.isEqual(decoded.toByteArray(), expected.toByteArray())
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd()
    }

    private fun respond(out: OutputStream, code: Int, body: String, extra: String = "") {
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; else -> "Error" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }
}
