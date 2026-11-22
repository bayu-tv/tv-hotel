package com.bayutv.hotel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class VoiceInput(private val activity: ComponentActivity) {

    private var activeTarget: EditText? = null
    private var activeCallback: ((String) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                if (spoken.isNotEmpty()) {
                    activeTarget?.let { et ->
                        et.setText(spoken)
                        et.setSelection(spoken.length)
                    }
                    activeCallback?.invoke(spoken)
                }
            }
        }

    fun startFor(target: EditText, prompt: String = "Ucapkan judul lagu, radio, atau kata kunci...", onResult: ((String) -> Unit)? = null) {
        activeTarget = target
        activeCallback = onResult
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Input suara tidak tersedia di perangkat ini", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Gagal membuka mikrofon suara", Toast.LENGTH_SHORT).show()
        }
    }

    fun createMicButton(
        target: EditText,
        prompt: String = "Ucapkan kata kunci pencarian...",
        accentColor: Int = 0xFFE6C88F.toInt(),
        onResult: ((String) -> Unit)? = null
    ): ImageButton {
        val dp = activity.resources.displayMetrics.density
        return ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Ketik dengan suara"
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF1F1F26.toInt())
                setStroke((1.2f * dp).toInt(), 0x33FFFFFF)
            }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    cornerRadius = 12f * dp
                    setColor(if (focused) 0xFF2D2518.toInt() else 0xFF1F1F26.toInt())
                    setStroke((if (focused) 2f else 1.2f) * dp toInt(), if (focused) Color.WHITE else 0x33FFFFFF)
                }
            }
            setOnClickListener {
                startFor(target, prompt, onResult)
            }
            val size = (44f * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = (8f * dp).toInt()
            }
        }
    }
}
