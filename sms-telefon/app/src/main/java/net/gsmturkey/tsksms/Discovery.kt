package net.gsmturkey.tsksms

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Ağda bulunma: TSK yerel ağa "TSK-SMS-BUL" yayını (UDP 47815) gönderir; bu telefon kendi adresini / portunu / kimliğini
 * söyler. Şifre GÖNDERİLMEZ (TSK'da bir kez girilir). Modem yeniden başlayıp telefonun adresi değişince TSK aynı
 * kimlikle telefonu yeniden bulur.
 */
class Discovery(private val ctx: Context, private val httpPort: Int) {
    @Volatile private var sock: DatagramSocket? = null

    fun start() {
        if (sock != null) return
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(PORT))
        sock = s
        Thread({
            val buf = ByteArray(512)
            while (!s.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("TSK-SMS-BUL")) {
                        val reply = JSONObject()
                            .put("app", "TSK SMS")
                            .put("id", Store.deviceId(ctx))
                            .put("port", httpPort)
                            .put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                            .put("on", Store.enabled(ctx))
                            .toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                    }
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "tsk-sms-discovery").start()
    }

    fun stop() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null
    }

    companion object {
        const val PORT = 47815
    }
}
