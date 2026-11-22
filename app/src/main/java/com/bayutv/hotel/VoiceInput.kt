package com.bayutv.hotel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

class VoiceInput(private val activity: Activity) {

    private var activeTarget: EditText? = null
    private var launcher: ActivityResultLauncher<Intent>? = null

    init {
        if (activity is ComponentActivity) {
            try {
                launcher = activity.registerForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        val spoken = matches?.firstOrNull()?.trim().orEmpty()
                        if (spoken.isNotEmpty()) {
                            activeTarget?.apply {
                                setText(spoken)
                                setSelection(spoken.length)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                launcher = null
            }
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun createMicButton(
        targetEditText: EditText,
        promptHint: String = "Ucapkan pencarian...",
        accentColor: Int = 0xFFE6C88F.toInt()
    ): View {
        return TextView(activity).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            contentDescription = "Pencarian Suara"

            val size = dp(42)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dp(8)
            }
            background = micBg(focused = false, accentColor = accentColor)

            setOnFocusChangeListener { _, focused ->
                background = micBg(focused = focused, accentColor = accentColor)
            }
            setOnClickListener {
                startVoicePrompt(targetEditText, promptHint)
            }
        }
    }

    fun startVoicePrompt(targetEditText: EditText, promptHint: String) {
        activeTarget = targetEditText
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("id", "ID").toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, promptHint)
        }
        try {
            val regLauncher = launcher
            if (regLauncher != null) {
                regLauncher.launch(intent)
            } else {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, 9091)
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                "Layanan input suara tidak tersedia di perangkat ini",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Gagal membuka mikrofon pencarian suara",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun micBg(focused: Boolean, accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) 0xFF2B2316.toInt() else 0xFF16161B.toInt())
            setStroke(
                dp(if (focused) 2 else 1),
                if (focused) Color.WHITE else accentColor
            )
        }
    }
}
