package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.calls.CallTranslateHelper
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class CallTranslateSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCallTranslate)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val notSet = LocaleController.getString(R.string.InuCallTranslateNotSet)
        items.add(
            mkTwoLineCheckItem(
                TOGGLE,
                R.string.InuCallTranslate,
                R.string.InuCallTranslateInfo,
                InuConfig.CALL_TRANSLATE.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCallTranslateServer)))
        items.add(
            UItem.asButton(
                BUTTON_URL,
                LocaleController.getString(R.string.InuCallTranslateEndpoint),
                InuConfig.CALL_TRANSLATE_URL.value.ifBlank { notSet },
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_TOKEN,
                LocaleController.getString(R.string.InuCallTranslateToken),
                mask(InuConfig.CALL_TRANSLATE_TOKEN.value, notSet),
            )
        )
        items.add(UItem.asShadow(CallTranslateHelper.state.ifEmpty { LocaleController.getString(R.string.InuCallTranslateHint) }))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCallTranslateLanguages)))
        items.add(
            UItem.asButton(
                BUTTON_LANG_IN,
                LocaleController.getString(R.string.InuCallTranslateLangIn),
                InuConfig.CALL_TRANSLATE_LANG_IN.value,
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_LANG_OUT,
                LocaleController.getString(R.string.InuCallTranslateLangOut),
                InuConfig.CALL_TRANSLATE_LANG_OUT.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE -> {
                val new = InuConfig.CALL_TRANSLATE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            BUTTON_URL -> ask(R.string.InuCallTranslateEndpoint, InuConfig.CALL_TRANSLATE_URL.value, InputType.TYPE_TEXT_VARIATION_URI) {
                if (it.isEmpty()) return@ask
                InuConfig.CALL_TRANSLATE_URL.value = it
            }
            BUTTON_TOKEN -> ask(R.string.InuCallTranslateToken, InuConfig.CALL_TRANSLATE_TOKEN.value) {
                InuConfig.CALL_TRANSLATE_TOKEN.value = it
            }
            BUTTON_LANG_IN -> ask(R.string.InuCallTranslateLangIn, InuConfig.CALL_TRANSLATE_LANG_IN.value) {
                if (it.isEmpty()) return@ask
                InuConfig.CALL_TRANSLATE_LANG_IN.value = it.lowercase()
            }
            BUTTON_LANG_OUT -> ask(R.string.InuCallTranslateLangOut, InuConfig.CALL_TRANSLATE_LANG_OUT.value) {
                if (it.isEmpty()) return@ask
                InuConfig.CALL_TRANSLATE_LANG_OUT.value = it.lowercase()
            }
        }
    }

    private fun ask(titleRes: Int, initial: String, inputType: Int = InputType.TYPE_CLASS_TEXT, apply: (String) -> Unit) {
        showInputDialog(
            this,
            LocaleController.getString(titleRes),
            initialText = initial,
            selectAll = true,
            inputType = inputType,
        ) { text ->
            apply(text.trim())
            listView.adapter.update(true)
            true
        }
    }

    private fun mask(value: String, empty: CharSequence): CharSequence {
        if (value.isEmpty()) return empty
        return if (value.length <= 6) "…" else value.take(4) + "…" + value.takeLast(2)
    }

    companion object {
        private val TOGGLE = InuUtils.generateId()
        private val BUTTON_URL = InuUtils.generateId()
        private val BUTTON_TOKEN = InuUtils.generateId()
        private val BUTTON_LANG_IN = InuUtils.generateId()
        private val BUTTON_LANG_OUT = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "call-translate",
            titleRes = R.string.InuCallTranslate,
            iconRes = R.drawable.inu_tabler_language,
            factory = ::CallTranslateSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("call-translate-toggle", R.string.InuCallTranslate, TOGGLE),
                SearchRegistry.Entry("call-translate-endpoint", R.string.InuCallTranslateEndpoint, BUTTON_URL),
                SearchRegistry.Entry("call-translate-token", R.string.InuCallTranslateToken, BUTTON_TOKEN),
                SearchRegistry.Entry("call-translate-lang-in", R.string.InuCallTranslateLangIn, BUTTON_LANG_IN),
                SearchRegistry.Entry("call-translate-lang-out", R.string.InuCallTranslateLangOut, BUTTON_LANG_OUT),
            ),
        )
    }
}
