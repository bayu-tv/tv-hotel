package com.bayutv.hotel

import android.content.Context

// Skala tampilan otomatis supaya pas di TV dengan tinggi layar berbeda.
object Ui {
    private fun k(c: Context): Float {
        val m = c.resources.displayMetrics
        val heightDp = m.heightPixels / m.density
        return (heightDp / 640f).coerceIn(0.7f, 1.3f)
    }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density * k(c)).toInt()

    fun sp(c: Context, v: Float): Float = v * c.resources.displayMetrics.density * k(c)
}
