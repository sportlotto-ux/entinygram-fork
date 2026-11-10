package desu.inugram.helpers.calls

import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.voip.VoIPService

// entiny: live call translation starts with the call and stops with it
object CallTranslateHelper {
    private const val TAG = "InuCallTranslate"

    private var session: CallTranslateSession? = null
    private var overlay: CallTranslateOverlay? = null

    @Volatile
    var state: String = ""
        private set

    @JvmStatic
    fun onConnectionStateChanged(callState: Int, privateCall: Boolean) {
        if (callState != VoIPService.STATE_ESTABLISHED || !privateCall) return
        if (session != null || !InuConfig.CALL_TRANSLATE.value) return
        val url = InuConfig.CALL_TRANSLATE_URL.value.trim()
        val token = InuConfig.CALL_TRANSLATE_TOKEN.value.trim()
        if (url.isEmpty() || token.isEmpty()) {
            state = "no endpoint"
            return
        }
        val started = CallTranslateSession(
            url = url,
            token = token,
            langIn = InuConfig.CALL_TRANSLATE_LANG_IN.value.trim().ifEmpty { "ru" },
            langOut = InuConfig.CALL_TRANSLATE_LANG_OUT.value.trim().ifEmpty { "en" },
            listener = object : CallTranslateSession.Listener {
                override fun onSubtitle(dir: Int, text: String, isFinal: Boolean) {
                    if (InuConfig.CALL_TRANSLATE.value) overlay?.show(text)
                }

                override fun onState(newState: String) {
                    state = newState
                }
            },
        )
        session = started
        try {
            started.start()
        } catch (error: Throwable) {
            Log.d(TAG, "start failed: " + error.message)
            session = null
            started.stop()
        }
    }

    @JvmStatic
    fun onCallEnded() {
        val current = session ?: return
        session = null
        state = ""
        overlay?.hide()
        Thread({ current.stop() }, "inu-call-stop").start()
    }

    @JvmStatic
    fun attachOverlay(parent: FrameLayout) {
        if (!InuConfig.CALL_TRANSLATE.value) return
        val strip = CallTranslateOverlay(parent.context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        params.leftMargin = AndroidUtilities.dp(20f)
        params.rightMargin = AndroidUtilities.dp(20f)
        params.bottomMargin = AndroidUtilities.dp(130f)
        parent.addView(strip, params)
        overlay = strip
        parent.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                if (overlay === strip) overlay = null
            }
        })
    }
}
