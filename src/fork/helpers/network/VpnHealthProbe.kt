package desu.inugram.helpers.network

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

// entiny: "engine ready" is not "tunnel passes traffic", so the state comes from real dials
object VpnHealthProbe {
    private const val TAG = "InuVpnProbe"
    private const val SOCKS_HOST = "127.0.0.1"
    private const val AWG_CLASS = "desu.inugram.engine.awgproxy.Awgproxy"

    const val STATE_IDLE = 0
    const val STATE_STARTING = 1
    const val STATE_UP = 2
    const val STATE_WEAK = 3
    const val STATE_DOWN = 4

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val FIRST_PROBE_DELAY_MS = 1200L
    private const val FAST_INTERVAL_MS = 15_000L
    private const val SLOW_INTERVAL_MS = 60_000L
    private const val SLOW_AFTER_OKS = 4
    private const val DOWN_AFTER_FAILS = 4
    private const val WEAK_LATENCY_MS = 2000

    private val targets = arrayOf("1.1.1.1", "149.154.167.51")

    @Volatile
    var state: Int = STATE_IDLE
        private set

    @Volatile
    var latencyMs: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var handshakeAgeSec: Long = 0
        private set

    @Volatile
    var rxBytes: Long = 0
        private set

    @Volatile
    var txBytes: Long = 0
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var port = 0
    private var fails = 0
    private var oks = 0
    private var onUp: (() -> Unit)? = null
    private var onDown: (() -> Unit)? = null
    private var peerStatusMethod: Method? = null

    fun start(socksPort: Int, onUp: () -> Unit, onDown: () -> Unit) {
        stop()
        port = socksPort
        this.onUp = onUp
        this.onDown = onDown
        state = STATE_STARTING
        val t = HandlerThread("inu-vpn-probe")
        t.start()
        thread = t
        handler = Handler(t.looper).also { h -> h.postDelayed(probe, FIRST_PROBE_DELAY_MS) }
        Log.d(TAG, "probe started on $SOCKS_HOST:$socksPort")
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
        port = 0
        fails = 0
        oks = 0
        onUp = null
        onDown = null
        state = STATE_IDLE
        latencyMs = 0
    }

    fun stateRes(): Int = when (state) {
        STATE_UP -> org.telegram.messenger.R.string.InuVpnStateConnected
        STATE_WEAK -> org.telegram.messenger.R.string.InuVpnStateWeak
        STATE_DOWN -> org.telegram.messenger.R.string.InuVpnStateDown
        else -> org.telegram.messenger.R.string.InuVpnStateConnecting
    }

    private val probe = object : Runnable {
        override fun run() {
            val socksPort = port
            if (socksPort == 0) return
            val started = SystemClock.elapsedRealtime()
            val error = dial(socksPort)
            val took = (SystemClock.elapsedRealtime() - started).toInt()
            val h = handler ?: return
            if (error == null) {
                fails = 0
                oks++
                latencyMs = took
                lastError = null
                state = if (took > WEAK_LATENCY_MS) STATE_WEAK else STATE_UP
                if (oks == 1) {
                    Log.d(TAG, "tunnel up, first probe $took ms")
                    onUp?.invoke()
                }
                readPeerStatus()
                h.postDelayed(this, if (oks >= SLOW_AFTER_OKS) SLOW_INTERVAL_MS else FAST_INTERVAL_MS)
            } else {
                fails++
                oks = 0
                lastError = error
                if (fails >= DOWN_AFTER_FAILS) {
                    state = STATE_DOWN
                    Log.d(TAG, "tunnel down after $fails probes: $error")
                    onDown?.invoke()
                    return
                }
                state = if (fails < 2) STATE_STARTING else STATE_WEAK
                Log.d(TAG, "probe $fails failed: $error")
                h.postDelayed(this, retryDelay())
            }
        }
    }

    private fun retryDelay(): Long = when (fails) {
        1 -> 2500L
        2 -> 4000L
        3 -> 6000L
        else -> FAST_INTERVAL_MS
    }

    private fun dial(socksPort: Int): String? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, socksPort))
        var error: String? = null
        for (host in targets) {
            try {
                Socket(proxy).use { socket ->
                    socket.connect(InetSocketAddress.createUnresolved(host, 443), CONNECT_TIMEOUT_MS)
                }
                return null
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return error ?: "no route"
    }

    private fun readPeerStatus() {
        if (InuConfig.AMNEZIA_PROTOCOL.value != VpnConfigParser.PROTOCOL_AWG) return
        try {
            val method = peerStatusMethod
                ?: Class.forName(AWG_CLASS).getMethod("GetPeerStatus").also { peerStatusMethod = it }
            val raw = method.invoke(null) as? String
            if (raw.isNullOrEmpty()) return
            val json = JSONObject(raw)
            val handshake = json.optLong("handshake")
            handshakeAgeSec = if (handshake > 0) (System.currentTimeMillis() / 1000 - handshake).coerceAtLeast(0) else 0
            rxBytes = json.optLong("rx")
            txBytes = json.optLong("tx")
            Log.d(TAG, "peer handshake ${handshakeAgeSec}s ago, rx=$rxBytes tx=$txBytes")
        } catch (_: Throwable) {
        }
    }
}
