package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.helpers.NotificationsHelper
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
        VpnHealthProbe.stop()
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
        // "ready" only means the SOCKS listener is up, the strikes are cleared by a real probe
        VpnHealthProbe.start(port, ::clearStrikes, ::onTunnelDown)
    }

    @JvmStatic
    fun onEngineFailed(reason: String?) {
        Log.d(TAG, "engine failed: $reason")
        activePort = 0
        VpnHealthProbe.stop()
        clearStrikes()
        if (InuConfig.AMNEZIA_ENABLED.value) {
            InuConfig.AMNEZIA_ENABLED.value = false
            releaseKeepAlive()
            Utilities.globalQueue.postRunnable { reapplyProxy() }
        }
    }

    private fun onTunnelDown() {
        if (!InuConfig.AMNEZIA_ENABLED.value) return
        val strikes = InuConfig.AMNEZIA_START_STRIKES.value
        Log.d(TAG, "no traffic through tunnel (${VpnHealthProbe.lastError}), unconfirmed starts=$strikes")
        if (strikes < MAX_UNFINISHED_STARTS) {
            restartAfterDown()
            return
        }
        Log.d(TAG, "tunnel still down after $strikes restarts, disabling")
        InuConfig.AMNEZIA_ENABLED.value = false
        AwgEngine.stop()
        XrayEngine.stop()
        VpnHealthProbe.stop()
        activePort = 0
        releaseKeepAlive()
        Utilities.globalQueue.postRunnable { reapplyProxy() }
    }

    // restart() clears the strikes on purpose (manual toggle, new config), this one does not
    private fun restartAfterDown() {
        AwgEngine.stop()
        XrayEngine.stop()
        VpnHealthProbe.stop()
        activePort = 0
        if (InuConfig.AMNEZIA_ENABLED.value) start()
        Utilities.globalQueue.postRunnable { reapplyProxy() }
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
            if (!isKeepAliveEnabled()) {
                setKeepAlivePref(true)
                InuConfig.AMNEZIA_KEEPALIVE_SET.value = true
            }
            if (!NotificationsHelper.isBackgroundConnectionEnabled()) {
                NotificationsHelper.setBackgroundConnectionEnabled(true)
                InuConfig.AMNEZIA_PUSHCONN_SET.value = true
                Log.d(TAG, "background connection enabled")
            }
            ApplicationLoader.startPushService()
        }
    }

    private fun releaseKeepAlive() {
        AndroidUtilities.runOnUIThread {
            if (InuConfig.AMNEZIA_KEEPALIVE_SET.value) {
                InuConfig.AMNEZIA_KEEPALIVE_SET.value = false
                setKeepAlivePref(false)
            }
            if (InuConfig.AMNEZIA_PUSHCONN_SET.value) {
                InuConfig.AMNEZIA_PUSHCONN_SET.value = false
                NotificationsHelper.setBackgroundConnectionEnabled(false)
            }
            ApplicationLoader.startPushService()
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
