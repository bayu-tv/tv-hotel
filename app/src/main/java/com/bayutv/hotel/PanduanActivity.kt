package com.bayutv.hotel

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PanduanActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val bgDark = 0xFF121110.toInt()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface = Typeface.SERIF): TextView {
        return TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@PanduanActivity, sp))
            setTextColor(color)
            typeface = face
        }
    }

    private fun card(col: LinearLayout, title: String, body: String, isPhone: Boolean) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPhone) 16 else 24), dp(16), dp(if (isPhone) 16 else 24), dp(16))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x14FFFFFF)
                setStroke(dp(1), 0x22FFFFFF)
            }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (focused) 0x28B8975A else 0x14FFFFFF)
                    setStroke(dp(if (focused) 2 else 1), if (focused) brassLight else 0x22FFFFFF)
                }
            }
        }
        c.addView(tv(title, if (isPhone) 18f else 20f, brassLight, Typeface.create(Typeface.SERIF, Typeface.BOLD)))
        val b = tv(body, if (isPhone) 15.5f else 18f, ivory, Typeface.SERIF).apply {
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(6), 0, 0)
        }
        c.addView(b)

        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        }
        col.addView(c, lp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pBox = getSharedPreferences("bbox", MODE_PRIVATE)
        val pLegacy = getSharedPreferences("bayutv", MODE_PRIVATE)
        fun readPref(key: String, fallback: String): String {
            return pBox.getString(key, null)
                ?: pLegacy.getString(key, null)
                ?: fallback
        }

        val isPhonePortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600
        val padH = dp(if (isPhonePortrait) 18 else 56)
        val padV = dp(if (isPhonePortrait) 20 else 36)

        val sv = ScrollView(this).apply {
            setBackgroundColor(bgDark)
            isVerticalScrollBarEnabled = false
            isFocusable = false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val titleView = tv(
            "Panduan B-Box",
            if (isPhonePortrait) 24f else 32f,
            brassLight,
            Typeface.create(Typeface.SERIF, Typeface.BOLD)
        )
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))

        val btnBack = tv("← Kembali", 13f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x26FFFFFF)
                setStroke(dp(1), 0x44B8975A)
            }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (focused) Color.WHITE else 0x26FFFFFF)
                    if (focused) setStroke(dp(2), brassLight)
                }
                setTextColor(if (focused) bgDark else ivory)
            }
            setOnClickListener { finish() }
        }
        headerRow.addView(btnBack)
        col.addView(headerRow)

        val wifiName = readPref("wifi", "B-Box-WiFi")
        val wifiPass = readPref("wifipass", "(diisi dari menu pengaturan nanti)")
        val rulesText = readPref("rules", "Bisa diisi dari menu pengaturan nanti.")
        val noteText = readPref("note", "Bisa diisi dari menu pengaturan nanti.")

        card(col, "Wi-Fi", "Nama: $wifiName\nSandi: $wifiPass", isPhonePortrait)
        card(col, "Nomor Penting", "Darurat 112\nAmbulans 118\nPemadam kebakaran 113", isPhonePortrait)
        card(col, "Aturan & Petunjuk", rulesText, isPhonePortrait)
        card(col, "Catatan", noteText, isPhonePortrait)

        sv.addView(col)
        setContentView(sv)
        btnBack.requestFocus()
    }
}
