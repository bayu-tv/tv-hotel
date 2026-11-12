package com.bayutv.hotel

import android.content.Context
import java.util.Calendar

data class ThemeSpec(val id: String, val bg: Int, val glow: Int, val accent: Int, val accentLight: Int)

object Themes {
    val DEFAULT = ThemeSpec("default", 0xFF121110.toInt(), 0x42B07A44, 0xFFB8975A.toInt(), 0xFFE6C88F.toInt())
    val RAMADAN = ThemeSpec("ramadan", 0xFF0A1B17.toInt(), 0x4A2E8B6A, 0xFFD9B25F.toInt(), 0xFFF0D58C.toInt())
    val NEWYEAR = ThemeSpec("newyear", 0xFF0A0D1C.toInt(), 0x4A4A5FBF, 0xFFD8C27A.toInt(), 0xFFF3E7B0.toInt())

    // Mengembalikan [bulan Hijriah (0-11, Ramadan = 8), tanggal]
    fun hijri(): IntArray {
        return try {
            val c = android.icu.util.IslamicCalendar()
            c.calendarType = android.icu.util.IslamicCalendar.CalendarType.ISLAMIC_UMALQURA
            intArrayOf(c.get(android.icu.util.Calendar.MONTH), c.get(android.icu.util.Calendar.DAY_OF_MONTH))
        } catch (e: Exception) {
            intArrayOf(-1, 0)
        }
    }

    fun pick(ctx: Context): ThemeSpec {
        val mode = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).getString("theme", "auto") ?: "auto"
        return when (mode) {
            "default" -> DEFAULT
            "ramadan" -> RAMADAN
            "newyear" -> NEWYEAR
            else -> auto()
        }
    }

    private fun auto(): ThemeSpec {
        if (hijri()[0] == 8) return RAMADAN
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MONTH)
        val d = c.get(Calendar.DAY_OF_MONTH)
        if ((m == Calendar.DECEMBER && d >= 25) || (m == Calendar.JANUARY && d <= 3)) return NEWYEAR
        return DEFAULT
    }
}
