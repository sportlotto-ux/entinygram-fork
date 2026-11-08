package desu.inugram.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.network.VpnConfigParser
import desu.inugram.helpers.network.VpnTunnelManager
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class VpnSettingsActivity : SettingsPageActivity() {
    private val configured: Boolean
        get() = InuConfig.AMNEZIA_CONFIG.value.trim().isNotEmpty()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuVpn)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuVpnConnection)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_VPN,
                R.string.InuVpnTunnel,
                R.string.InuVpnTunnelInfo,
                InuConfig.AMNEZIA_ENABLED.value,
            )
        )
        items.add(
            mkTwoLineEntry(
                ROW_CONFIG,
                R.drawable.inu_tabler_shield_lock,
                LocaleController.getString(R.string.InuVpnConfig),
                statusText(),
            )
        )
        items.add(mkSubPageButton(BTN_IMPORT, LocaleController.getString(R.string.InuVpnImportFile)))
        items.add(mkSubPageButton(BTN_PASTE, LocaleController.getString(R.string.InuVpnPaste)))
        if (configured) {
            items.add(mkSubPageButton(BTN_REMOVE, LocaleController.getString(R.string.InuVpnRemove)))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuVpnFooter)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_VPN -> {
                val enable = !InuConfig.AMNEZIA_ENABLED.value
                if (enable && !configured) {
                    item.checked = false
                    (view as? NotificationsCheckCell)?.isChecked = false
                    BulletinFactory.of(this)
                        .createErrorBulletin(LocaleController.getString(R.string.InuVpnNotConfigured))
                        .show()
                    return
                }
                VpnTunnelManager.setEnabled(enable)
                (view as? NotificationsCheckCell)?.isChecked = enable
                if (enable) checkEngineStarted()
            }

            BTN_IMPORT -> launchImport()
            BTN_PASTE -> importFromClipboard()
            BTN_REMOVE -> confirmRemove()
        }
    }

    private fun checkEngineStarted() {
        Utilities.globalQueue.postRunnable({
            AndroidUtilities.runOnUIThread {
                if (parentActivity == null || InuConfig.AMNEZIA_ENABLED.value) return@runOnUIThread
                listView?.adapter?.update(true)
                BulletinFactory.of(this)
                    .createErrorBulletin(LocaleController.getString(R.string.InuVpnEngineFailed))
                    .show()
            }
        }, 3500)
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/json", "application/octet-stream", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        Utilities.globalQueue.postRunnable {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) {
                null
            }
            AndroidUtilities.runOnUIThread {
                if (text == null) badConfig() else applyConfig(text)
            }
        }
    }

    private fun importFromClipboard() {
        val manager = ApplicationLoader.applicationContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        val text = if (clip == null) null else (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).text?.toString() }
        if (text == null) {
            badConfig()
            return
        }
        applyConfig(text)
    }

    private fun applyConfig(text: String) {
        val parsed = VpnConfigParser.parse(text)
        if (parsed == null) {
            badConfig()
            return
        }
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuVpn))
            .setMessage(describe(parsed))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                InuConfig.AMNEZIA_CONFIG.value = parsed.raw
                InuConfig.AMNEZIA_PROTOCOL.value = parsed.protocol
                VpnTunnelManager.restart()
                listView?.adapter?.update(true)
                BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuVpnImported))
                    .show()
            }
            .show()
    }

    private fun confirmRemove() {
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuVpnRemove))
            .setMessage(LocaleController.getString(R.string.InuVpnRemoveConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                VpnTunnelManager.setEnabled(false)
                InuConfig.AMNEZIA_CONFIG.value = ""
                InuConfig.AMNEZIA_PROTOCOL.value = VpnConfigParser.PROTOCOL_NONE
                listView?.adapter?.update(true)
            }
            .show()
    }

    private fun badConfig() {
        BulletinFactory.of(this)
            .createErrorBulletin(LocaleController.getString(R.string.InuVpnBadConfig))
            .show()
    }

    companion object {
        private val TOGGLE_VPN = InuUtils.generateId()
        private val ROW_CONFIG = InuUtils.generateId()
        private val BTN_IMPORT = InuUtils.generateId()
        private val BTN_PASTE = InuUtils.generateId()
        private val BTN_REMOVE = InuUtils.generateId()
        private const val REQ_IMPORT = 0x3701

        @JvmStatic
        fun statusText(): CharSequence {
            val raw = InuConfig.AMNEZIA_CONFIG.value.trim()
            if (raw.isEmpty()) return LocaleController.getString(R.string.InuVpnNotConfigured)
            val parsed = VpnConfigParser.parse(raw) ?: return LocaleController.getString(R.string.InuVpnNotConfigured)
            return describe(parsed)
        }

        private fun describe(parsed: VpnConfigParser.Parsed): CharSequence {
            val name = when (parsed.protocol) {
                VpnConfigParser.PROTOCOL_AWG -> "AmneziaWG"
                VpnConfigParser.PROTOCOL_XRAY -> "VLESS"
                else -> return ""
            }
            val host = parsed.endpointHost ?: return name
            val port = parsed.endpointPort
            return if (port != null) "$name · $host:$port" else "$name · $host"
        }

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "vpn",
            titleRes = R.string.InuVpn,
            iconRes = R.drawable.inu_tabler_shield_lock,
            factory = ::VpnSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("vpn-toggle", R.string.InuVpnTunnel, TOGGLE_VPN),
                SearchRegistry.Entry("vpn-import", R.string.InuVpnImportFile, BTN_IMPORT),
                SearchRegistry.Entry("vpn-paste", R.string.InuVpnPaste, BTN_PASTE),
            ),
        )
    }
}
