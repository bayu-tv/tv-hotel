package com.bayutv.hotel

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()
    private val green = 0xFF7FB08A.toInt()
    private val red = 0xFFC9706A.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var scan: MutableMap<String, String> = HashMap()
    private var scope = 1
    private var running = false
    private var pool: ExecutorService? = null
    private val lines = ArrayList<Pair<String, Int>>()
    private var okN = 0
    private var fixN = 0
    private var badN = 0
    private var doneN = 0
    private var total = 0

    private lateinit var title: TextView
    private lateinit var opts: LinearLayout
    private lateinit var bar: ProgressBar
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var btn: TextView
    private lateinit var notice: Notice
    private val rings = ArrayList<View>()
    private val anims = ArrayList<ValueAnimator>()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121110.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(56), dp(28), dp(56), dp(24))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Lingkaran sinyal
        val rf = FrameLayout(this)
        val size = dp(130)
        for (i in 0..2) {
            val r = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setStroke(dp(1), brass)
            g.setColor(0x00000000)
            r.background = g
            r.alpha = 0f
            rf.addView(r, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            rings.add(r)
        }
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_scan)
        ic.setColorFilter(brass, PorterDuff.Mode.SRC_IN)
        rf.addView(ic, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        col.addView(rf, LinearLayout.LayoutParams(size, size))

        title = tv("Pindai Sinyal", 28f, ivory, Typeface.SERIF)
        title.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(title, LinearLayout.LayoutParams(-2, -2))

        opts = LinearLayout(this)
        opts.gravity = Gravity.CENTER
        val olp = LinearLayout.LayoutParams(-2, -2)
        olp.topMargin = dp(14)
        col.addView(opts, olp)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.progressTintList = ColorStateList.valueOf(brass)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0xFF34302A.toInt())
        val blp = LinearLayout.LayoutParams(dp(520), dp(6))
        blp.topMargin = dp(16)
        col.addView(bar, blp)

        stats = tv("", 17f, mute, Typeface.SANS_SERIF)
        stats.gravity = Gravity.CENTER_HORIZONTAL
        val slp = LinearLayout.LayoutParams(-2, -2)
        slp.topMargin = dp(12)
        col.addView(stats, slp)

        logView = tv("", 15f, mute, Typeface.SANS_SERIF)
        logView.gravity = Gravity.BOTTOM
        logView.setLineSpacing(0f, 1.15f)
        val llp = LinearLayout.LayoutParams(dp(520), 0, 1f)
        llp.topMargin = dp(10)
        col.addView(logView, llp)

        btn = tv("", 17f, brass, Typeface.SANS_SERIF)
        btn.setPadding(dp(28), dp(10), dp(28), dp(10))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(30).toFloat()
        bg.setStroke(dp(1), brass)
        btn.background = bg
        col.addView(btn, LinearLayout.LayoutParams(-2, -2))

        notice = Notice(this, root)
        setContentView(root)

        for (i in 0..2) {
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 2400L
            a.repeatCount = ValueAnimator.INFINITE
            a.startDelay = i * 800L
            val ring = rings[i]
            a.addUpdateListener {
                val f = it.animatedValue as Float
                ring.scaleX = 0.35f + 1.1f * f
                ring.scaleY = 0.35f + 1.1f * f
                ring.alpha = 0.9f * (1f - f)
            }
            anims.add(a)
        }

        val text = Playlist.loadCached(this)
        if (text != null) {
            groups = Playlist.group(Playlist.parse(text))
            scan = Playlist.loadScan(this)
            scope = if (deadCount() > 0) 1 else 2
        } else {
            notice.show("Belum ada daftar channel", "Buka TV Live dulu satu kali agar daftar channel diunduh, lalu kembali ke sini.")
        }
        renderOptions()
        renderStats()
        btn.text = "Tekan OK untuk mulai pindai"
    }

    private fun deadCount(): Int = groups.keys.count { scan[it] == "" }

    private fun keysFor(s: Int): List<String> {
        if (s == 0) {
            val last = prefs.getString("last", null)
            return if (last != null && groups.containsKey(last)) listOf(last) else emptyList()
        }
        if (s == 1) return groups.keys.filter { scan[it] == "" }
        return groups.keys.toList()
    }

    private fun renderOptions() {
        val last = prefs.getString("last", null)
        val lastName = if (last != null) groups[last]?.firstOrNull()?.name else null
        val labels = arrayOf(
            "Channel terakhir" + (if (lastName != null) " (" + lastName + ")" else ""),
            "Yang hilang (" + deadCount() + ")",
            "Semua (" + groups.size + ")"
        )
        opts.removeAllViews()
        for (i in 0..2) {
            val t = tv(labels[i], 15f, if (i == scope) brass else mute, Typeface.SANS_SERIF)
            t.setPadding(dp(16), dp(8), dp(16), dp(8))
            val g = GradientDrawable()
            g.cornerRadius = dp(30).toFloat()
            g.setStroke(dp(1), if (i == scope) brass else 0xFF34302A.toInt())
            t.background = g
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(6), 0, dp(6), 0)
            opts.addView(t, lp)
        }
    }

    private fun renderStats() {
        stats.text = "Aktif " + okN + "   \u00B7   Pulih " + fixN + "   \u00B7   Mati " + badN
        bar.max = if (total > 0) total else 1
        bar.progress = doneN
    }

    private fun addLog(t: String, color: Int) {
        lines.add(Pair(t, color))
        val sb = SpannableStringBuilder()
        val last = lines.takeLast(7)
        for ((i, p) in last.withIndex()) {
            val start = sb.length
            sb.append(p.first)
            if (i < last.size - 1) sb.append("\n")
            sb.setSpan(ForegroundColorSpan(p.second), start, start + p.first.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = sb
    }

    private fun startScan() {
        if (groups.isEmpty()) return
        val keys = keysFor(scope)
        if (keys.isEmpty()) {
            notice.show("Tidak ada yang perlu dipindai", "Belum ada channel yang ditandai hilang. Pilih Semua untuk memeriksa seluruh channel.", 4000L)
            return
        }
        running = true
        okN = 0
        fixN = 0
        badN = 0
        doneN = 0
        total = keys.size
        lines.clear()
        logView.text = ""
        title.text = "Mencari sinyal..."
        btn.text = "OK untuk berhenti"
        for (i in 0..2) anims[i].start()
        renderStats()
        val p = Executors.newFixedThreadPool(3)
        pool = p
        for (k in keys) p.execute { work(k) }
    }

    private fun work(k: String) {
        if (!running) return
        val list = groups[k] ?: return
        val pref = synchronized(scan) { scan[k] }
        val ordered = ArrayList<Channel>()
        if (!pref.isNullOrEmpty()) list.firstOrNull { it.url == pref }?.let { ordered.add(it) }
        for (c in list) if (ordered.none { it.url == c.url }) ordered.add(c)
        var found: String? = null
        var first = false
        var i = 0
        for (c in ordered.take(4)) {
            if (!running) return
            if (Playlist.check(c)) {
                found = c.url
                first = (i == 0)
                break
            }
            i++
        }
        val name = list[0].name
        val f = found
        val fi = first
        ui.post { onResult(k, name, f, fi) }
    }

    private fun onResult(k: String, name: String, found: String?, first: Boolean) {
        if (!running) return
        doneN++
        val wasDead = scan[k] == ""
        synchronized(scan) { scan[k] = found ?: "" }
        if (found == null) {
            badN++
            addLog("\u2715 " + name + ": tidak ada sumber aktif", red)
        } else if (wasDead) {
            fixN++
            addLog("\u21BB " + name + ": pulih", brass)
        } else if (first) {
            okN++
            addLog("\u2713 " + name + " aktif", green)
        } else {
            fixN++
            addLog("\u21BB " + name + ": pindah ke sumber lain", brass)
        }
        renderStats()
        if (doneN >= total) complete()
    }

    private fun stopAnims() {
        for (a in anims) a.cancel()
        for (r in rings) r.alpha = 0f
    }

    private fun complete() {
        running = false
        pool?.shutdown()
        stopAnims()
        Playlist.saveScan(this, scan)
        title.text = "Pindai selesai"
        btn.text = "OK untuk pindai lagi"
        addLog("Selesai: " + (okN + fixN) + " siap ditonton, " + badN + " tidak ditemukan.", ivory)
        renderOptions()
    }

    private fun stopScan() {
        running = false
        pool?.shutdownNow()
        stopAnims()
        synchronized(scan) { Playlist.saveScan(this, scan) }
        title.text = "Dihentikan"
        btn.text = "OK untuk melanjutkan pindai"
        renderOptions()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (running) stopScan() else startScan()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!running) {
                scope = (scope + 2) % 3
                renderOptions()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!running) {
                scope = (scope + 1) % 3
                renderOptions()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (running) stopScan()
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        running = false
        pool?.shutdownNow()
        stopAnims()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
