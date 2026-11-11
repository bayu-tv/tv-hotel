package com.bayutv.hotel

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Kotak pemberitahuan di tengah layar, transparan seperti kaca.
class Notice(private val ctx: Context, parent: FrameLayout) {

    private val ui = Handler(Looper.getMainLooper())
    private val box = LinearLayout(ctx)
    private val title = TextView(ctx)
    private val msg = TextView(ctx)
    private val autoHide = Runnable { fade() }

    init {
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(Ui.dp(ctx, 40), Ui.dp(ctx, 24), Ui.dp(ctx, 40), Ui.dp(ctx, 26))
        val g = GradientDrawable()
        g.cornerRadius = Ui.dp(ctx, 20).toFloat()
        g.setColor(0xA00F0E0D.toInt())
        g.setStroke(Ui.dp(ctx, 1), 0x44FFFFFF)
        box.background = g

        title.setTextColor(0xFFB8975A.toInt())
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(ctx, 13f))
        title.letterSpacing = 0.22f
        title.gravity = Gravity.CENTER_HORIZONTAL
        title.setPadding(0, 0, 0, Ui.dp(ctx, 8))

        msg.setTextColor(0xFFEFE8DA.toInt())
        msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(ctx, 21f))
        msg.typeface = Typeface.SERIF
        msg.gravity = Gravity.CENTER_HORIZONTAL
        msg.setLineSpacing(0f, 1.2f)
        msg.maxWidth = Ui.dp(ctx, 560)

        box.addView(title, LinearLayout.LayoutParams(-2, -2))
        box.addView(msg, LinearLayout.LayoutParams(-2, -2))
        box.alpha = 0f
        box.visibility = View.GONE
        parent.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun show(t: String?, m: String, ms: Long = 0L) {
        ui.removeCallbacks(autoHide)
        if (t.isNullOrEmpty()) {
            title.visibility = View.GONE
        } else {
            title.visibility = View.VISIBLE
            title.text = t.uppercase()
        }
        msg.text = m
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).setDuration(250).start()
        if (ms > 0L) ui.postDelayed(autoHide, ms)
    }

    fun hide() {
        ui.removeCallbacks(autoHide)
        fade()
    }

    private fun fade() {
        box.animate().alpha(0f).setDuration(250).withEndAction {
            if (box.alpha < 0.05f) box.visibility = View.GONE
        }.start()
    }
}
