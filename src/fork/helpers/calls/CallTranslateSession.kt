package desu.inugram.helpers.calls

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketException
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketFrame
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// entiny: opus over our ws proxy, subtitles back; the tap ring is drained here
internal class CallTranslateSession(
    private val url: String,
    private val token: String,
    private val langIn: String,
    private val langOut: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onSubtitle(dir: Int, text: String, isFinal: Boolean)

        fun onState(state: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val outbox = ArrayBlockingQueue<ByteArray>(256)

    @Volatile
    private var running = true

    @Volatile
    private var socket: WebSocket? = null

    private var pump: Thread? = null
    private var sender: Thread? = null
    private var dropped = 0

    fun start() {
        InuCallTap.nativeStart()
        pump = Thread({ pumpLoop() }, "inu-call-tap").also { it.start() }
        sender = Thread({ senderLoop() }, "inu-call-ws").also { it.start() }
    }

    fun stop() {
        running = false
        InuCallTap.nativeStop()
        try {
            socket?.disconnect()
        } catch (ignore: Exception) {
        }
        socket = null
        try {
            pump?.join(500)
            sender?.join(500)
        } catch (ignore: InterruptedException) {
        }
        pump = null
        sender = null
        outbox.clear()
    }

    private fun pumpLoop() {
        val ws = try {
            WebSocketFactory()
                .setConnectionTimeout(8000)
                .createSocket(target())
                .addHeader("X-Proxy-Token", token)
                .addListener(object : WebSocketAdapter() {
                    override fun onTextMessage(ws: WebSocket, text: String) = onMessage(text)

                    override fun onDisconnected(ws: WebSocket, server: WebSocketFrame?, client: WebSocketFrame?, closedByServer: Boolean) {
                        ui { listener.onState("disconnected") }
                    }

                    override fun onError(ws: WebSocket, error: WebSocketException) {
                        ui { listener.onState("error: " + error.message) }
                    }
                })
                .connect()
        } catch (error: Exception) {
            running = false
            ui { listener.onState("connect failed: " + error.message) }
            return
        }
        if (!running) {
            ws.disconnect()
            return
        }
        socket = ws
        ui { listener.onState("connected") }
        val packet = ByteArray(512)
        while (running && ws.isOpen) {
            var got = false
            for (dir in DIRS) {
                val size = InuCallTap.nativeRead(dir, packet)
                if (size <= 0) continue
                val frame = ByteArray(size + 1)
                frame[0] = if (dir == InuCallTap.IN) TAG_IN else TAG_OUT
                System.arraycopy(packet, 0, frame, 1, size)
                if (!outbox.offer(frame)) dropped++
                got = true
            }
            if (!got) {
                try {
                    Thread.sleep(4)
                } catch (ignore: InterruptedException) {
                    return
                }
            }
        }
        running = false
        ws.disconnect()
    }

    private fun senderLoop() {
        while (running) {
            val frame = try {
                outbox.poll(200, TimeUnit.MILLISECONDS)
            } catch (ignore: InterruptedException) {
                return
            } ?: continue
            val ws = socket ?: continue
            try {
                ws.sendBinary(frame, true)
            } catch (error: Exception) {
                Log.d(TAG, "send failed: " + error.message)
                running = false
            }
        }
    }

    private fun onMessage(text: String) {
        val event = try {
            JSONObject(text)
        } catch (ignore: Exception) {
            return
        }
        when (event.optString("t")) {
            "sub" -> {
                val dst = event.optString("dst").trim()
                if (dst.isEmpty()) return
                val dir = if (event.optString("dir") == "in") InuCallTap.IN else InuCallTap.OUT
                val last = event.optBoolean("final")
                ui { listener.onSubtitle(dir, dst, last) }
            }
            "state" -> {
                val state = event.optString("gemini")
                ui { listener.onState(state) }
            }
        }
    }

    private fun target(): String {
        val separator = if (url.contains('?')) '&' else '?'
        return url + separator + "in=" + langIn + "&out=" + langOut
    }

    private fun ui(block: () -> Unit) {
        if (!running) return
        main.post {
            if (running) block()
        }
    }

    private companion object {
        const val TAG = "InuCallTranslate"
        const val TAG_IN: Byte = 0x10
        const val TAG_OUT: Byte = 0x11
        val DIRS = intArrayOf(InuCallTap.IN, InuCallTap.OUT)
    }
}
