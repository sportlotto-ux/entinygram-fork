package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import java.net.ServerSocket
import java.util.concurrent.Executors

object AwgEngine {
    private const val TAG = "InuAwg"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "inu-awg-engine").apply { isDaemon = true } }

    fun isRunning(): Boolean = try {
        Class.forName("desu.inugram.native.Awgproxy").getMethod("isRunning").invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun start(rawConf: String): Int {
        val port = freePort()
        val conf = ensureSocks(rawConf, port)
        io.execute {
            try {
                val cls = Class.forName("desu.inugram.native.Awgproxy")
                cls.getMethod("start", String::class.java, String::class.java)
                    .invoke(null, conf, AndroidUtilities.getCacheDir().absolutePath)
                InuConfig.AMNEZIA_SOCKS_PORT.value = port
                Log.d(TAG, "awg socks on ${VpnTunnelManager.SOCKS_HOST}:$port")
            } catch (e: Throwable) {
                Log.d(TAG, "awg start failed: ${e.message}")
            }
        }
        return port
    }

    fun stop() {
        io.execute {
            try {
                Class.forName("desu.inugram.native.Awgproxy").getMethod("stop").invoke(null)
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
