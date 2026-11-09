package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager

object VpnTunnelManager {
    private const val TAG = "InuVpn"
    private const val START_DELAY_MS = 3000L
    private const val READY_SETTLE_MS = 1500L
    private const val MAX_UNFINISHED_STARTS = 2

    const val SOCKS_HOST = "127.0.0.1"

    @Volatile private var activePort: Int = 0

    @JvmStatic
    fun isActive(): Boolean = InuConfig.AMNEZIA_ENABLED.value && activePort != 0

    @JvmStatic
    fun socksPort(): Int = activePort

    fun setEnabled(enabled: Boolean) {
        InuConfig.AMNEZIA_ENABLED.value = enabled
        if (enabled) start() else stop()
        Utilities.globalQueue.postRunnable { reapplyProxy() }
    }

    fun init() {
        if (!InuConfig.AMNEZIA_ENABLED.value) return
        val strikes = InuConfig.AMNEZIA_START_STRIKES.value
        if (strikes >= MAX_UNFINISHED_STARTS) {
            Log.d(TAG, "engine died $strikes starts in a row, tunnel disabled")
            clearStrikes()
            InuConfig.AMNEZIA_ENABLED.value = false
            releaseKeepAlive()
            return
        }
        Utilities.globalQueue.postRunnable({ if (InuConfig.AMNEZIA_ENABLED.value) start() }, START_DELAY_MS)
    }

    fun start() {
        val raw = InuConfig.AMNEZIA_CONFIG.value.trim()
        if (raw.isEmpty()) return
        val parsed = VpnConfigParser.parse(raw) ?: return
        InuConfig.AMNEZIA_PROTOCOL.value = parsed.protocol
        ensureKeepAlive()
        markUnfinishedStart()
        Log.d(TAG, "vpn start protocol=${parsed.protocol}")
        when (parsed.protocol) {
            VpnConfigParser.PROTOCOL_AWG -> AwgEngine.start(parsed.raw)
            VpnConfigParser.PROTOCOL_XRAY -> XrayEngine.start(parsed.raw)
            else -> clearStrikes()
        }
    }

    fun stop() {
        AwgEngine.stop()
        XrayEngine.stop()
        activePort = 0
        clearStrikes()
        releaseKeepAlive()
    }

    fun restart() {
        stop()
        if (InuConfig.AMNEZIA_ENABLED.value) start()
        Utilities.globalQueue.postRunnable { reapplyProxy() }
    }

    @JvmStatic
    fun onEngineReady(port: Int) {
        activePort = port
        InuConfig.AMNEZIA_SOCKS_PORT.value = port
        Log.d(TAG, "engine ready on $SOCKS_HOST:$port")
        Utilities.globalQueue.postRunnable { reapplyProxy() }
        Utilities.globalQueue.postRunnable({ if (activePort == port) clearStrikes() }, READY_SETTLE_MS)
    }

    @JvmStatic
    fun onEngineFailed(reason: String?) {
        Log.d(TAG, "engine failed: $reason")
        activePort = 0
        clearStrikes()
        if (InuConfig.AMNEZIA_ENABLED.value) {
            InuConfig.AMNEZIA_ENABLED.value = false
            releaseKeepAlive()
            Utilities.globalQueue.postRunnable { reapplyProxy() }
        }
    }

    private fun markUnfinishedStart() {
        InuConfig.AMNEZIA_START_STRIKES.value = InuConfig.AMNEZIA_START_STRIKES.value + 1
    }

    private fun clearStrikes() {
        InuConfig.AMNEZIA_START_STRIKES.value = 0
    }

    // entiny: the tunnel lives in this process, the stock keep-alive service keeps the process out of cached
    private fun ensureKeepAlive() {
        AndroidUtilities.runOnUIThread {
            if (isKeepAliveEnabled()) return@runOnUIThread
            setKeepAlivePref(true)
            InuConfig.AMNEZIA_KEEPALIVE_SET.value = true
            ApplicationLoader.startPushService()
            Log.d(TAG, "keep-alive service enabled")
        }
    }

    private fun releaseKeepAlive() {
        if (!InuConfig.AMNEZIA_KEEPALIVE_SET.value) return
        InuConfig.AMNEZIA_KEEPALIVE_SET.value = false
        AndroidUtilities.runOnUIThread {
            setKeepAlivePref(false)
            ApplicationLoader.startPushService()
            Log.d(TAG, "keep-alive service disabled")
        }
    }

    private fun isKeepAliveEnabled(): Boolean {
        val prefs = MessagesController.getGlobalNotificationsSettings()
        if (prefs.contains("pushService")) return prefs.getBoolean("pushService", true)
        return MessagesController.getMainSettings(UserConfig.selectedAccount.coerceAtLeast(0)).getBoolean("keepAliveService", false)
    }

    private fun setKeepAlivePref(enabled: Boolean) {
        MessagesController.getGlobalNotificationsSettings().edit().putBoolean("pushService", enabled).apply()
        val account = UserConfig.selectedAccount
        if (account > 0) MessagesController.getNotificationsSettings(account).edit().putBoolean("pushService", enabled).apply()
    }

    private fun reapplyProxy() {
        if (isActive()) {
            ConnectionsManager.setProxySettings(true, SOCKS_HOST, activePort, "", "", "")
            return
        }
        if (CensorshipHelper.isTunnelActive()) {
            ConnectionsManager.setProxySettings(true, CensorshipHelper.tunnelHost(), CensorshipHelper.tunnelPort(), "", "", CensorshipHelper.tunnelSecret())
            return
        }
        val prefs = MessagesController.getGlobalMainSettings()
        val proxy = SharedConfig.currentProxy
        if (prefs.getBoolean("proxy_enabled", false) && proxy != null) {
            ConnectionsManager.setProxySettings(true, proxy.settings.address, proxy.settings.port, proxy.settings.user, proxy.settings.password, proxy.settings.secret)
        } else {
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "")
        }
    }
}
