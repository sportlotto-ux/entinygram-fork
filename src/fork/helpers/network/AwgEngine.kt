package desu.inugram.helpers.network

import android.util.Log
import org.telegram.messenger.AndroidUtilities
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.util.concurrent.Executors

object AwgEngine {
    private const val TAG = "InuAwg"
    private const val CLASS_NAME = "desu.inugram.engine.awgproxy.Awgproxy"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "inu-awg-engine").apply { isDaemon = true } }

    fun isRunning(): Boolean = try {
        Class.forName(CLASS_NAME).getMethod("isRunning").invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun start(rawConf: String) {
        val port = freePort()
        val conf = ensureSocks(rawConf, port)
        io.execute {
            try {
                Log.d(TAG, "loading engine")
                val cls = Class.forName(CLASS_NAME)
                Log.d(TAG, "engine loaded, starting on $port")
                cls.getMethod("start", String::class.java, String::class.java)
                    .invoke(null, conf, AndroidUtilities.getCacheDir().absolutePath)
                Log.d(TAG, "awg socks on ${VpnTunnelManager.SOCKS_HOST}:$port")
                VpnTunnelManager.onEngineReady(port)
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                Log.d(TAG, "awg start failed: ${cause.javaClass.simpleName}: ${cause.message}")
                VpnTunnelManager.onEngineFailed(cause.message)
            }
        }
    }

    fun stop() {
        io.execute {
            try {
                Class.forName(CLASS_NAME).getMethod("stop").invoke(null)
            } catch (e: Throwable) {
                Log.d(TAG, "awg stop failed: ${e.message}")
            }
        }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun ensureSocks(raw: String, port: Int): String {
        val t = raw.trim()
        if (t.contains("[Socks5]", ignoreCase = true)) return t
        val addr = "${VpnTunnelManager.SOCKS_HOST}:$port"
        return "$t\n\n[Socks5]\nBindAddress = $addr\n"
    }
}
