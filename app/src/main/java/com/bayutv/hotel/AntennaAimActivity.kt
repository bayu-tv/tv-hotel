package com.bayutv.hotel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Arah & Sinyal Antena:
 *  - kiri: arah dan jarak ke tower pemancar (koordinat tower diisi lewat Pengaturan di HP)
 *  - kanan: meter sinyal per mux yang terus diperbarui saat antena diputar
 */
class AntennaAimActivity : AppCompatActivity() {

    private class Mux(val label: String, val input: String, val channelId: Long) {
        var state = 0 // 0 belum, 1 memeriksa, 2 jernih, 3 lemah
        var row: TextView? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()
    private val green = 0xFF8FC79A.toInt()
    private val red = 0xFFC9706A.toInt()

    private val muxes = ArrayList<Mux>()
    private var cur = -1
    private lateinit var tvView: TvView
    private lateinit var meterTitle: TextView

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    // ---------- Perhitungan arah ----------
    companion object {
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        fun dirName(b: Double): String {
            val names = arrayOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
            return names[((b / 45.0).roundToInt()) % 8]
        }
    }

    // Kompas sederhana: atas = Utara, panah emas = arah tower
    private class CompassView(ctx: Context, private val bearing: Double?) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2
            val cy = h / 2
            val r = minOf(w, h) / 2 - 30f
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = 0x55EFE8DA
            c.drawCircle(cx, cy, r, p)
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0)
                val l = if (i % 9 == 0) 22f else 10f
                c.drawLine(
                    cx + (r - l) * sin(a).toFloat(), cy - (r - l) * cos(a).toFloat(),
                    cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), p
                )
            }
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.16f
            p.color = 0xFFEFE8DA.toInt()
            c.drawText("U", cx, cy - r + r * 0.32f, p)
            p.color = 0xFFA79F90.toInt()
            c.drawText("S", cx, cy + r - r * 0.18f, p)
            c.drawText("T", cx + r - r * 0.22f, cy + r * 0.06f, p)
            c.drawText("B", cx - r + r * 0.22f, cy + r * 0.06f, p)
            val b = bearing ?: return
            val a = Math.toRadians(b)
            val tipX = cx + (r * 0.78f) * sin(a).toFloat()
            val tipY = cy - (r * 0.78f) * cos(a).toFloat()
            p.color = 0xFFE6C88F.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.06f
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx, cy, tipX, tipY, p)
            p.style = Paint.Style.FILL
            val head = Path()
            val s = r * 0.14f
            val left = a - Math.toRadians(150.0)
            val right = a + Math.toRadians(150.0)
            head.moveTo(tipX + s * 0.4f * sin(a).toFloat(), tipY - s * 0.4f * cos(a).toFloat())
            head.lineTo(tipX + s * sin(left).toFloat(), tipY - s * cos(left).toFloat())
            head.lineTo(tipX + s * sin(right).toFloat(), tipY - s * cos(right).toFloat())
            head.close()
            c.drawPath(head, p)
            c.drawCircle(cx, cy, r * 0.05f, p)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121110.toInt())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(40), dp(30), dp(40), dp(30))
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        // ---- Kiri: arah tower ----
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv("Arah Antena", 30f, brass, Typeface.SERIF))
        val home = latLon("lat", "lon")
        val tower = latLon("tower_lat", "tower_lon")
        val towerName = (prefs.getString("tower_name", "") ?: "").ifEmpty { "tower pemancar" }
        var b: Double? = null
        if (home != null && tower != null) {
            b = bearing(home.first, home.second, tower.first, tower.second)
            val d = distanceKm(home.first, home.second, tower.first, tower.second)
            left.addView(CompassView(this, b), LinearLayout.LayoutParams(dp(300), dp(300)))
            left.addView(tv(String.format(Locale.US, "%.0f°  %s", b, dirName(b)), 30f, brassLight, Typeface.SANS_SERIF))
            left.addView(tv(String.format(Locale("id"), "Jarak ke %s: %.1f km", towerName, d), 18f, ivory, Typeface.SANS_SERIF))
            val tip = when {
                d < 10 -> "Jarak dekat: antena indoor biasanya cukup. Letakkan dekat jendela yang menghadap tower."
                d < 30 -> "Jarak menengah: antena luar ruangan jauh lebih stabil. Pasang setinggi mungkin."
                else -> "Jarak jauh: butuh antena luar + penguat (booster), pasang setinggi mungkin."
            }
            val t = tv(tip, 15f, mute, Typeface.SANS_SERIF)
            t.setPadding(0, dp(8), 0, dp(8))
            left.addView(t)
            left.addView(tv("Atas = Utara. Cocokkan arahnya dengan tombol \"Buka kompas\" di halaman Pengaturan lewat HP.", 13f, mute, Typeface.SANS_SERIF))
        } else {
            val msg = if (home == null) "Lokasi rumah belum diisi." else "Lokasi tower belum diisi."
            left.addView(tv(msg, 22f, brassLight, Typeface.SANS_SERIF))
            val t = tv(
                "Cari tower di Google Maps lewat HP (ketik \"pemancar TVRI\" atau \"tower TV\"), tekan lama titik tower sampai koordinatnya muncul, salin, " +
                    "lalu buka menu Pengaturan di Beranda dan isi bagian \"Arah Antena\" dari HP. " +
                    "Lokasi rumah diisi di bagian Kota.", 16f, mute, Typeface.SANS_SERIF
            )
            t.setPadding(0, dp(10), 0, 0)
            left.addView(t)
        }
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        // ---- Kanan: meter sinyal ----
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.setPadding(dp(30), 0, 0, 0)
        right.addView(tv("Meter Sinyal", 30f, brass, Typeface.SERIF))
        meterTitle = tv("Memuat channel antena...", 15f, mute, Typeface.SANS_SERIF)
        meterTitle.setPadding(0, dp(4), 0, dp(10))
        right.addView(meterTitle)
        val rows = LinearLayout(this)
        rows.orientation = LinearLayout.VERTICAL
        right.addView(rows)
        tvView = TvView(this)
        tvView.isFocusable = false
        val tvl = LinearLayout.LayoutParams(dp(320), dp(180))
        tvl.topMargin = dp(12)
        right.addView(tvView, tvl)
        right.addView(tv("Putar antena pelan-pelan (tunggu ±10 detik tiap posisi). Arah terbaik: semua ✓.", 13f, mute, Typeface.SANS_SERIF))
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)

        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String?) {
                mark(2)
            }

            override fun onVideoUnavailable(inputId: String?, reason: Int) {
                if (reason == 2) mark(3)
            }

            override fun onConnectionFailed(inputId: String?) {
                mark(3)
            }
        })

        Thread {
            val list = loadMuxes()
            ui.post {
                muxes.addAll(list)
                if (muxes.isEmpty()) {
                    meterTitle.text = "Belum ada channel antena yang bisa dibaca. Jalankan Auto Scan (DTV) di pengaturan TV dulu."
                } else {
                    meterTitle.text = muxes.size.toString() + " frekuensi (mux) tertangkap. Diperiksa bergantian terus-menerus."
                    for (m in muxes) {
                        val r = tv("", 18f, ivory, Typeface.SANS_SERIF)
                        r.setPadding(0, dp(4), 0, dp(4))
                        m.row = r
                        rows.addView(r)
                        render(m)
                    }
                    next()
                }
            }
        }.start()
    }

    private fun latLon(a: String, b: String): Pair<Double, Double>? {
        val x = (prefs.getString(a, "") ?: "").toDoubleOrNull() ?: return null
        val y = (prefs.getString(b, "") ?: "").toDoubleOrNull() ?: return null
        return Pair(x, y)
    }

    // Satu mux = satu frekuensi; channel di mux yang sama dikelompokkan lewat transport stream id
    private fun loadMuxes(): List<Mux> {
        val chans = Antenna.load(this).channels
        if (chans.isEmpty()) return emptyList()
        val tsOf = HashMap<Long, String>()
        try {
            for (input in chans.mapNotNull { it.tunerInput }.distinct()) {
                val c = contentResolver.query(
                    TvContract.buildChannelsUriForInput(input),
                    arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID),
                    null, null, null
                ) ?: continue
                c.use {
                    while (it.moveToNext()) tsOf[it.getLong(0)] = it.getInt(2).toString() + ":" + it.getInt(1)
                }
            }
        } catch (e: Exception) {
        }
        val groups = LinkedHashMap<String, ArrayList<Channel>>()
        for (ch in chans) {
            val ts = tsOf[ch.tunerId]
            val k = if (ts == null || ts == "0:0") "ch" + ch.tunerId else ts
            groups.getOrPut(k) { ArrayList() }.add(ch)
        }
        return groups.values.map { g ->
            val names = g.joinToString(", ") { Playlist.display(it.name) }
            Mux(if (names.length > 60) names.take(57) + "..." else names, g[0].tunerInput ?: "", g[0].tunerId)
        }
    }

    private fun render(m: Mux) {
        val r = m.row ?: return
        when (m.state) {
            2 -> {
                r.text = "✓  " + m.label
                r.setTextColor(green)
            }
            3 -> {
                r.text = "✕  " + m.label + "  (lemah)"
                r.setTextColor(red)
            }
            1 -> {
                r.text = "…  " + m.label
                r.setTextColor(brassLight)
            }
            else -> {
                r.text = "·  " + m.label
                r.setTextColor(mute)
            }
        }
    }

    private val timeout = Runnable { mark(3) }

    private fun mark(state: Int) {
        val m = muxes.getOrNull(cur) ?: return
        if (m.state != 1) return
        ui.removeCallbacks(timeout)
        m.state = state
        render(m)
        // tampilkan hasil sebentar, lalu lanjut ke mux berikutnya
        ui.postDelayed({ next() }, if (muxes.size == 1) 3000L else 1500L)
    }

    private fun next() {
        if (isFinishing || muxes.isEmpty()) return
        cur = (cur + 1) % muxes.size
        val m = muxes[cur]
        m.state = 1
        render(m)
        try {
            tvView.tune(m.input, TvContract.buildChannelUri(m.channelId))
            ui.postDelayed(timeout, 10000L)
        } catch (e: Exception) {
            m.state = 3
            render(m)
            ui.postDelayed({ next() }, 1500L)
        }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        try {
            tvView.reset()
        } catch (e: Exception) {
        }
    }

    override fun onRestart() {
        super.onRestart()
        for (m in muxes) {
            m.state = 0
            render(m)
        }
        cur = -1
        next()
    }
}
