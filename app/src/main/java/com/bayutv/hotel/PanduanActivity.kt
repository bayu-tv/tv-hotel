package com.bayutv.hotel

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PanduanActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val ivory = 0xFFEFE8DA.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun card(col: LinearLayout, title: String, body: String) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(24), dp(16), dp(24), dp(16))
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(0x14FFFFFF)
        g.setStroke(dp(1), 0x22FFFFFF)
        c.background = g
        c.addView(tv(title, 20f, brass, Typeface.SERIF))
        val b = tv(body, 18f, ivory, Typeface.SERIF)
        b.setLineSpacing(0f, 1.3f)
        c.addView(b)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14)
        col.addView(c, lp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("bayutv", MODE_PRIVATE)
        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF121110.toInt())
        sv.isFocusable = true
        sv.isFocusableInTouchMode = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(64), dp(40), dp(64), dp(40))
        col.addView(tv("Panduan Rumah", 34f, brass, Typeface.SERIF))
        card(col, "Wi-Fi", "Nama: " + p.getString("wifi", "Rumah-Bayu") + "\nSandi: " + p.getString("wifipass", "(diisi dari HP nanti)"))
        card(col, "Nomor Penting", "Darurat 112\nAmbulans 118\nPemadam kebakaran 113")
        card(col, "Aturan Rumah", p.getString("rules", "Bisa diisi dari HP nanti.") ?: "")
        card(col, "Catatan", p.getString("note", "Bisa diisi dari HP nanti.") ?: "")
        sv.addView(col)
        setContentView(sv)
        sv.requestFocus()
    }
}
