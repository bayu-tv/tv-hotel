package com.bayutv.hotel

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private var player: ExoPlayer? = null

    private var all: List<Channel> = emptyList()
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var dead: Set<String> = emptySet()
    private var shownIdx: List<Int> = emptyList()
    private val labels = ArrayList<String>()
    private var cur = 0
    private var cands: List<Channel> = emptyList()
    private var candPos = 0
    private var numBuf = ""

    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var panel: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var notice: Notice

    private val brass = 0xFFB8975A.toInt()
    private val ivory = 0xFFEFE8DA.toInt()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val jump = Runnable {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        if (n != null && n >= 1 && n <= all.size) play(n - 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (x: Exception) {
            }
            oldHandler?.uncaughtException(t, e)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val root = FrameLayout(this)
        val view = PlayerView(this)
        view.useController = false
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        banner = TextView(this)
        banner.setTextColor(ivory)
        banner.textSize = 26f
        banner.typeface = Typeface.SERIF
        banner.setPadding(dp(28), dp(14), dp(28), dp(14))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(0x99100F0E.toInt())
        bg.setStroke(dp(1), 0x33FFFFFF)
        banner.background = bg
        banner.visibility = View.GONE
        val bl = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        bl.setMargins(dp(40), 0, dp(40), dp(36))
        root.addView(banner, bl)

        status = TextView(this)
        status.setTextColor(ivory)
        status.textSize = 20f
        status.setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        status.setPadding(dp(32), dp(20), dp(32), dp(24))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xEE1A1816.toInt())
        panel.setPadding(dp(12), dp(12), dp(12), dp(12))
        panel.visibility = View.GONE

        search = EditText(this)
        search.hint = "Cari channel (tekan OK untuk mengetik)"
        search.setHintTextColor(0xFF8D8678.toInt())
        search.setTextColor(ivory)
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_DONE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            list.requestFocus()
            true
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, -2))

        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val idx = if (position < shownIdx.size) shownIdx[position] else -1
                val playing = idx == cur
                val isDead = idx >= 0 && all[idx].key in dead
                v.setTextColor(if (playing) brass else if (isDead) 0xFF6D675C.toInt() else ivory)
                v.paintFlags = if (isDead) v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else v.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                v.textSize = 20f
                return v
            }
        }
        list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(0x55B8975A)
        list.isFocusable = true
        list.isFocusableInTouchMode = true
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shownIdx.size) {
                play(shownIdx[pos])
                hidePanel()
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(dp(440), -1, Gravity.START))

        notice = Notice(this, root)
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Memuat..."
                if (state == Player.STATE_READY) {
                    status.text = ""
                    notice.hide()
                    if (all.isNotEmpty()) {
                        val ch = all[cur]
                        prefs.edit().putString("last", ch.key).remove("trying").apply()
                        if (candPos > 0 && candPos < cands.size) rememberAlive(ch.key, cands[candPos].url)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (candPos + 1 < cands.size) {
                    candPos++
                    status.text = "Mencoba sumber lain (" + (candPos + 1) + "/" + cands.size + ")..."
                    startCandidate()
                    return
                }
                prefs.edit().remove("trying").apply()
                status.text = ""
                val cause = error.cause
                val why = if (cause is HttpDataSource.InvalidResponseCodeException) {
                    when (cause.responseCode) {
                        404, 410 -> "Alamat siaran sudah tidak ada (HTTP " + cause.responseCode + ")"
                        401, 403 -> "Ditolak server, diblokir atau butuh izin (HTTP " + cause.responseCode + ")"
                        else -> "Server bermasalah (HTTP " + cause.responseCode + ")"
                    }
                } else {
                    error.errorCodeName
                }
                notice.show("Sinyal hilang", why + "\n\nAtas/bawah: ganti channel  \u00B7  OK: daftar channel\nBeranda, Pindai Sinyal: cari sumber baru")
            }
        })
        player = p

        val cf = File(filesDir, "crash.txt")
        if (cf.exists()) {
            val t = cf.readText()
            cf.delete()
            android.app.AlertDialog.Builder(this)
                .setTitle("Aplikasi sempat tertutup")
                .setMessage(t.take(700))
                .setPositiveButton("OK", null)
                .show()
        }

        notice.show("Mohon tunggu", "Memuat daftar channel...")
        Thread { loadChannels() }.start()
    }

    private fun rememberAlive(key: String, url: String) {
        val m = Playlist.loadScan(this)
        m[key] = url
        Playlist.saveScan(this, m)
    }

    private fun loadChannels() {
        val cache = Playlist.cacheFile(this)
        val fresh: String? = try {
            val c = URL(Playlist.PLAYLIST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val ok = fresh != null && fresh.length > 100
        if (ok) cache.writeText(fresh!!)
        val text: String? = if (ok) fresh else Playlist.loadCached(this)
        val parsed = if (text != null) Playlist.parse(text) else emptyList()
        ui.post { onLoaded(parsed, !ok) }
    }

    private fun onLoaded(parsed: List<Channel>, fromCache: Boolean) {
        if (parsed.isEmpty()) {
            notice.show("Daftar kosong", "Periksa koneksi internet TV, lalu buka lagi aplikasinya.")
            return
        }
        notice.hide()
        val g = Playlist.group(parsed)
        val scan = Playlist.loadScan(this)
        val picks = ArrayList<Channel>()
        val deadSet = HashSet<String>()
        for ((k, members) in g) {
            val want = scan[k]
            val pick = if (!want.isNullOrEmpty()) (members.firstOrNull { it.url == want } ?: members[0]) else members[0]
            picks.add(pick)
            if (want != null && want.isEmpty()) deadSet.add(k)
        }
        groups = g
        all = picks
        dead = deadSet
        search.hint = "Cari di " + all.size + " channel" + (if (fromCache) " (dari cache)" else "")
        applyFilter()
        val last = prefs.getString("last", null)
        val idx = all.indexOfFirst { it.key == last }
        val trying = prefs.getString("trying", null)
        prefs.edit().remove("trying").apply()
        val firstAlive = all.indexOfFirst { it.key !in dead }
        play(if (idx >= 0) idx else if (firstAlive >= 0) firstAlive else 0)
        if (trying != null) {
            val bad = all.firstOrNull { it.key == trying }
            if (bad != null) notice.show("Dilewati", "Channel \"" + bad.name + "\" tadi membuat aplikasi tertutup, jadi tidak diputar lagi.", 7000L)
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shownIdx = all.indices.filter { q.isEmpty() || all[it].name.lowercase().contains(q) }
        labels.clear()
        for (i in shownIdx) labels.add(" " + (i + 1) + ".  " + all[i].name)
        adapter.notifyDataSetChanged()
    }

    private fun play(i: Int) {
        if (all.isEmpty()) return
        cur = ((i % all.size) + all.size) % all.size
        val ch = all[cur]
        val members = groups[ch.key] ?: listOf(ch)
        cands = (listOf(ch) + members.filter { it.url != ch.url }).take(5)
        candPos = 0
        showBanner((cur + 1).toString() + "   " + ch.name, 3000L)
        status.text = "Memuat..."
        notice.hide()
        adapter.notifyDataSetChanged()
        prefs.edit().putString("trying", ch.key).commit()
        startCandidate()
    }

    private fun startCandidate() {
        val p = player ?: return
        val ch = cands[candPos]
        try {
            val dsf = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
            val ua = Playlist.clean(ch.userAgent)
            val ref = Playlist.clean(ch.referrer)
            if (ua != null) dsf.setUserAgent(ua)
            if (ref != null) dsf.setDefaultRequestProperties(mapOf("Referer" to ref))
            p.setMediaSource(DefaultMediaSourceFactory(dsf).createMediaSource(MediaItem.fromUri(ch.url)))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            prefs.edit().remove("trying").apply()
            status.text = ""
            notice.show("Gagal memutar", e.javaClass.simpleName, 6000L)
        }
    }

    private fun zap(d: Int) {
        if (all.isEmpty()) return
        var i = cur
        for (k in 0 until all.size) {
            i = ((i + d) % all.size + all.size) % all.size
            if (all[i].key !in dead) break
        }
        play(i)
    }

    private fun showBanner(t: String, ms: Long) {
        banner.text = t
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, ms)
    }

    private fun digit(d: Int) {
        numBuf += d.toString()
        showBanner(numBuf, 1400L)
        ui.removeCallbacks(jump)
        ui.postDelayed(jump, 1400L)
    }

    private fun showPanel() {
        search.setText("")
        applyFilter()
        panel.visibility = View.VISIBLE
        list.requestFocus()
        list.setSelection(if (cur < shownIdx.size) cur else 0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun hidePanel() {
        hideKeyboard()
        panel.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hidePanel()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            zap(-1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            zap(1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (all.isNotEmpty()) showPanel()
            return true
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            digit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (all.isNotEmpty()) player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
