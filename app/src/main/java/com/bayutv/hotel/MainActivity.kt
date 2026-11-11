package com.bayutv.hotel

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
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

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val referrer: String?,
    val userAgent: String?
)

class MainActivity : AppCompatActivity() {

    // Playlist Indonesia dari iptv-org. Kode negara lain: us, my, sg, jp, dst.
    private val playlistUrl = "https://iptv-org.github.io/iptv/countries/id.m3u"

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var all: List<Channel> = emptyList()
    private var shownIdx: List<Int> = emptyList()
    private val labels = ArrayList<String>()
    private var cur = 0
    private var numBuf = ""
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var panel: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private val brass = 0xFFB8975A.toInt()
    private val ivory = 0xFFEFE8DA.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        banner.textSize = 24f
        banner.setBackgroundColor(0xB0000000.toInt())
        banner.setPadding(dp(20), dp(10), dp(20), dp(10))
        banner.visibility = View.GONE
        val bl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        bl.setMargins(dp(32), dp(24), dp(32), 0)
        root.addView(banner, bl)

        status = TextView(this)
        status.setTextColor(ivory)
        status.textSize = 22f
        status.setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        status.setPadding(dp(32), dp(20), dp(32), dp(24))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

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
                val playing = position < shownIdx.size && shownIdx[position] == cur
                v.setTextColor(if (playing) brass else ivory)
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

        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Memuat..."
                if (state == Player.STATE_READY) {
                    status.text = ""
                    if (all.isNotEmpty()) prefs.edit().putString("last", all[cur].url).remove("trying").apply()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                prefs.edit().remove("trying").apply()
                val cause = error.cause
                val why = if (cause is HttpDataSource.InvalidResponseCodeException) {
                    when (cause.responseCode) {
                        404, 410 -> "alamat siaran sudah tidak ada (HTTP " + cause.responseCode + ")"
                        401, 403 -> "ditolak server, diblokir atau butuh izin (HTTP " + cause.responseCode + ")"
                        else -> "server bermasalah (HTTP " + cause.responseCode + ")"
                    }
                } else {
                    error.errorCodeName
                }
                status.text = "Sinyal hilang: " + why + ". Atas/bawah: ganti channel. OK: daftar."
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

        status.text = "Memuat daftar channel..."
        Thread { loadChannels() }.start()
    }

    private fun loadChannels() {
        val cache = File(filesDir, "channels.m3u")
        val fresh: String? = try {
            val c = URL(playlistUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (fresh != null && fresh.length > 100) cache.writeText(fresh)
        val text: String? = if (fresh != null && fresh.length > 100) fresh else if (cache.exists()) cache.readText() else null
        val fromCache = !(fresh != null && fresh.length > 100)
        val parsed = if (text != null) parse(text) else emptyList()
        ui.post { onLoaded(parsed, fromCache) }
    }

    private fun attr(line: String, key: String): String? {
        val m = Regex(key + "=\"([^\"]*)\"").find(line)
        val v = m?.groupValues?.get(1)
        return if (v.isNullOrEmpty()) null else v
    }

    private fun clean(v: String): String = v.filter { it.code in 32..126 }.trim()

    private fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var ref: String? = null
        var ua: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
                ref = null
                ua = null
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                ref = line.substringAfter("=")
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                ua = line.substringAfter("=")
            } else if (!line.startsWith("#") && name != null) {
                out.add(Channel(name, line, logo, group, ref, ua))
                name = null
            }
        }
        return out
    }

    private fun onLoaded(parsed: List<Channel>, fromCache: Boolean) {
        if (parsed.isEmpty()) {
            status.text = "Daftar channel kosong. Periksa internet TV."
            return
        }
        all = parsed
        search.hint = "Cari di " + all.size + " channel" + (if (fromCache) " (dari cache)" else "")
        applyFilter()
        val last = prefs.getString("last", null)
        val idx = all.indexOfFirst { it.url == last }
        val trying = prefs.getString("trying", null)
        prefs.edit().remove("trying").apply()
        play(if (idx >= 0) idx else 0)
        if (trying != null) {
            val bad = all.firstOrNull { it.url == trying }
            if (bad != null) showBanner("Channel \"" + bad.name + "\" tadi membuat aplikasi tertutup. Dilewati.", 9000L)
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
        val p = player ?: return
        cur = ((i % all.size) + all.size) % all.size
        val ch = all[cur]
        showBanner((cur + 1).toString() + "  " + ch.name, 3000L)
        status.text = "Memuat..."
        adapter.notifyDataSetChanged()
        prefs.edit().putString("trying", ch.url).commit()
        try {
            val dsf = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
            val ua = ch.userAgent?.let { clean(it) }
            val ref = ch.referrer?.let { clean(it) }
            if (!ua.isNullOrEmpty()) dsf.setUserAgent(ua)
            if (!ref.isNullOrEmpty()) dsf.setDefaultRequestProperties(mapOf("Referer" to ref))
            p.setMediaSource(DefaultMediaSourceFactory(dsf).createMediaSource(MediaItem.fromUri(ch.url)))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            prefs.edit().remove("trying").apply()
            status.text = "Gagal memutar: " + e.javaClass.simpleName
        }
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

    private val repoApi = "https://api.github.com/repos/bayu-tv/tv-hotel/releases/latest"

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
        android.app.AlertDialog.Builder(this)
            .setTitle("Ada versi baru (v" + tag + ")")
            .setMessage("Tekan Perbarui untuk mengunduh dan memasang. Aplikasi akan tertutup sebentar saat pemasangan.")
            .setPositiveButton("Perbarui") { _, _ -> startUpdate(url) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            status.text = "Izinkan 'Pasang aplikasi tak dikenal' untuk Bayu TV, lalu buka lagi dan tekan Perbarui."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        status.text = "Mengunduh pembaruan..."
        Thread {
            try {
                val f = File(cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                ui.post { installApk(f) }
            } catch (e: Exception) {
                ui.post { status.text = "Gagal mengunduh pembaruan: " + e.javaClass.simpleName }
            }
        }.start()
    }

    private fun installApk(f: File) {
        status.text = ""
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
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
            play(cur - 1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            play(cur + 1)
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
