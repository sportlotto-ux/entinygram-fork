package desu.inugram.helpers.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities

// entiny: subtitle strip drawn over the call window
@SuppressLint("ViewConstructor")
internal class CallTranslateOverlay(context: Context) : FrameLayout(context) {

    private val label = TextView(context)

    private val hide = Runnable { animate().alpha(0f).setDuration(220).start() }

    init {
        alpha = 0f
        isClickable = false
        label.setTextColor(Color.WHITE)
        label.textSize = 17f
        label.gravity = Gravity.CENTER
        label.setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
        label.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        label.setPadding(dp(14), dp(8), dp(14), dp(8))
        label.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xB3000000.toInt())
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(text: String) {
        if (text.isEmpty()) {
            hide()
            return
        }
        label.text = text
        animate().cancel()
        animate().alpha(1f).setDuration(160).start()
        removeCallbacks(hide)
        postDelayed(hide, HIDE_DELAY_MS)
    }

    fun hide() {
        removeCallbacks(hide)
        animate().cancel()
        animate().alpha(0f).setDuration(160).start()
    }

    private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

    private companion object {
        const val HIDE_DELAY_MS = 6000L
    }
}
