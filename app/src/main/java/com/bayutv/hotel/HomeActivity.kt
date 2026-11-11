package com.bayutv.hotel

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    // Lokasi bawaan: Pontianak. Nanti bisa diubah dari HP.
    private val lat = -0.0263
    private val lon = 109.3425
    private val tzName = "Asia/Pontianak"
    private val repoApi = "https://api.github.com/repos/bayu-tv/tv-hotel/releases/latest"

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val city: String get() = prefs.getString("city", "Pontianak") ?: "Pontianak"

    private lateinit var tvGreet: TextView
    private lateinit var tvName: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvHijri: TextView
    private lateinit var wxIcon: ImageView
    private lateinit var tvWx1: TextView
    private lateinit var tvWx2: TextView
    private lateinit var fc: LinearLayout
    private lateinit var tvCd: TextView
    private lateinit var tvNote: TextView
    private lateinit var tiles: LinearLayout
    private val cells = ArrayList<Pair<TextView, TextView>>()
    private val times = IntArray(5) { -1 }
    private val prayNames = arrayOf("Subuh", "Zuhur", "Asar", "Magrib", "Isya")
    private var prayerDay = ""
    private var prayerTryAt = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun icon(res: Int, size: Int, color: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return v
    }

    private fun box(focused: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(if (focused) 0x30B8975A else 0x14FFFFFF)
        g.setStroke(dp(if (focused) 2 else 1), if (focused) brass else 0x22FFFFFF)
        return g
    }

    private fun tile(label: String, res: Int, action: () -> Unit): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        val ic = icon(res, 48, mute)
        t.addView(ic)
        val tx = tv(label, 20f, mute, Typeface.SERIF)
        tx.setPadding(0, dp(12), 0, 0)
        t.addView(tx)
        t.isFocusable = true
        t.isClickable = true
        t.background = box(false)
        t.setOnClickListener { action() }
        t.setOnFocusChangeListener { _, f ->
            t.background = box(f)
            val c = if (f) brassLight else mute
            ic.setColorFilter(c, PorterDuff.Mode.SRC_IN)
            tx.setTextColor(c)
            val s = if (f) 1.07f else 1f
            t.animate().scaleX(s).scaleY(s).translationY(if (f) -dp(8).toFloat() else 0f).setDuration(220).start()
            if (f) ObjectAnimator.ofFloat(ic, "rotation", 0f, -8f, 8f, -4f, 0f).setDuration(700).start()
        }
        val lp = LinearLayout.LayoutParams(dp(190), dp(150))
        lp.setMargins(dp(10), 0, dp(10), 0)
        t.layoutParams = lp
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121110.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(56), dp(32), dp(56), dp(20))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Baris atas: sapaan dan jam
        val top = LinearLayout(this)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val welcome = tv("SELAMAT DATANG", 13f, brass, Typeface.SANS_SERIF)
        welcome.letterSpacing = 0.25f
        tvGreet = tv("", 36f, ivory, Typeface.SERIF)
        tvName = tv(prefs.getString("family", "Keluarga Anda") ?: "Keluarga Anda", 36f, brass, Typeface.create(Typeface.SERIF, Typeface.ITALIC))
        left.addView(welcome)
        left.addView(tvGreet)
        left.addView(tvName)
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        tvClock = tv("", 54f, ivory, Typeface.create("sans-serif-light", Typeface.NORMAL))
        tvDate = tv("", 16f, mute, Typeface.SANS_SERIF)
        tvHijri = tv("", 16f, mute, Typeface.SANS_SERIF)
        right.addView(tvClock)
        right.addView(tvDate)
        right.addView(tvHijri)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(right, LinearLayout.LayoutParams(-2, -2))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        val line = View(this)
        line.setBackgroundColor(0xFF34302A.toInt())
        val llp = LinearLayout.LayoutParams(-1, dp(1))
        llp.topMargin = dp(14)
        col.addView(line, llp)

        // Cuaca (tanpa kotak)
        val wx = LinearLayout(this)
        wx.gravity = Gravity.CENTER_VERTICAL
        wxIcon = icon(R.drawable.ic_cloud_sun, 56, brassLight)
        wx.addView(wxIcon)
        val wt = LinearLayout(this)
        wt.orientation = LinearLayout.VERTICAL
        wt.setPadding(dp(16), 0, 0, 0)
        tvWx1 = tv("Memuat cuaca...", 18f, ivory, Typeface.SANS_SERIF)
        val row2 = LinearLayout(this)
        row2.gravity = Gravity.CENTER_VERTICAL
        row2.addView(icon(R.drawable.ic_wind, 18, mute))
        tvWx2 = tv("", 15f, mute, Typeface.SANS_SERIF)
        tvWx2.setPadding(dp(8), 0, 0, 0)
        row2.addView(tvWx2)
        wt.addView(tvWx1)
        wt.addView(row2)
        wx.addView(wt, LinearLayout.LayoutParams(0, -2, 1f))
        fc = LinearLayout(this)
        wx.addView(fc)
        val wlp = LinearLayout.LayoutParams(-1, -2)
        wlp.topMargin = dp(14)
        col.addView(wx, wlp)

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // Menu
        tiles = LinearLayout(this)
        tiles.gravity = Gravity.CENTER
        tiles.addView(tile("TV Live", R.drawable.ic_tv) { startActivity(Intent(this, MainActivity::class.java)) })
        tiles.addView(tile("Panduan", R.drawable.ic_book) { startActivity(Intent(this, PanduanActivity::class.java)) })
        tiles.addView(tile("Pindai Sinyal", R.drawable.ic_scan) { Toast.makeText(this, "Segera hadir", Toast.LENGTH_SHORT).show() })
        tiles.addView(tile("Pengaturan", R.drawable.ic_phone) { Toast.makeText(this, "Segera hadir", Toast.LENGTH_SHORT).show() })
        col.addView(tiles, LinearLayout.LayoutParams(-1, -2))

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // Jadwal sholat
        val pray = LinearLayout(this)
        pray.background = box(false)
        pray.setPadding(dp(8), dp(8), dp(8), dp(8))
        for (i in 0..4) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            val n = tv(prayNames[i], 14f, mute, Typeface.SANS_SERIF)
            val t = tv("--:--", 22f, ivory, Typeface.SERIF)
            c.addView(n)
            c.addView(t)
            cells.add(Pair(n, t))
            pray.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(pray, LinearLayout.LayoutParams(-1, -2))
        tvCd = tv("", 15f, mute, Typeface.SANS_SERIF)
        tvCd.setPadding(dp(8), dp(8), 0, 0)
        col.addView(tvCd)
        tvNote = tv("Cuaca: Open-Meteo. Kualitas udara: CAMS. Jadwal sholat: Aladhan (Kemenag RI).", 11f, 0xFF6F685B.toInt(), Typeface.SANS_SERIF)
        tvNote.setPadding(dp(8), dp(4), 0, 0)
        col.addView(tvNote)

        setContentView(root)
        tiles.getChildAt(0).requestFocus()

        renderWeather()
        loadWeather()
        loadPrayer()
        ui.post(tick)
        Thread { checkUpdate() }.start()
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            ui.postDelayed(this, 15000L)
        }
    }

    private fun hijri(d: Date): String {
        return try {
            val f = android.icu.text.SimpleDateFormat("d MMMM y", android.icu.util.ULocale("id_ID@calendar=islamic-umalqura"))
            f.format(d) + " H"
        } catch (e: Exception) {
            ""
        }
    }

    private fun fmt(m: Int): String = if (m < 0) "--:--" else String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    private fun refreshClock() {
        val now = Date()
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        tvClock.text = String.format(Locale.US, "%02d:%02d", h, m)
        tvGreet.text = if (h < 4) "Selamat Malam," else if (h < 11) "Selamat Pagi," else if (h < 15) "Selamat Siang," else if (h < 18) "Selamat Sore," else "Selamat Malam,"
        tvDate.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(now)
        tvHijri.text = hijri(now)
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(now)
        if (today != prayerDay && System.currentTimeMillis() - prayerTryAt > 60000L) loadPrayer()
        updatePrayerUI(h * 60 + m)
    }

    private fun updatePrayerUI(nowMin: Int) {
        if (times[0] < 0) {
            tvCd.text = "Jadwal sholat sedang dimuat..."
            return
        }
        var nx = times.indexOfFirst { it > nowMin }
        val diff = if (nx < 0) {
            nx = 0
            times[0] + 1440 - nowMin
        } else {
            times[nx] - nowMin
        }
        for (i in 0..4) {
            val hi = i == nx
            cells[i].first.setTextColor(if (hi) brass else mute)
            cells[i].second.setTextColor(if (hi) brassLight else ivory)
            cells[i].second.text = fmt(times[i])
        }
        tvCd.text = prayNames[nx] + " dalam " + (diff / 60) + " jam " + (diff % 60) + " menit"
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "BayuTV")
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPrayer() {
        prayerTryAt = System.currentTimeMillis()
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        if (prefs.getString("pray_date", "") == today) {
            val cached = prefs.getString("pray", null)
            if (cached != null && applyPrayer(cached, today)) return
        }
        Thread {
            val r = httpGet("https://api.aladhan.com/v1/timings/" + today + "?latitude=" + lat + "&longitude=" + lon + "&method=20&timezonestring=" + tzName)
            if (r != null) {
                ui.post {
                    if (applyPrayer(r, today)) prefs.edit().putString("pray_date", today).putString("pray", r).apply()
                }
            }
        }.start()
    }

    private fun applyPrayer(json: String, day: String): Boolean {
        return try {
            val t = JSONObject(json).getJSONObject("data").getJSONObject("timings")
            val keys = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            val tmp = IntArray(5)
            for (i in 0..4) {
                val s = t.getString(keys[i])
                tmp[i] = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
            }
            for (i in 0..4) times[i] = tmp[i]
            prayerDay = day
            refreshClock()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadWeather() {
        Thread {
            val w = httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m" +
                "&daily=weather_code,temperature_2m_max&timezone=Asia%2FPontianak&forecast_days=4")
            val a = httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon +
                "&current=us_aqi&timezone=Asia%2FPontianak")
            if (w != null) prefs.edit().putString("wx", w).apply()
            if (a != null) prefs.edit().putString("aq", a).apply()
            ui.post { renderWeather() }
        }.start()
        ui.postDelayed({ loadWeather() }, 30 * 60 * 1000L)
    }

    private fun cond(c: Int): Pair<String, Int> {
        return when (c) {
            0 -> Pair("Cerah", R.drawable.ic_sun)
            1, 2 -> Pair("Berawan", R.drawable.ic_cloud_sun)
            3 -> Pair("Mendung", R.drawable.ic_cloud)
            45, 48 -> Pair("Berkabut", R.drawable.ic_cloud)
            51, 53, 55, 56, 57 -> Pair("Gerimis", R.drawable.ic_rain)
            61, 63, 65, 66, 67, 80, 81, 82 -> Pair("Hujan", R.drawable.ic_rain)
            95, 96, 99 -> Pair("Hujan petir", R.drawable.ic_storm)
            else -> Pair("Berawan", R.drawable.ic_cloud_sun)
        }
    }

    private fun dirName(deg: Double): String {
        val n = arrayOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
        return n[(((deg + 22.5) / 45.0).toInt()) % 8]
    }

    private fun renderWeather() {
        try {
            val w = prefs.getString("wx", null) ?: return
            val o = JSONObject(w)
            val cur = o.getJSONObject("current")
            val temp = Math.round(cur.getDouble("temperature_2m")).toInt()
            val c = cond(cur.getInt("weather_code"))
            val ws = Math.round(cur.getDouble("wind_speed_10m")).toInt()
            val wd = cur.getDouble("wind_direction_10m")
            wxIcon.setImageResource(c.second)
            val head = temp.toString() + "\u00B0"
            val sp = SpannableString(head + "  " + c.first + " \u00B7 " + city)
            sp.setSpan(RelativeSizeSpan(2.1f), 0, head.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvWx1.text = sp

            val base = "Angin " + ws + " km/j " + dirName(wd)
            var aqText = ""
            var aqColor = mute
            val a = prefs.getString("aq", null)
            if (a != null) {
                val aq = JSONObject(a).getJSONObject("current").getInt("us_aqi")
                val lbl = if (aq <= 50) "Baik" else if (aq <= 100) "Sedang" else if (aq <= 150) "Kurang sehat" else "Tidak sehat"
                aqColor = if (aq <= 50) 0xFF7FB08A.toInt() else if (aq <= 100) brass else 0xFFC9706A.toInt()
                aqText = aq.toString() + " " + lbl
            }
            if (aqText.isEmpty()) {
                tvWx2.text = base
            } else {
                val pre = base + "  \u00B7  Kualitas udara "
                val s2 = SpannableString(pre + aqText)
                s2.setSpan(ForegroundColorSpan(aqColor), pre.length, pre.length + aqText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                tvWx2.text = s2
            }

            fc.removeAllViews()
            val d = o.getJSONObject("daily")
            val codes = d.getJSONArray("weather_code")
            val maxs = d.getJSONArray("temperature_2m_max")
            for (i in 1..3) {
                if (i >= codes.length()) break
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, i)
                val item = LinearLayout(this)
                item.orientation = LinearLayout.VERTICAL
                item.gravity = Gravity.CENTER
                item.setPadding(dp(12), 0, dp(12), 0)
                item.addView(tv(SimpleDateFormat("EEE", Locale("id", "ID")).format(cal.time), 13f, mute, Typeface.SANS_SERIF))
                item.addView(icon(cond(codes.getInt(i)).second, 28, 0xFFCFC7B8.toInt()))
                item.addView(tv(Math.round(maxs.getDouble(i)).toInt().toString() + "\u00B0", 14f, ivory, Typeface.SANS_SERIF))
                fc.addView(item)
            }
        } catch (e: Exception) {
            tvWx1.text = "Cuaca belum tersedia"
        }
    }

    // ---- Pembaruan otomatis ----
    private fun checkUpdate() {
        try {
            val c = URL(repoApi).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "BayuTV")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name").removePrefix("v").toIntOrNull() ?: return
            val now = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
            if (tag <= now) return
            val assets = o.getJSONArray("assets")
            var found: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) found = a.getString("browser_download_url")
            }
            val url = found ?: return
            ui.post { askUpdate(tag, url) }
        } catch (e: Exception) {
        }
    }

    private fun askUpdate(tag: Int, url: String) {
        AlertDialog.Builder(this)
            .setTitle("Ada versi baru (v" + tag + ")")
            .setMessage("Tekan Perbarui untuk mengunduh dan memasang. Aplikasi akan tertutup sebentar saat pemasangan.")
            .setPositiveButton("Perbarui") { _, _ -> startUpdate(url) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            tvNote.text = "Izinkan 'Pasang aplikasi tak dikenal' untuk Bayu TV, lalu buka lagi dan tekan Perbarui."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        tvNote.text = "Mengunduh pembaruan..."
        Thread {
            try {
                val f = File(cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                ui.post { installApk(f) }
            } catch (e: Exception) {
                ui.post { tvNote.text = "Gagal mengunduh pembaruan: " + e.javaClass.simpleName }
            }
        }.start()
    }

    private fun installApk(f: File) {
        tvNote.text = ""
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
