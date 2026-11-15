package com.bayutv.hotel

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File

// TV Live hybrid: channel internet (ExoPlayer) dan channel antena (TvView) dalam satu daftar.
class MainActivity : AppCompatActivity() {

    private class Cat(val title: String, val keys: List<String>)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private var player: ExoPlayer? = null
    private var tvView: TvView? = null

    private var all: List<Channel> = emptyList()
    private var byKey: Map<String, Channel> = emptyMap()
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var scan: MutableMap<String, String> = HashMap()
    private val hidden = HashSet<String>()
    private val pendingDead = ArrayList<String>()
    private var numberOf: Map<String, Int> = emptyMap()
    private var cats: List<Cat> = emptyList()
    private var catIdx = 0
    private var shown: List<Channel> = emptyList()
    private var zapKeys: List<String> = emptyList()
    private var curKey: String? = null
    private var cands: List<Channel> = emptyList()
    private var candPos = 0
    private var numBuf = ""
    private var failStreak = 0
    private var lastDir = 1
    private var antennaInfo = ""
    private var antenna: List<Channel> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var panel: LinearLayout
    private lateinit var search: EditText
    private lateinit var catList: ListView
    private lateinit var chanList: ListView
    private lateinit var catAdapter: BaseAdapter
    private lateinit var chanAdapter: BaseAdapter
    private lateinit var notice: Notice

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val jump = Runnable {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        if (n != null) {
            val k = numberOf.entries.firstOrNull { it.value == n }?.key
            if (k != null) play(k, zapKeys) else showBanner("Channel " + n + " tidak ada", 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        playerView = PlayerView(this)
        playerView.useController = false
        playerView.isFocusable = false
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        banner = tv("", 26f, ivory, Typeface.SERIF)
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

        status = tv("", 20f, ivory, Typeface.SANS_SERIF)
        status.setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        status.setPadding(dp(32), dp(20), dp(32), dp(24))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        buildPanel()

        notice = Notice(this, root)
        setContentView(root)
        setupPlayer()

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

        if (!Antenna.hasPermission(this) && !prefs.getBoolean("asked_tv_perm", false)) {
            prefs.edit().putBoolean("asked_tv_perm", true).apply()
            try {
                requestPermissions(arrayOf(Antenna.PERM), 21)
            } catch (e: Exception) {
            }
        }

        notice.show("Mohon tunggu", "Memuat daftar channel...")
        Thread { loadChannels() }.start()
    }

    // ---------- Tampilan daftar (kategori + channel) ----------

    private fun buildPanel() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.setBackgroundColor(0xF0151412.toInt())
        panel.setPadding(dp(16), dp(16), dp(16), dp(16))
        panel.visibility = View.GONE

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val head = tv("KATEGORI", 13f, brass, Typeface.SANS_SERIF)
        head.letterSpacing = 0.2f
        head.setPadding(dp(10), 0, 0, dp(8))
        left.addView(head)
        catList = ListView(this)
        catList.divider = null
        catList.selector = ColorDrawable(0x44B8975A)
        catList.isFocusable = true
        catAdapter = object : BaseAdapter() {
            override fun getCount(): Int = cats.size
            override fun getItem(position: Int): Any = cats[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: tv("", 17f, ivory, Typeface.SANS_SERIF).also {
                    it.setPadding(dp(10), dp(9), dp(10), dp(9))
                    it.maxLines = 1
                    it.ellipsize = TextUtils.TruncateAt.END
                }
                val c = cats[position]
                v.text = c.title + "  (" + c.keys.size + ")"
                v.setTextColor(if (position == catIdx) brassLight else mute)
                return v
            }
        }
        catList.adapter = catAdapter
        catList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != catIdx) selectCat(position, false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        catList.setOnItemClickListener { _, _, pos, _ ->
            selectCat(pos, false)
            chanList.requestFocus()
        }
        left.addView(catList, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(left, LinearLayout.LayoutParams(dp(290), -1))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.setPadding(dp(12), 0, 0, 0)
        search = EditText(this)
        search.hint = "Cari channel"
        search.setHintTextColor(0xFF8D8678.toInt())
        search.setTextColor(ivory)
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_DONE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            chanList.requestFocus()
            true
        }
        right.addView(search, LinearLayout.LayoutParams(-1, -2))

        chanList = ListView(this)
        chanList.divider = null
        chanList.selector = ColorDrawable(0x55B8975A)
        chanList.isFocusable = true
        chanAdapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                if (convertView is LinearLayout) {
                    row = convertView
                } else {
                    row = LinearLayout(this@MainActivity)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(dp(8), dp(6), dp(8), dp(6))
                    val num = tv("", 15f, mute, Typeface.SANS_SERIF)
                    num.gravity = Gravity.END
                    row.addView(num, LinearLayout.LayoutParams(dp(44), -2))
                    val logo = ImageView(this@MainActivity)
                    logo.scaleType = ImageView.ScaleType.FIT_CENTER
                    val llp = LinearLayout.LayoutParams(dp(56), dp(32))
                    llp.setMargins(dp(12), 0, dp(12), 0)
                    row.addView(logo, llp)
                    val name = tv("", 18f, ivory, Typeface.SANS_SERIF)
                    name.maxLines = 1
                    name.ellipsize = TextUtils.TruncateAt.END
                    row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                    val star = tv("", 16f, brass, Typeface.SANS_SERIF)
                    row.addView(star, LinearLayout.LayoutParams(-2, -2))
                }
                val ch = shown[position]
                (row.getChildAt(0) as TextView).text = (numberOf[ch.key] ?: 0).toString()
                Images.loadUrl(ch.logo, row.getChildAt(1) as ImageView, 128, true)
                val nm = row.getChildAt(2) as TextView
                val extra = if (ch.isAntenna) "  · antena " + ch.number else if (ch.status == "geo") "  · belum dicek" else ""
                nm.text = Playlist.display(ch.name) + extra
                nm.setTextColor(if (ch.key == curKey) brassLight else ivory)
                (row.getChildAt(3) as TextView).text = if (favs.contains(ch.key)) "★" else ""
                return row
            }
        }
        chanList.adapter = chanAdapter
        chanList.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) {
                play(shown[pos].key, shown.map { it.key })
                hidePanel()
            }
        }
        chanList.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos < shown.size) toggleFav(shown[pos])
            true
        }
        right.addView(chanList, LinearLayout.LayoutParams(-1, 0, 1f))
        val hint = tv("OK: tonton  ·  Tahan OK / Menu: favorit  ·  Kiri/Kanan: pindah kolom", 12f, mute, Typeface.SANS_SERIF)
        hint.setPadding(dp(8), dp(6), 0, 0)
        right.addView(hint)
        panel.addView(right, LinearLayout.LayoutParams(dp(500), -1))

        root.addView(panel, FrameLayout.LayoutParams(-2, -1, Gravity.START))
    }

    private var favs: Set<String> = emptySet()

    private fun toggleFav(ch: Channel) {
        if (ch.key == Antenna.OPEN_KEY) return
        val added = Playlist.toggleFavorite(this, ch.key)
        notice.show(null, Playlist.display(ch.name) + (if (added) " ditambahkan ke Favorit" else " dihapus dari Favorit"), 1800L)
        val keepTitle = cats.getOrNull(catIdx)?.title
        rebuild()
        val i = cats.indexOfFirst { it.title == keepTitle }
        selectCat(if (i >= 0) i else 0, true)
    }

    // ---------- Data ----------

    private fun loadChannels() {
        var list = Playlist.loadAll(this)
        val hadCache = list.isNotEmpty()
        if (!hadCache) {
            Playlist.download(this)
            list = Playlist.loadAll(this)
        }
        val ant = Antenna.load(this)
        ui.post { onLoaded(list, ant, false) }
        if (hadCache && Playlist.ageHours(this) >= 6) {
            if (Playlist.download(this)) {
                val fresh = Playlist.loadAll(this)
                ui.post { onLoaded(fresh, ant, true) }
            }
        }
    }

    private fun onLoaded(list: List<Channel>, ant: Antenna.Result, refresh: Boolean) {
        if (isFinishing) return
        antenna = if (ant.channels.isNotEmpty()) ant.channels else listOf(Antenna.shortcut())
        antennaInfo = ant.info
        if (list.isEmpty() && ant.channels.isEmpty()) {
            notice.show("Daftar kosong", "Periksa koneksi internet TV, lalu buka lagi aplikasinya.")
            return
        }
        setData(list)
        if (refresh) {
            if (panel.visibility == View.VISIBLE) selectCat(catIdx.coerceAtMost(cats.size - 1), true)
            return
        }
        notice.hide()
        val last = prefs.getString("last", null)
        val trying = prefs.getString("trying", null)
        prefs.edit().remove("trying").apply()
        val vis = visibleKeys()
        val start = if (last != null && last != trying && byKey.containsKey(last) && last !in hidden) last
        else vis.firstOrNull { it != trying && byKey[it]?.isAntenna != true && it != Antenna.OPEN_KEY } ?: vis.firstOrNull()
        if (start != null) play(start, vis)
        if (trying != null) {
            val bad = byKey[trying]
            if (bad != null) notice.show("Dilewati", "Channel \"" + Playlist.display(bad.name) + "\" tadi membuat aplikasi tertutup, jadi tidak diputar otomatis.", 7000L)
        }
    }

    private fun setData(list: List<Channel>) {
        scan = Playlist.loadScan(this)
        val g = Playlist.group(list)
        val reps = ArrayList<Channel>()
        for ((k, members) in g) {
            val want = scan[k]
            reps.add(if (!want.isNullOrEmpty()) (members.firstOrNull { it.url == want } ?: members[0]) else members[0])
        }
        val gm = HashMap<String, List<Channel>>()
        for ((k, v) in g) gm[k] = v
        for (a in antenna) gm[a.key] = listOf(a)
        groups = gm
        all = antenna + reps
        byKey = all.associateBy { it.key }
        hidden.clear()
        for ((k, v) in scan) if (v.isEmpty() && byKey.containsKey(k)) hidden.add(k)
        rebuild()
    }

    private fun visibleKeys(): List<String> = all.filter { it.key !in hidden }.map { it.key }

    private fun rebuild() {
        favs = Playlist.favorites(this)
        val vis = all.filter { it.key !in hidden }
        val nums = HashMap<String, Int>()
        var n = 0
        for (c in vis) {
            if (c.key == Antenna.OPEN_KEY) continue
            n++
            nums[c.key] = n
        }
        numberOf = nums
        val out = ArrayList<Cat>()
        val fav = vis.filter { favs.contains(it.key) }.map { it.key }
        if (fav.isNotEmpty()) out.add(Cat("Favorit", fav))
        out.add(Cat("Semua channel", vis.map { it.key }))
        val ant = vis.filter { it.region == "Antena" }.map { it.key }
        if (ant.isNotEmpty()) out.add(Cat("Antena · TV Digital", ant))
        val byRegion = LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>()
        for (r in listOf("Indonesia", "Internasional", "Playlist Saya")) byRegion[r] = LinkedHashMap()
        for (c in vis) {
            if (c.region == "Antena") continue
            byRegion.getOrPut(c.region) { LinkedHashMap() }.getOrPut(c.category) { ArrayList() }.add(c.key)
        }
        for ((r, m) in byRegion) {
            if (m.isEmpty()) continue
            val allR = ArrayList<String>()
            for (v in m.values) allR.addAll(v)
            out.add(Cat(r + " · Semua", allR))
            for ((c, keys) in m) out.add(Cat(r + " · " + c, keys))
        }
        cats = out
        catAdapter.notifyDataSetChanged()
    }

    private fun selectCat(i: Int, keepSel: Boolean) {
        if (cats.isEmpty()) return
        catIdx = i.coerceIn(0, cats.size - 1)
        catAdapter.notifyDataSetChanged()
        applyFilter()
        if (!keepSel) chanList.setSelection(0)
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val keys = if (q.isNotEmpty()) visibleKeys() else (cats.getOrNull(catIdx)?.keys ?: emptyList())
        shown = keys.mapNotNull { byKey[it] }.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        chanAdapter.notifyDataSetChanged()
    }

    // ---------- Pemutar ----------

    private fun setupPlayer() {
        // Buffer kecil agar hemat RAM (TV 2 GB)
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12000, 30000, 1500, 3000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(lc).build()
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Memuat..."
                if (state == Player.STATE_READY) onPlaying()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (candPos + 1 < cands.size) {
                    candPos++
                    status.text = "Mencoba sumber lain (" + (candPos + 1) + "/" + cands.size + ")..."
                    startCandidate()
                    return
                }
                val cause = error.cause
                val why = if (cause is HttpDataSource.InvalidResponseCodeException) {
                    when (cause.responseCode) {
                        404, 410 -> "alamat siaran sudah tidak ada"
                        401, 403 -> "ditolak server (diblokir/butuh izin)"
                        else -> "server bermasalah (HTTP " + cause.responseCode + ")"
                    }
                } else {
                    error.errorCodeName
                }
                onChannelFailed(why)
            }
        })
        player = p
    }

    private fun onPlaying() {
        status.text = ""
        notice.hide()
        failStreak = 0
        val k = curKey ?: return
        prefs.edit().putString("last", k).remove("trying").apply()
        val ch = cands.getOrNull(candPos) ?: return
        var changed = false
        if (pendingDead.isNotEmpty()) {
            for (d in pendingDead) scan[d] = ""
            pendingDead.clear()
            changed = true
        }
        if (scan[k] != ch.url) {
            scan[k] = ch.url
            changed = true
        }
        if (changed) {
            val copy = HashMap(scan)
            Thread { Playlist.saveScan(this, copy) }.start()
        }
    }

    private fun onChannelFailed(why: String) {
        prefs.edit().remove("trying").apply()
        status.text = ""
        val k = curKey ?: return
        val ch = byKey[k] ?: return
        failStreak++
        if (failStreak >= 3) {
            // Beberapa gagal beruntun: kemungkinan internet TV bermasalah, jangan sembunyikan apa pun
            pendingDead.clear()
            notice.show("Sinyal hilang", "Beberapa channel gagal berturut-turut. Periksa internet TV.\n\nAtas/bawah: ganti channel  ·  OK: daftar channel")
            return
        }
        hidden.add(k)
        pendingDead.add(k)
        rebuild()
        if (panel.visibility == View.VISIBLE) applyFilter()
        notice.show("Siaran tidak tersedia", Playlist.display(ch.name) + ": " + why + ".\nChannel disembunyikan. Pindai Sinyal untuk mencarinya lagi.", 3500L)
        ui.postDelayed({ if (panel.visibility != View.VISIBLE && curKey == k) zap(lastDir) }, 2600L)
    }

    private fun ensureTvView(): TvView? {
        tvView?.let { return it }
        return try {
            val t = TvView(this)
            t.isFocusable = false
            root.addView(t, 1, FrameLayout.LayoutParams(-1, -1))
            t.setCallback(object : TvView.TvInputCallback() {
                override fun onConnectionFailed(inputId: String?) {
                    status.text = "Gagal tersambung ke tuner antena."
                }

                override fun onVideoAvailable(inputId: String?) {
                    status.text = ""
                    notice.hide()
                    curKey?.let { prefs.edit().putString("last", it).remove("trying").apply() }
                }

                override fun onVideoUnavailable(inputId: String?, reason: Int) {
                    status.text = when (reason) {
                        1 -> "Menyetel..."
                        2 -> "Sinyal antena lemah"
                        3 -> "Buffering..."
                        4 -> "Hanya audio"
                        else -> "Video belum tampil (" + reason + ")"
                    }
                }
            })
            tvView = t
            t
        } catch (e: Exception) {
            null
        }
    }

    private fun play(key: String, list: List<String>) {
        val ch = byKey[key] ?: return
        if (key == Antenna.OPEN_KEY) {
            if (!Antenna.openSaved(this)) {
                notice.show("TV Antena", "Aplikasi antena belum bisa dibuka. Pilih aplikasinya dari menu TV Antena di Beranda." +
                    (if (antennaInfo.isNotEmpty()) "\n\n" + antennaInfo else ""), 7000L)
            }
            return
        }
        curKey = key
        if (list.isNotEmpty()) zapKeys = list
        val num = numberOf[key]
        showBanner((if (num != null) num.toString() + "   " else "") + Playlist.display(ch.name), 3000L)
        notice.hide()
        chanAdapter.notifyDataSetChanged()
        prefs.edit().putString("trying", key).commit()

        if (ch.isAntenna) {
            player?.stop()
            playerView.visibility = View.INVISIBLE
            val t = ensureTvView()
            if (t == null) {
                notice.show("Antena", "Tuner tidak bisa dipakai di dalam aplikasi.", 5000L)
                return
            }
            t.visibility = View.VISIBLE
            status.text = "Menyetel antena..."
            val input = ch.tunerInput ?: return
            try {
                t.tune(input, TvContract.buildChannelUri(ch.tunerId))
            } catch (e: Exception) {
                status.text = "Gagal menyetel: " + e.javaClass.simpleName
            }
            return
        }
        tvView?.let {
            try {
                it.reset()
            } catch (e: Exception) {
            }
            it.visibility = View.GONE
        }
        playerView.visibility = View.VISIBLE
        val members = groups[key] ?: listOf(ch)
        val pref = scan[key]
        val first = if (!pref.isNullOrEmpty()) members.firstOrNull { it.url == pref } ?: ch else ch
        cands = (listOf(first) + members.filter { it.url != first.url }).take(5)
        candPos = 0
        status.text = "Memuat..."
        startCandidate()
    }

    private fun startCandidate() {
        val p = player ?: return
        val ch = cands.getOrNull(candPos) ?: return
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
            onChannelFailed(e.javaClass.simpleName)
        }
    }

    private fun zap(d: Int) {
        lastDir = d
        var list = zapKeys.filter { it !in hidden && it != Antenna.OPEN_KEY }
        if (list.isEmpty()) list = visibleKeys().filter { it != Antenna.OPEN_KEY }
        if (list.isEmpty()) return
        val i = list.indexOf(curKey)
        val next = if (i < 0) 0 else ((i + d) % list.size + list.size) % list.size
        play(list[next], zapKeys)
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
        if (cats.isEmpty()) return
        search.setText("")
        // Buka kategori tempat channel yang sedang diputar
        var ci = cats.indexOfFirst { it.keys == zapKeys }
        if (ci < 0) ci = cats.indexOfFirst { it.title == "Semua channel" }
        selectCat(if (ci >= 0) ci else 0, true)
        panel.visibility = View.VISIBLE
        catList.setSelection(catIdx)
        val pos = shown.indexOfFirst { it.key == curKey }
        chanList.requestFocus()
        chanList.setSelection(if (pos >= 0) pos else 0)
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
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hidePanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (catList.hasFocus()) {
                    chanList.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (chanList.hasFocus()) {
                    catList.requestFocus()
                    catList.setSelection(catIdx)
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> if (chanList.hasFocus()) {
                    val p = chanList.selectedItemPosition
                    if (p >= 0 && p < shown.size) toggleFav(shown[p])
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                val ch = curKey?.let { byKey[it] }
                if (ch != null) showBanner((numberOf[ch.key]?.toString() ?: "") + "   " + Playlist.display(ch.name) +
                    "\n" + ch.region + " · " + ch.category, 4000L)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> {
                curKey?.let { byKey[it] }?.let { toggleFav(it) }
                return true
            }
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            digit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Thread {
                val ant = Antenna.load(this)
                val list = Playlist.loadAll(this)
                ui.post { onLoaded(list, ant, true) }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        if (all.isNotEmpty() && byKey[curKey ?: ""]?.isAntenna != true) player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        try {
            tvView?.reset()
        } catch (e: Exception) {
        }
    }

    override fun onRestart() {
        super.onRestart()
        val k = curKey
        if (k != null && byKey[k]?.isAntenna == true) play(k, zapKeys)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        try {
            tvView?.reset()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
