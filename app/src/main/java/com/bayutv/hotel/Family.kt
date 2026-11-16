package com.bayutv.hotel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Jadwal sholat hari ini (menit sejak tengah malam), dibagi antara Beranda dan TV Live. */
object Prayer {
    val NAMES = arrayOf("Subuh", "Zuhur", "Asar", "Magrib", "Isya")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)

    fun todayStr(): String = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

    fun parse(json: String): IntArray? {
        return try {
            val t = JSONObject(json).getJSONObject("data").getJSONObject("timings")
            val keys = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            IntArray(5) { i ->
                val s = t.getString(keys[i])
                s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Dari cache bila tanggalnya hari ini, selain itu null. */
    fun cached(ctx: Context): IntArray? {
        val p = prefs(ctx)
        if (p.getString("pray_date", "") != todayStr()) return null
        return parse(p.getString("pray", "") ?: "")
    }

    /** Unduh jadwal hari ini bila belum ada (dipanggil dari thread latar). */
    fun fetch(ctx: Context): IntArray? {
        cached(ctx)?.let { return it }
        val p = prefs(ctx)
        val lat = p.getString("lat", "") ?: ""
        val lon = p.getString("lon", "") ?: ""
        if (lat.isEmpty() || lon.isEmpty()) return null
        val tz = (p.getString("tz", "") ?: "").ifEmpty { TimeZone.getDefault().id }
        return try {
            val today = todayStr()
            val c = URL(
                "https://api.aladhan.com/v1/timings/" + today + "?latitude=" + lat + "&longitude=" + lon +
                    "&method=20&timezonestring=" + URLEncoder.encode(tz, "UTF-8")
            ).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            if (c.responseCode != 200) return null
            val r = c.inputStream.bufferedReader().use { it.readText() }
            val t = parse(r) ?: return null
            p.edit().putString("pray_date", today).putString("pray", r).apply()
            t
        } catch (e: Exception) {
            null
        }
    }

    fun fmt(m: Int): String = String.format(Locale.US, "%02d.%02d", m / 60, m % 60)
}

/** Kunci anak: PIN, bagian yang dikunci, dan batas waktu menonton per hari. */
object Lock {
    private var unlockedUntil = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)

    fun pin(ctx: Context): String = prefs(ctx).getString("pin_anak", "") ?: ""

    fun enabled(ctx: Context): Boolean = pin(ctx).length >= 4

    fun unlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    /** what: "intl" (Internasional), "custom" (Playlist Saya), "film" (menu Film) */
    fun isLocked(ctx: Context, what: String): Boolean =
        enabled(ctx) && prefs(ctx).getString("lock_" + what, "") == "Ya" && !unlocked()

    fun channelLocked(ctx: Context, ch: Channel): Boolean = when (ch.region) {
        "Internasional" -> isLocked(ctx, "intl")
        "Playlist Saya" -> isLocked(ctx, "custom")
        else -> false
    }

    fun limitMinutes(ctx: Context): Int = (prefs(ctx).getString("limit_min", "") ?: "").toIntOrNull() ?: 0

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Tambah waktu tonton; true bila batas hari ini terlampaui dan belum dibuka dengan PIN. */
    fun addWatch(ctx: Context, seconds: Int): Boolean {
        val p = prefs(ctx)
        val d = day()
        var sec = if (p.getString("watch_day", "") == d) p.getInt("watch_sec", 0) else 0
        sec += seconds
        p.edit().putString("watch_day", d).putInt("watch_sec", sec).apply()
        val lim = limitMinutes(ctx)
        if (!enabled(ctx) || lim <= 0) return false
        if (p.getString("limit_ok_day", "") == d) return false
        return sec >= lim * 60
    }

    fun allowRestOfDay(ctx: Context) {
        prefs(ctx).edit().putString("limit_ok_day", day()).apply()
    }

    /** Minta PIN; bila benar, kunci terbuka 30 menit lalu onOk dijalankan. */
    fun ask(a: Activity, title: String, onOk: () -> Unit, onCancel: (() -> Unit)? = null) {
        val e = EditText(a)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        e.hint = "PIN"
        AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage("Masukkan PIN kunci anak.")
            .setView(e)
            .setPositiveButton("Buka") { _, _ ->
                if (e.text.toString() == pin(a)) {
                    unlockedUntil = System.currentTimeMillis() + 30L * 60000L
                    onOk()
                } else {
                    AlertDialog.Builder(a).setMessage("PIN salah.").setPositiveButton("OK", null).show()
                    onCancel?.invoke()
                }
            }
            .setNegativeButton("Batal") { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .show()
    }
}

object Net {
    fun localIp(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in en) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun get(url: String, timeoutMs: Int = 10000): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "BayuTV")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
