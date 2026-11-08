package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import java.net.ServerSocket
import java.util.concurrent.Executors

object XrayEngine {
    private const val TAG = "InuXray"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "inu-xray-engine").apply { isDaemon = true } }

    fun isRunning(): Boolean = try {
        Class.forName("desu.inugram.xrayproxy.Xrayproxy").getMethod("isRunning").invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun start(vlessUri: String): Int {
        val port = freePort()
        io.execute {
            try {
                val cls = Class.forName("desu.inugram.xrayproxy.Xrayproxy")
                cls.getMethod("start", String::class.java, java.lang.Integer.TYPE)
                    .invoke(null, vlessUri, port)
                InuConfig.AMNEZIA_SOCKS_PORT.value = port
                Log.d(TAG, "xray socks on ${VpnTunnelManager.SOCKS_HOST}:$port")
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                Log.d(TAG, "xray start failed: ${cause.message}")
            }
        }
        return port
    }

    fun stop() {
        io.execute {
            try {
                Class.forName("desu.inugram.xrayproxy.Xrayproxy").getMethod("stop").invoke(null)
            } catch (e: Throwable) {
                Log.d(TAG, "xray stop failed: ${e.message}")
            }
        }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
