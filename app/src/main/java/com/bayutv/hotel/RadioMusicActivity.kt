package com.bayutv.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class RadioMusicActivity : AppCompatActivity() {

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val streamUrl: String = "",
        val thumbUrl: String? = null,
        val isLive: Boolean = false,
        val isYtMusic: Boolean = false
    )

    data class LyricLine(val timeMs: Long, val text: String)

    // Mesin pemutar global agar lagu tetap jalan saat pindah layar atau layar HP mati
    object MusicEngine {
        var player: MediaPlayer? = null
        var currentTrack: Track? = null
        var queue: List<Track> = emptyList()
        var queueIndex: Int = -1
        var isPreparing: Boolean = false
        var lyrics: List<LyricLine> = emptyList()
        var lyricStatus: String = "Pilih lagu untuk melihat lirik karaoke"
        var onStateChanged: (() -> Unit)? = null

        fun playTrack(track: Track, list: List<Track>, index: Int) {
            currentTrack = track
            queue = list
            queueIndex = index
            isPreparing = true
            lyrics = emptyList()
            lyricStatus = if (track.isLive) "Siaran Radio / Live Audio" else "Mencari lirik karaoke..."
            onStateChanged?.invoke()

            Thread {
                try {
                    val resolvedUrl = if (track.isYtMusic && track.streamUrl.isEmpty()) {
                        resolveYtAudioUrl(track.id)
                    } else {
                        track.streamUrl
                    }
                    if (resolvedUrl.isNullOrBlank()) {
                        isPreparing = false
                        lyricStatus = "Gagal memuat aliran audio. Coba lagu lain."
                        onStateChanged?.invoke()
                        return@Thread
                    }

                    if (!track.isLive) {
                        val fetchedLyrics = fetchSyncedLyrics(track.title, track.artist)
                        lyrics = fetchedLyrics
                        lyricStatus = if (fetchedLyrics.isEmpty()) {
                            "Lirik sinkron tidak tersedia untuk lagu ini"
                        } else {
                            ""
                        }
                    }

                    startMediaPlayer(resolvedUrl)
                } catch (_: Exception) {
                    isPreparing = false
                    lyricStatus = "Koneksi terputus saat memutar audio"
                    onStateChanged?.invoke()
                }
            }.start()
        }

        private fun startMediaPlayer(url: String) {
            try {
                player?.release()
            } catch (_: Exception) {
            }
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                isPreparing = false
                it.start()
                onStateChanged?.invoke()
            }
            mp.setOnCompletionListener {
                playNext()
            }
            mp.setOnErrorListener { _, _, _ ->
                isPreparing = false
                lyricStatus = "Format audio tidak didukung atau server sibuk"
                onStateChanged?.invoke()
                true
            }
            player = mp
            mp.prepareAsync()
        }

        fun togglePause() {
            val mp = player ?: return
            try {
                if (mp.isPlaying) mp.pause() else mp.start()
                onStateChanged?.invoke()
            } catch (_: Exception) {
            }
        }

        fun playNext() {
            if (queue.isEmpty()) return
            val next = (queueIndex + 1) % queue.size
            playTrack(queue[next], queue, next)
        }

        fun playPrev() {
            if (queue.isEmpty()) return
            val prev = if (queueIndex - 1 < 0) queue.size - 1 else queueIndex - 1
            playTrack(queue[prev], queue, prev)
        }

        private fun resolveYtAudioUrl(videoId: String): String? {
            // 1. Coba InnerTube IOS Client (Audio-Only m4a hemat kuota)
            try {
                val url = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)")

                val body = """{"context":{"client":{"clientName":"IOS","clientVersion":"19.45.4","deviceModel":"iPhone16,2","osVersion":"18.1.0.22B83","hl":"id","gl":"ID"}},"videoId":"$videoId"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                    if (formats != null) {
                        var bestUrl: String? = null
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val mime = f.optString("mimeType", "")
                            val u = f.optString("url", "")
                            if (mime.startsWith("audio/mp4") && u.isNotEmpty()) {
                                return u
                            }
                            if (mime.startsWith("audio/") && u.isNotEmpty() && bestUrl == null) {
                                bestUrl = u
                            }
                        }
                        if (bestUrl != null) return bestUrl
                    }
                }
            } catch (_: Exception) {
            }

            // 2. Fallback Piped API publik (Audio m4a)
            val pipedHosts = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de")
            for (host in pipedHosts) {
                try {
                    val conn = URL("$host/streams/$videoId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 7000
                    conn.readTimeout = 9000
                    conn.setRequestProperty("User-Agent", "BayuTV")
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val audios = json.optJSONArray("audioStreams") ?: continue
                        for (i in 0 until audios.length()) {
                            val a = audios.getJSONObject(i)
                            val u = a.optString("url", "")
                            if (a.optString("mimeType").contains("mp4") && u.isNotEmpty()) return u
                        }
                        if (audios.length() > 0) {
                            val u = audios.getJSONObject(0).optString("url", "")
                            if (u.isNotEmpty()) return u
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return null
        }

        private fun fetchSyncedLyrics(title: String, artist: String): List<LyricLine> {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: ""
            val queries = listOf(
                "https://lrclib.net/api/get?track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}&artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}",
                "https://lrclib.net/api/search?q=${URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")}"
            )
            for (qUrl in queries) {
                try {
                    val conn = URL(qUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "BayuTV/1.0")
                    if (conn.responseCode != 200) continue
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    val obj = if (raw.startsWith("[")) {
                        val arr = JSONArray(raw)
                        if (arr.length() > 0) arr.getJSONObject(0) else null
                    } else {
                        JSONObject(raw)
                    }
                    val lrc = obj?.optString("syncedLyrics", "") ?: ""
                    if (lrc.isNotBlank()) {
                        val parsed = parseLrc(lrc)
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (_: Exception) {
                }
            }
            return emptyList()
        }

        private fun parseLrc(lrc: String): List<LyricLine> {
            val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
            val out = ArrayList<LyricLine>()
            for (line in lrc.lines()) {
                val m = regex.find(line.trim()) ?: continue
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val msStr = m.groupValues[3]
                val ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10L else (msStr.toLongOrNull() ?: 0L)
                val text = m.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    out.add(LyricLine(min * 60_000L + sec * 1_000L + ms, text))
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()
    private val mute = 0xFFB5AEA0.toInt()
    private val bgDark = 0xFF0B0A09.toInt()
    private val cardBg = 0xFF161412.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = arrayOf("Cari Lagu", "Radio ID", "Murottal & Lofi", "Musik Lokal")
    private val tabViews = ArrayList<TextView>()
    private var activeTab = 0

    private lateinit var searchBarBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var listBox: LinearLayout

    private lateinit var artView: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var timeLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: TextView

    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricBox: LinearLayout
    private val lyricViews = ArrayList<TextView>()
    private var lastHighlightedIdx = -1

    private lateinit var notice: Notice
    private var isSeeking = false

    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            ui.postDelayed(this, 300L)
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface = Typeface.SANS_SERIF): TextView {
        return TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@RadioMusicActivity, sp))
            setTextColor(color)
            typeface = face
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isPhonePortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600

        val root = FrameLayout(this).apply { setBackgroundColor(bgDark) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPhonePortrait) 16 else 36), dp(14), dp(if (isPhonePortrait) 16 else 36), dp(14))
        }

        // Header & Tab Responsif
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val brand = tv("BAYU AUDIO", 17f, brassLight, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.1f
            setPadding(0, 0, dp(14), 0)
        }
        topBar.addView(brand)

        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in tabs.indices) {
            val t = tv(tabs[i], 13f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f -> styleTab(this, i, f) }
                setOnClickListener { selectTab(i) }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(6)
            tabRow.addView(t, lp)
            tabViews.add(t)
        }
        tabScroll.addView(tabRow)
        topBar.addView(tabScroll, LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(topBar)

        // Kotak Pencarian Lagu YT Music
        searchBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cardBg)
                setStroke(dp(1), 0x44B8975A)
            }
        }
        searchInput = EditText(this).apply {
            hint = "Cari judul lagu atau penyanyi (YT Music)..."
            setHintTextColor(0xFF8C8578.toInt())
            setTextColor(ivory)
            textSize = 14f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) searchYtMusic(q)
                    hideKeyboard()
                    true
                } else false
            }
        }
        val searchBtn = tv("Cari", 12f, bgDark, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(brassLight)
            }
            setOnClickListener {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) searchYtMusic(q)
                hideKeyboard()
            }
        }
        searchBarBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchBarBox.addView(searchBtn)
        val sblp = LinearLayout.LayoutParams(-1, -2)
        sblp.bottomMargin = dp(10)
        outer.addView(searchBarBox, sblp)

        // Area Konten Utama: 2 Kolom di TV, 1 Kolom Bertumpuk di HP
        val bodyContainer = LinearLayout(this).apply {
            orientation = if (isPhonePortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        // Panel Kiri / Atas: Daftar Lagu & Stasiun
        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, if (isPhonePortrait) 0 else dp(16), dp(16))
        }
        listScroll.addView(listBox, FrameLayout.LayoutParams(-1, -2))

        // Panel Kanan / Bawah: Pemutar & Lirik Karaoke
        val playerPanel = buildPlayerAndLyricsPanel(isPhonePortrait)

        if (isPhonePortrait) {
            bodyContainer.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1.15f))
            val plp = LinearLayout.LayoutParams(-1, 0, 1f)
            plp.topMargin = dp(8)
            bodyContainer.addView(playerPanel, plp)
        } else {
            bodyContainer.addView(listScroll, LinearLayout.LayoutParams(0, -1, 1.05f))
            bodyContainer.addView(playerPanel, LinearLayout.LayoutParams(0, -1, 1f))
        }

        outer.addView(bodyContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(outer, FrameLayout.LayoutParams(-1, -1))
        notice = Notice(this, root)
        setContentView(root)

        MusicEngine.onStateChanged = {
            ui.post {
                if (!isFinishing) syncPlayerUi()
            }
        }

        selectTab(0)
        syncPlayerUi()
    }

    private fun buildPlayerAndLyricsPanel(isPhone: Boolean): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(cardBg)
                setStroke(dp(1), 0x33B8975A)
            }
        }

        // Info Lagu + Kontrol Pemutar
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF23201C.toInt())
        }
        val artSize = dp(if (isPhone) 52 else 68)
        topRow.addView(artView, LinearLayout.LayoutParams(artSize, artSize))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        nowTitle = tv("Belum ada audio diputar", 15f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowArtist = tv("Pilih lagu atau radio untuk mulai", 12f, brassLight).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        timeLabel = tv("00:00 / 00:00", 11f, mute)
        infoCol.addView(nowTitle)
        infoCol.addView(nowArtist)
        infoCol.addView(timeLabel)
        topRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Tombol Kontrol (Prev, Play/Pause, Next)
        val btnPrev = controlBtn("⏮") { MusicEngine.playPrev() }
        btnPlayPause = controlBtn("▶", primary = true) { MusicEngine.togglePause() }
        val btnNext = controlBtn("⏭") { MusicEngine.playNext() }

        topRow.addView(btnPrev)
        val mlp = LinearLayout.LayoutParams(-2, -2)
        mlp.setMargins(dp(6), 0, dp(6), 0)
        topRow.addView(btnPlayPause, mlp)
        topRow.addView(btnNext)
        panel.addView(topRow)

        // Seekbar
        seekBar = SeekBar(this).apply {
            max = 1000
            setPadding(0, dp(6), 0, dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = MusicEngine.player?.duration ?: 0
                        if (dur > 0) {
                            val target = (dur.toLong() * progress) / 1000L
                            timeLabel.text = "${fmtTime(target)} / ${fmtTime(dur.toLong())}"
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeeking = false
                    val mp = MusicEngine.player ?: return
                    val dur = mp.duration
                    if (dur > 0 && sb != null) {
                        mp.seekTo(((dur.toLong() * sb.progress) / 1000L).toInt())
                    }
                }
            })
        }
        panel.addView(seekBar, LinearLayout.LayoutParams(-1, -2))

        // Area Lirik Karaoke Sinkron
        val lyricHeader = tv("LIRIK KARAOKE SINKRON", 10f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(2), 0, dp(6))
        }
        panel.addView(lyricHeader)

        lyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF0F0E0C.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        lyricBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lyricScroll.addView(lyricBox, FrameLayout.LayoutParams(-1, -2))
        panel.addView(lyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        return panel
    }

    private fun controlBtn(symbol: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        return tv(symbol, 15f, if (primary) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (primary) brassLight else 0x26FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (f) Color.WHITE else if (primary) brassLight else 0x26FFFFFF)
                }
                setTextColor(if (f || primary) bgDark else ivory)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun styleTab(v: TextView, idx: Int, focused: Boolean) {
        val active = idx == activeTab
        v.setTextColor(if (focused) bgDark else if (active) brassLight else mute)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            when {
                focused -> {
                    setColor(brassLight)
                    setStroke(dp(2), Color.WHITE)
                }
                active -> {
                    setColor(0x33B8975A)
                    setStroke(dp(1), brass)
                }
                else -> setColor(0x18FFFFFF)
            }
        }
    }

    private fun selectTab(idx: Int) {
        activeTab = idx
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
        searchBarBox.visibility = if (idx == 0) View.VISIBLE else View.GONE
        listBox.removeAllViews()

        when (idx) {
            0 -> {
                if (searchInput.text.toString().trim().isNotEmpty()) {
                    searchYtMusic(searchInput.text.toString().trim())
                } else {
                    searchYtMusic("Lagu Pop Indonesia Terbaru")
                }
            }
            1 -> loadIndonesianRadios()
            2 -> loadMurottalAndLofi()
            3 -> loadLocalMusic()
        }
    }

    private fun searchYtMusic(query: String) {
        listBox.removeAllViews()
        listBox.addView(tv("Mencari \"$query\" di YouTube Music...", 13f, mute))
        Thread {
            val results = ArrayList<Track>()
            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                val safeQ = query.replace("\"", "")
                val payload = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"query":"$safeQ","params":"EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"}"""
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val rootJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractMusicItems(rootJson, results)
                }
            } catch (_: Exception) {
            }

            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeAllViews()
                if (results.isEmpty()) {
                    listBox.addView(tv("Tidak ditemukan hasil. Coba kata kunci lain.", 13f, mute))
                } else {
                    renderTrackList(results)
                }
            }
        }.start()
    }

    private fun extractMusicItems(node: Any?, out: MutableList<Track>) {
        if (out.size >= 25 || node == null) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    val vid = renderer.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
                    val cols = renderer.optJSONArray("flexColumns")
                    var title = ""
                    var artist = ""
                    if (cols != null && cols.length() > 0) {
                        title = readRunsText(cols.getJSONObject(0))
                        if (cols.length() > 1) artist = readRunsText(cols.getJSONObject(1))
                    }
                    if (vid.isNotEmpty() && title.isNotEmpty() && out.none { it.id == vid }) {
                        out.add(
                            Track(
                                id = vid,
                                title = title,
                                artist = artist.ifBlank { "YouTube Music" },
                                thumbUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                isLive = false,
                                isYtMusic = true
                            )
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractMusicItems(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractMusicItems(node.opt(i), out)
                }
            }
        }
    }

    private fun readRunsText(obj: JSONObject?): String {
        val runs = obj?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun loadIndonesianRadios() {
        val curated = listOf(
            Track("rri1", "RRI Pro 1 Nasional", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9014/rrijakartapro1.mp3", isLive = true),
            Track("rri3", "RRI Pro 3 Berita Nasional", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9016/rrijakartapro3.mp3", isLive = true),
            Track("elshinta", "Elshinta News & Talk", "Jakarta · Berita & Lalu Lintas", "https://stream-ssl.arenastreaming.com:8000/jakarta", isLive = true),
            Track("prambors", "Prambors FM", "Hits Anak Muda Indonesia", "https://22283.live.streamtheworld.com/PRAMBORS_FMAAC.aac", isLive = true),
            Track("hardrock", "Hard Rock FM Jakarta", "Pop & Lifestyle", "https://n01.radiojar.com/7csmg90fuqruv", isLive = true)
        )
        renderTrackList(curated)

        Thread {
            val fetched = ArrayList<Track>(curated)
            try {
                val conn = URL("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/ID?limit=25&order=clickcount&reverse=true&hidebroken=true").openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.setRequestProperty("User-Agent", "BayuTV/1.0")
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name", "").trim()
                        val u = o.optString("url_resolved", o.optString("url", "")).trim()
                        val tags = o.optString("tags", "Radio Indonesia")
                        if (name.isNotEmpty() && u.startsWith("http") && fetched.none { it.title.equals(name, true) }) {
                            fetched.add(Track("rb_$i", name, tags.ifBlank { "Radio Indonesia" }, u, o.optString("favicon", "").ifBlank { null }, isLive = true))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            ui.post {
                if (!isFinishing && activeTab == 1 && fetched.size > curated.size) {
                    listBox.removeAllViews()
                    renderTrackList(fetched)
                }
            }
        }.start()
    }

    private fun loadMurottalAndLofi() {
        val list = listOf(
            Track("murottal1", "Murottal Al-Qur'an 24 Jam", "Mishary Rashid Alafasy", "https://backup.qurango.net/radio/mishary_alafasi", isLive = true),
            Track("murottal2", "Radio Al-Qur'an Makkah", "Siaran Langsung Masjidil Haram", "https://backup.qurango.net/radio/tarateel", isLive = true),
            Track("murottal3", "Murottal Abdurrahman As-Sudais", "Imam Masjidil Haram", "https://backup.qurango.net/radio/abdulrahman_alsudaes", isLive = true),
            Track("lofi1", "Lofi Hip Hop & Belajar", "Musik Santai Tanpa Vokal", "https://streams.ilovemusic.de/iloveradio17.mp3", isLive = true),
            Track("piano1", "Instrumental Piano & Hujan", "Relaksasi & Pengantar Tidur", "https://strm112.1.fm/spa_mobile_mp3", isLive = true)
        )
        renderTrackList(list)
    }

    private fun loadLocalMusic() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 202)
            listBox.addView(tv("Izinkan akses penyimpanan untuk memutar file MP3 lokal (0 kuota).", 13f, mute))
            return
        }
        Thread {
            val locals = ArrayList<Track>()
            try {
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
                contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
                    while (c.moveToNext() && locals.size < 100) {
                        val id = c.getLong(0).toString()
                        val title = c.getString(1) ?: "Audio"
                        val artist = c.getString(2) ?: "Lokal"
                        val path = c.getString(3) ?: continue
                        locals.add(Track("loc_$id", title, artist, path, isLive = false))
                    }
                }
            } catch (_: Exception) {
            }
            ui.post {
                if (isFinishing || activeTab != 3) return@post
                listBox.removeAllViews()
                if (locals.isEmpty()) {
                    listBox.addView(tv("Tidak ada file MP3 ditemukan di penyimpanan perangkat.", 13f, mute))
                } else {
                    renderTrackList(locals)
                }
            }
        }.start()
    }

    private fun renderTrackList(items: List<Track>) {
        for ((index, item) in items.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFF151311.toInt())
                    setStroke(dp(1), 0x22FFFFFF)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (f) 0x33B8975A else 0xFF151311.toInt())
                        setStroke(dp(if (f) 2 else 1), if (f) brassLight else 0x22FFFFFF)
                    }
                }
                setOnClickListener {
                    MusicEngine.playTrack(item, items, index)
                }
            }

            val badge = tv(if (item.isLive) "LIVE" else if (item.isYtMusic) "YT" else "MP3", 10f, brassLight, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0x28B8975A)
                }
            }
            row.addView(badge)

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            col.addView(tv(item.title, 13.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(tv(item.artist, 11.5f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(8)
            listBox.addView(row, lp)
        }
    }

    private fun syncPlayerUi() {
        val t = MusicEngine.currentTrack
        if (t == null) {
            nowTitle.text = "Belum ada audio diputar"
            nowArtist.text = "Pilih lagu atau radio untuk mulai"
            btnPlayPause.text = "▶"
        } else {
            nowTitle.text = t.title
            nowArtist.text = if (MusicEngine.isPreparing) "Menyiapkan audio hemat kuota..." else t.artist
            Images.load(t.thumbUrl, artView)
            val playing = try { MusicEngine.player?.isPlaying == true } catch (_: Exception) { false }
            btnPlayPause.text = if (playing) "⏸" else "▶"
        }
        rebuildLyricViews()
    }

    private fun rebuildLyricViews() {
        lyricBox.removeAllViews()
        lyricViews.clear()
        lastHighlightedIdx = -1

        val lines = MusicEngine.lyrics
        if (lines.isEmpty()) {
            lyricBox.addView(tv(MusicEngine.lyricStatus, 13f, mute).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
            return
        }

        for (line in lines) {
            val ltv = tv(line.text, 14f, 0xFF7D766B.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), dp(7))
            }
            lyricBox.addView(ltv, LinearLayout.LayoutParams(-1, -2))
            lyricViews.add(ltv)
        }
    }

    private fun updatePlaybackProgress() {
        val mp = MusicEngine.player ?: return
        val track = MusicEngine.currentTrack ?: return
        try {
            if (track.isLive) {
                timeLabel.text = "SIARAN LANGSUNG · HEMAT KUOTA"
                seekBar.isEnabled = false
                return
            }
            seekBar.isEnabled = true
            val pos = mp.currentPosition.toLong()
            val dur = mp.duration.toLong()
            if (dur > 0 && !isSeeking) {
                seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                timeLabel.text = "${fmtTime(pos)} / ${fmtTime(dur)}"
            }

            // Sinkronisasi Baris Lirik Karaoke
            val lines = MusicEngine.lyrics
            if (lines.isNotEmpty() && lyricViews.size == lines.size) {
                var activeIdx = -1
                for (i in lines.indices) {
                    if (pos >= lines[i].timeMs) activeIdx = i else break
                }
                if (activeIdx != lastHighlightedIdx && activeIdx >= 0) {
                    if (lastHighlightedIdx in lyricViews.indices) {
                        lyricViews[lastHighlightedIdx].setTextColor(0xFF7D766B.toInt())
                        lyricViews[lastHighlightedIdx].setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 14f))
                    }
                    val activeView = lyricViews[activeIdx]
                    activeView.setTextColor(brassLight)
                    activeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 17f))
                    lastHighlightedIdx = activeIdx

                    val targetY = (activeView.top - lyricScroll.height / 3).coerceAtLeast(0)
                    lyricScroll.smoothScrollTo(0, targetY)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun fmtTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }
}
