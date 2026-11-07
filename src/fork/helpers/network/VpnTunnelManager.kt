package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager

object VpnTunnelManager {
    private const val TAG = "InuVpn"
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
        if (InuConfig.AMNEZIA_ENABLED.value) start()
    }

    fun start() {
        val raw = InuConfig.AMNEZIA_CONFIG.value.trim()
        if (raw.isEmpty()) return
        val parsed = VpnConfigParser.parse(raw) ?: return
        InuConfig.AMNEZIA_PROTOCOL.value = parsed.protocol
        activePort = when (parsed.protocol) {
            VpnConfigParser.PROTOCOL_AWG -> AwgEngine.start(parsed.raw)
            else -> 0
        }
        Log.d(TAG, "vpn start protocol=${parsed.protocol} port=$activePort")
    }

    fun stop() {
        AwgEngine.stop()
        activePort = 0
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
