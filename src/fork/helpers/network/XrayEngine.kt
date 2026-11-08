package desu.inugram.helpers.network

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.util.concurrent.Executors

object XrayEngine {
    private const val TAG = "InuXray"
    private const val CLASS_NAME = "desu.inugram.engine.xrayproxy.Xrayproxy"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "inu-xray-engine").apply { isDaemon = true } }

    fun isRunning(): Boolean = try {
        Class.forName(CLASS_NAME).getMethod("isRunning").invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun start(vlessUri: String) {
        val port = freePort()
        io.execute {
            try {
                Log.d(TAG, "loading engine")
                val cls = Class.forName(CLASS_NAME)
                Log.d(TAG, "engine loaded, starting on $port")
                cls.getMethod("start", String::class.java, java.lang.Integer.TYPE).invoke(null, vlessUri, port)
                Log.d(TAG, "xray socks on ${VpnTunnelManager.SOCKS_HOST}:$port")
                VpnTunnelManager.onEngineReady(port)
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                Log.d(TAG, "xray start failed: ${cause.javaClass.simpleName}: ${cause.message}")
                VpnTunnelManager.onEngineFailed(cause.message)
            }
        }
    }

    fun stop() {
        io.execute {
            try {
                Class.forName(CLASS_NAME).getMethod("stop").invoke(null)
            } catch (e: Throwable) {
                Log.d(TAG, "xray stop failed: ${e.message}")
            }
        }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
