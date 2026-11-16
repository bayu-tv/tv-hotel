package com.bayutv.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

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

    data class ResolvedStream(
        val url: String,
        val userAgent: String,
        val sourceLabel: String
    )

    data class RadioScanResult(
        val state: Int, // 0 = belum dicek, 1 = mengecek, 2 = aktif, 3 = mati
        val label: String,
        val detail: String
    )

    enum class PlayState {
        IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ERROR
    }

    // Mesin pemutar global berbasis Media3 ExoPlayer agar lagu tetap jalan saat pindah layar atau layar HP mati
    object MusicEngine {
        private val mainHandler = Handler(Looper.getMainLooper())
        var player: ExoPlayer? = null
        var currentTrack: Track? = null
        var queue: List<Track> = emptyList()
        var queueIndex: Int = -1
        var state: PlayState = PlayState.IDLE
        var statusDetail: String = "Pilih lagu atau radio untuk mulai"
        var lyrics: List<LyricLine> = emptyList()
        var lyricStatus: String = "Pilih lagu untuk melihat lirik karaoke"
        var onStateChanged: (() -> Unit)? = null
        private var playSeq = 0

        private fun notifyUi() {
            mainHandler.post { onStateChanged?.invoke() }
        }

        fun playTrack(ctx: Context, track: Track, list: List<Track>, index: Int) {
            val appCtx = ctx.applicationContext
            val seq = ++playSeq
            currentTrack = track
            queue = list
            queueIndex = index
            state = PlayState.RESOLVING
            statusDetail = when {
                track.isYtMusic -> "Mencari stream audio YouTube..."
                track.isLive -> "Menghubungkan ke server siaran..."
                else -> "Membuka file audio..."
            }
            lyrics = emptyList()
            lyricStatus = if (track.isLive) "Siaran Langsung · Lirik tidak tersedia" else "Mencari lirik karaoke..."
            notifyUi()

            mainHandler.post {
                try {
                    player?.stop()
                } catch (_: Exception) {
                }
            }

            Thread {
                try {
                    val resolved: ResolvedStream? = when {
                        track.isYtMusic && track.streamUrl.isEmpty() -> resolveYtAudioUrl(track.id)
                        track.isLive -> resolveRadioUrl(track.streamUrl)
                        else -> ResolvedStream(track.streamUrl, Playlist.DEFAULT_UA, "Lokal")
                    }

                    if (seq != playSeq) return@Thread

                    if (resolved == null || resolved.url.isBlank()) {
                        state = PlayState.ERROR
                        statusDetail = if (track.isYtMusic) {
                            "Semua server YouTube sedang sibuk. Coba lagi atau pilih lagu lain."
                        } else {
                            "Server radio tidak merespons / alamat siaran mati."
                        }
                        lyricStatus = statusDetail
                        notifyUi()
                        return@Thread
                    }

                    if (!track.isLive) {
                        val fetchedLyrics = fetchSyncedLyrics(track.title, track.artist)
                        if (seq == playSeq) {
                            lyrics = fetchedLyrics
                            lyricStatus = if (fetchedLyrics.isEmpty()) {
                                "Lirik sinkron tidak tersedia untuk lagu ini"
                            } else {
                                ""
                            }
                            notifyUi()
                        }
                    }

                    mainHandler.post {
                        if (seq != playSeq) return@post
                        startExoPlayer(appCtx, resolved, seq)
                    }
                } catch (_: Exception) {
                    if (seq == playSeq) {
                        state = PlayState.ERROR
                        statusDetail = "Koneksi internet terputus saat memuat audio."
                        lyricStatus = statusDetail
                        notifyUi()
                    }
                }
            }.start()
        }

        private fun startExoPlayer(appCtx: Context, resolved: ResolvedStream, seq: Int) {
            try {
                player?.release()
            } catch (_: Exception) {
            }

            state = PlayState.BUFFERING
            statusDetail = "Buffering audio (${resolved.sourceLabel})..."
            notifyUi()

            val lc = DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 30000, 1500, 3000)
                .build()

            val isLocalFile = !resolved.url.startsWith("http", ignoreCase = true)
            val exo = if (isLocalFile) {
                ExoPlayer.Builder(appCtx).setLoadControl(lc).build()
            } else {
                val dsf = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent(resolved.userAgent)
                ExoPlayer.Builder(appCtx)
                    .setLoadControl(lc)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                    .build()
            }

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (seq != playSeq) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            state = PlayState.BUFFERING
                            statusDetail = "Buffering aliran audio..."
                            notifyUi()
                        }
                        Player.STATE_READY -> {
                            state = if (exo.playWhenReady) PlayState.PLAYING else PlayState.PAUSED
                            statusDetail = if (currentTrack?.isLive == true) {
                                "Siaran Aktif · ${resolved.sourceLabel}"
                            } else {
                                "Memutar · ${resolved.sourceLabel}"
                            }
                            notifyUi()
                        }
                        Player.STATE_ENDED -> {
                            if (currentTrack?.isLive != true) playNext(appCtx)
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (seq != playSeq) return
                    if (exo.playbackState == Player.STATE_READY) {
                        state = if (isPlaying) PlayState.PLAYING else PlayState.PAUSED
                        notifyUi()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (seq != playSeq) return
                    state = PlayState.ERROR
                    val cause = error.cause
                    statusDetail = when {
                        cause is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
                            404, 410 -> "Server siaran offline / tidak ditemukan (HTTP ${cause.responseCode})"
                            401, 403 -> "Akses ditolak server (HTTP ${cause.responseCode})"
                            429, 502, 503 -> "Server sedang penuh / sibuk (HTTP ${cause.responseCode})"
                            else -> "Gangguan server HTTP ${cause.responseCode}"
                        }
                        currentTrack?.isLive == true -> "Radio tidak bersuara / gagal terhubung ke server"
                        else -> "Stream audio tidak didukung (${error.errorCodeName})"
                    }
                    if (currentTrack?.isLive == true) lyricStatus = statusDetail
                    notifyUi()
                }
            })

            player = exo
            exo.setMediaItem(MediaItem.fromUri(resolved.url))
            exo.prepare()
            exo.playWhenReady = true
        }

        fun togglePause() {
            val exo = player ?: return
            try {
                if (exo.isPlaying) {
                    exo.pause()
                    state = PlayState.PAUSED
                } else {
                    exo.play()
                    state = PlayState.PLAYING
                }
                notifyUi()
            } catch (_: Exception) {
            }
        }

        fun playNext(ctx: Context) {
            if (queue.isEmpty()) return
            val next = (queueIndex + 1) % queue.size
            playTrack(ctx, queue[next], queue, next)
        }

        fun playPrev(ctx: Context) {
            if (queue.isEmpty()) return
            val prev = if (queueIndex - 1 < 0) queue.size - 1 else queueIndex - 1
            playTrack(ctx, queue[prev], queue, prev)
        }

        // Mengurai pengalihan (redirect) serta file playlist .pls / .m3u pada stasiun radio
        private fun resolveRadioUrl(rawUrl: String): ResolvedStream? {
            var cur = rawUrl.trim()
            if (!cur.startsWith("http", ignoreCase = true)) return null
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

            for (hop in 0..4) {
                try {
                    val conn = URL(cur).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("User-Agent", ua)
                    conn.setRequestProperty("Icy-MetaData", "1")
                    val code = conn.responseCode
                    if (code in 301..308) {
                        val loc = conn.getHeaderField("Location") ?: break
                        cur = URL(URL(cur), loc).toString()
                        conn.disconnect()
                        continue
                    }
                    if (code !in 200..299) {
                        conn.disconnect()
                        return null
                    }
                    val ctype = (conn.contentType ?: "").lowercase(Locale.US)
                    val isPlaylistFile = cur.lowercase(Locale.US).endsWith(".pls") ||
                        cur.lowercase(Locale.US).endsWith(".m3u") ||
                        ctype.contains("scpls") ||
                        ctype.contains("mpegurl")

                    if (isPlaylistFile && !cur.lowercase(Locale.US).contains(".m3u8")) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val extracted = extractStreamFromPlaylist(text, cur)
                        if (extracted != null && extracted != cur) {
                            cur = extracted
                            continue
                        }
                    } else {
                        conn.disconnect()
                    }
                    val fmt = when {
                        ctype.contains("aac") -> "AAC Live"
                        ctype.contains("mpeg") || ctype.contains("mp3") -> "MP3 Live"
                        ctype.contains("ogg") -> "OGG Live"
                        else -> "Stream Live"
                    }
                    return ResolvedStream(cur, ua, fmt)
                } catch (_: Exception) {
                    return null
                }
            }
            return ResolvedStream(cur, ua, "Stream Live")
        }

        private fun extractStreamFromPlaylist(body: String, baseUrl: String): String? {
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val candidate = if (line.startsWith("File", ignoreCase = true) && line.contains("=")) {
                    line.substringAfter("=").trim()
                } else {
                    line
                }
                if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                    return candidate
                }
                if (!candidate.contains("=") && candidate.contains(".")) {
                    return try {
                        URL(URL(baseUrl), candidate).toString()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return null
        }

        private fun resolveYtAudioUrl(videoId: String): ResolvedStream? {
            // 1. InnerTube ANDROID_VR (Paling stabil untuk audio-only m4a/webm tanpa blokir poToken)
            try {
                val vrUa = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                val conn = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", vrUa)

                val body = """{"context":{"client":{"clientName":"ANDROID_VR","clientVersion":"1.60.19","deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L","hl":"id","gl":"ID"}},"videoId":"$videoId"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val picked = pickBestAudioFromInnerTube(json, vrUa, "YT Audio HD")
                    if (picked != null) return picked
                }
            } catch (_: Exception) {
            }

            // 2. InnerTube IOS Client (Adaptive audio + fallback HLS manifest)
            try {
                val iosUa = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)"
                val conn = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", iosUa)

                val body = """{"context":{"client":{"clientName":"IOS","clientVersion":"19.45.4","deviceModel":"iPhone16,2","osVersion":"18.1.0.22B83","hl":"id","gl":"ID"}},"videoId":"$videoId"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val picked = pickBestAudioFromInnerTube(json, iosUa, "YT iOS Stream")
                    if (picked != null) return picked
                    val hls = json.optJSONObject("streamingData")?.optString("hlsManifestUrl", "") ?: ""
                    if (hls.startsWith("http")) return ResolvedStream(hls, iosUa, "YT HLS")
                }
            } catch (_: Exception) {
            }

            // 3. Fallback Piped API publik (4 host cadangan)
            val pipedHosts = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.adminforge.de",
                "https://api.piped.private.coffee",
                "https://pipedapi.leptons.xyz"
            )
            for (host in pipedHosts) {
                try {
                    val conn = URL("$host/streams/$videoId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val audios = json.optJSONArray("audioStreams") ?: continue
                        var fallbackUrl: String? = null
                        for (i in 0 until audios.length()) {
                            val a = audios.getJSONObject(i)
                            val u = a.optString("url", "")
                            if (u.startsWith("http")) {
                                if (a.optString("mimeType").contains("mp4")) {
                                    return ResolvedStream(u, "Mozilla/5.0", "Piped M4A")
                                }
                                if (fallbackUrl == null) fallbackUrl = u
                            }
                        }
                        if (fallbackUrl != null) return ResolvedStream(fallbackUrl, "Mozilla/5.0", "Piped Audio")
                    }
                } catch (_: Exception) {
                }
            }

            // 4. Fallback Invidious API publik
            val invHosts = listOf("https://inv.nadeko.net", "https://invidious.nerdvpn.de")
            for (host in invHosts) {
                try {
                    val conn = URL("$host/api/v1/videos/$videoId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val formats = json.optJSONArray("adaptiveFormats") ?: continue
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val type = f.optString("type", "")
                            val u = f.optString("url", "")
                            if (type.startsWith("audio/") && u.startsWith("http")) {
                                return ResolvedStream(u, "Mozilla/5.0", "Invidious Audio")
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return null
        }

        private fun pickBestAudioFromInnerTube(json: JSONObject, ua: String, label: String): ResolvedStream? {
            val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
            var mp4Url: String? = null
            var anyAudioUrl: String? = null
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                val u = f.optString("url", "")
                if (!u.startsWith("http")) continue
                if (mime.startsWith("audio/mp4")) {
                    mp4Url = u
                    break
                }
                if (mime.startsWith("audio/") && anyAudioUrl == null) {
                    anyAudioUrl = u
                }
            }
            val chosen = mp4Url ?: anyAudioUrl ?: return null
            return ResolvedStream(chosen, ua, label)
        }

        private fun fetchSyncedLyrics(title: String, artist: String): List<LyricLine> {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat.", "ft.", "·").firstOrNull()?.trim() ?: ""
            val queries = listOf(
                "https://lrclib.net/api/get?track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}&artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}",
                "https://lrclib.net/api/search?q=${URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")}"
            )
            for (qUrl in queries) {
                try {
                    val conn = URL(qUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 7000
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
    private val mute = 0xFFAFA798.toInt()
    private val bgDark = 0xFF0B0A09.toInt()
    private val cardBg = 0xFF171512.toInt()
    private val greenOk = 0xFF52C47A.toInt()
    private val redDead = 0xFFE06C65.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = arrayOf("Cari Lagu", "Radio ID", "Murottal & Lofi", "Musik Lokal")
    private val tabViews = ArrayList<TextView>()
    private var activeTab = 0

    private lateinit var searchBarBox: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var radioBarBox: LinearLayout
    private lateinit var btnScanRadio: TextView
    private lateinit var radioScanSummary: TextView

    private lateinit var listBox: LinearLayout
    private var currentRenderedTracks: List<Track> = emptyList()
    private val radioScanMap = ConcurrentHashMap<String, RadioScanResult>()
    private var isScanningRadios = false

    private lateinit var artView: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var statusTextView: TextView
    private val eqBars = ArrayList<View>()
    private lateinit var timeLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: TextView

    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricBox: LinearLayout
    private val lyricViews = ArrayList<TextView>()
    private var lastHighlightedIdx = -1

    private lateinit var notice: Notice
    private var isSeeking = false
    private var eqPhase = 0.0

    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            animateEqualizer()
            ui.postDelayed(this, 260L)
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
            setPadding(dp(if (isPhonePortrait) 14 else 32), dp(12), dp(if (isPhonePortrait) 14 else 32), dp(12))
        }

        // Header & Tab Navigasi
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

        // Bar Pencarian Lagu YT Music (Tab 0)
        searchBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
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
        val searchBtn = tv("Cari", 12.5f, bgDark, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(16), dp(7), dp(16), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(brassLight)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (f) Color.WHITE else brassLight)
                }
            }
            setOnClickListener {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) searchYtMusic(q)
                hideKeyboard()
            }
        }
        searchBarBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchBarBox.addView(searchBtn)
        val sblp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        outer.addView(searchBarBox, sblp)

        // Bar Scan Radio Otomatis (Tab 1)
        radioBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
                setStroke(dp(1), 0x33B8975A)
            }
        }
        radioScanSummary = tv("Siap memindai stasiun radio aktif", 12.5f, mute).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        btnScanRadio = tv("Scan Radio", 12.5f, bgDark, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(brassLight)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (f) Color.WHITE else brassLight)
                }
            }
            setOnClickListener { startRadioScan() }
        }
        radioBarBox.addView(radioScanSummary, LinearLayout.LayoutParams(0, -2, 1f))
        radioBarBox.addView(btnScanRadio)
        val rblp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        outer.addView(radioBarBox, rblp)

        // Kontainer Utama: 2 Kolom di TV, Vertikal di HP
        val bodyContainer = LinearLayout(this).apply {
            orientation = if (isPhonePortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), if (isPhonePortrait) dp(2) else dp(14), dp(16))
        }
        listScroll.addView(listBox, FrameLayout.LayoutParams(-1, -2))

        val playerPanel = buildPlayerAndLyricsPanel(isPhonePortrait)

        if (isPhonePortrait) {
            bodyContainer.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1.1f))
            val plp = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) }
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
                if (!isFinishing) {
                    syncPlayerUi()
                    if (currentRenderedTracks.isNotEmpty()) {
                        renderTrackList(currentRenderedTracks)
                    }
                }
            }
        }

        selectTab(0)
        syncPlayerUi()
    }

    private fun buildPlayerAndLyricsPanel(isPhone: Boolean): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(cardBg)
                setStroke(dp(1), 0x44B8975A)
            }
        }

        // Baris Atas: Sampul + Info Lagu + Equalizer
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val artFrame = FrameLayout(this).apply {
            padding = 0
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF23201C.toInt())
                setStroke(dp(1), 0x55B8975A)
            }
            clipToOutline = true
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val artSize = dp(if (isPhone) 58 else 78)
        artFrame.addView(artView, FrameLayout.LayoutParams(-1, -1))
        topRow.addView(artFrame, LinearLayout.LayoutParams(artSize, artSize))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        nowTitle = tv("Belum ada audio diputar", if (isPhone) 15f else 17f, ivory, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowArtist = tv("Pilih lagu, radio, atau murottal", 12.5f, brassLight).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(4))
        }

        // Baris Status & Indikator Loading (Spinner + Keterangan Jelas)
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x22FFFFFF)
            }
        }
        loadingSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }
        val spinLp = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) }
        statusRow.addView(loadingSpinner, spinLp)

        statusTextView = tv("Siap memutar", 11.5f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusRow.addView(statusTextView, LinearLayout.LayoutParams(0, -2, 1f))

        infoCol.addView(nowTitle)
        infoCol.addView(nowArtist)
        infoCol.addView(statusRow)
        topRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Equalizer Mini 4 Batang
        val eqBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(2), 0)
        }
        eqBars.clear()
        for (i in 0 until 4) {
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(brassLight)
                }
            }
            val blp = LinearLayout.LayoutParams(dp(3), dp(6)).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
            eqBox.addView(bar, blp)
            eqBars.add(bar)
        }
        topRow.addView(eqBox, LinearLayout.LayoutParams(-2, dp(24)))
        panel.addView(topRow)

        // Baris Kontrol & Seekbar
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val btnPrev = controlBtn("⏮") { MusicEngine.playPrev(this) }
        btnPlayPause = controlBtn("▶", primary = true) { MusicEngine.togglePause() }
        val btnNext = controlBtn("⏭") { MusicEngine.playNext(this) }

        ctrlRow.addView(btnPrev)
        val mlp = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, dp(8), 0) }
        ctrlRow.addView(btnPlayPause, mlp)
        ctrlRow.addView(btnNext)

        timeLabel = tv("00:00 / 00:00", 11.5f, mute).apply {
            gravity = Gravity.END
            setPadding(dp(10), 0, 0, 0)
        }
        ctrlRow.addView(timeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(ctrlRow)

        seekBar = SeekBar(this).apply {
            max = 1000
            setPadding(0, dp(4), 0, dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = MusicEngine.player?.duration ?: 0
                        if (dur > 0) {
                            val target = (dur * progress) / 1000L
                            timeLabel.text = "${fmtTime(target)} / ${fmtTime(dur)}"
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeeking = false
                    val exo = MusicEngine.player ?: return
                    val dur = exo.duration
                    if (dur > 0 && sb != null) {
                        exo.seekTo((dur * sb.progress) / 1000L)
                    }
                }
            })
        }
        panel.addView(seekBar, LinearLayout.LayoutParams(-1, -2))

        // Area Lirik Karaoke Sinkron
        val lyricHeader = tv("LIRIK KARAOKE SINKRON", 10.5f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(2), 0, dp(6))
        }
        panel.addView(lyricHeader)

        lyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF0E0D0B.toInt())
                setStroke(dp(1), 0x1FFFFFFF)
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
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
        radioBarBox.visibility = if (idx == 1) View.VISIBLE else View.GONE
        currentRenderedTracks = emptyList()
        listBox.removeAllViews()

        when (idx) {
            0 -> {
                val q = searchInput.text.toString().trim()
                searchYtMusic(if (q.isNotEmpty()) q else "Lagu Pop Indonesia Terbaru")
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
            Track("rri2", "RRI Pro 2 Musik & Kreativitas", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9015/rrijakartapro2.mp3", isLive = true),
            Track("rri3", "RRI Pro 3 Berita Nasional", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9016/rrijakartapro3.mp3", isLive = true),
            Track("elshinta", "Elshinta News & Talk", "Jakarta · Berita & Lalu Lintas", "https://stream-ssl.arenastreaming.com:8000/jakarta", isLive = true),
            Track("prambors", "Prambors FM", "Hits Anak Muda Indonesia", "https://22283.live.streamtheworld.com/PRAMBORS_FMAAC.aac", isLive = true),
            Track("hardrock", "Hard Rock FM Jakarta", "Pop & Lifestyle", "https://n01.radiojar.com/7csmg90fuqruv", isLive = true),
            Track("iradio", "I-Radio Jakarta", "100% Musik Indonesia", "https://n02.radiojar.com/4ywdgup3bnzuv", isLive = true),
            Track("delta", "Delta FM", "Lagu Enak Terus", "https://s1.cloudmu.id/listen/delta_fm/radio.mp3", isLive = true)
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
                    renderTrackList(sortRadiosByScan(fetched))
                }
            }
        }.start()
    }

    private fun startRadioScan() {
        if (isScanningRadios || currentRenderedTracks.isEmpty()) return
        val targets = currentRenderedTracks.toList()
        isScanningRadios = true
        btnScanRadio.text = "Memindai..."
        radioScanSummary.text = "Mengecek ${targets.size} stasiun radio..."

        for (t in targets) {
            radioScanMap[t.id] = RadioScanResult(1, "CEK...", "Menguji koneksi")
        }
        renderTrackList(targets)

        val doneCount = AtomicInteger(0)
        val activeCount = AtomicInteger(0)
        val deadCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6)

        for (t in targets) {
            pool.execute {
                val res = probeRadioStation(t.streamUrl)
                radioScanMap[t.id] = res
                if (res.state == 2) activeCount.incrementAndGet() else deadCount.incrementAndGet()
                val done = doneCount.incrementAndGet()

                ui.post {
                    if (isFinishing) return@post
                    radioScanSummary.text = "Scan: $done/${targets.size} (${activeCount.get()} aktif · ${deadCount.get()} mati)"
                    if (done >= targets.size) {
                        isScanningRadios = false
                        btnScanRadio.text = "Scan Ulang"
                        radioScanSummary.text = "Selesai: ${activeCount.get()} Aktif · ${deadCount.get()} Mati"
                        if (activeTab == 1) {
                            renderTrackList(sortRadiosByScan(targets))
                        }
                    } else if (activeTab == 1 && done % 3 == 0) {
                        renderTrackList(targets)
                    }
                }
            }
        }
        pool.shutdown()
    }

    private fun sortRadiosByScan(list: List<Track>): List<Track> {
        return list.sortedBy { t ->
            when (radioScanMap[t.id]?.state) {
                2 -> 0 // AKTIF paling atas
                1, null -> 1 // Belum dicek di tengah
                else -> 2 // MATI di paling bawah
            }
        }
    }

    private fun probeRadioStation(rawUrl: String): RadioScanResult {
        var cur = rawUrl.trim()
        if (!cur.startsWith("http", ignoreCase = true)) {
            return RadioScanResult(3, "MATI", "Alamat URL tidak valid")
        }
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        for (hop in 0..3) {
            try {
                val conn = URL(cur).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4500
                conn.readTimeout = 4500
                conn.setRequestProperty("User-Agent", ua)
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location") ?: run {
                        conn.disconnect()
                        return RadioScanResult(3, "MATI", "Redirect kosong")
                    }
                    cur = URL(URL(cur), loc).toString()
                    conn.disconnect()
                    continue
                }
                if (code in 200..299) {
                    val ctype = (conn.contentType ?: "").lowercase(Locale.US)
                    val buf = ByteArray(256)
                    val readBytes = try {
                        conn.inputStream.use { it.read(buf) }
                    } catch (_: Exception) {
                        -1
                    }
                    conn.disconnect()
                    if (readBytes <= 0) {
                        return RadioScanResult(3, "MATI", "Tidak ada data audio")
                    }
                    if (ctype.contains("text/html")) {
                        return RadioScanResult(3, "MATI", "Server mengembalikan halaman HTML")
                    }
                    return RadioScanResult(2, "AKTIF", "Siap diputar")
                } else {
                    conn.disconnect()
                    return RadioScanResult(3, "MATI", "HTTP $code")
                }
            } catch (_: Exception) {
                return RadioScanResult(3, "MATI", "Server timeout / offline")
            }
        }
        return RadioScanResult(3, "MATI", "Terlalu banyak pengalihan")
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
        currentRenderedTracks = items
        listBox.removeAllViews()
        val curId = MusicEngine.currentTrack?.id

        for ((index, item) in items.withIndex()) {
            val isPlayingThis = item.id == curId
            val scanInfo = if (activeTab == 1) radioScanMap[item.id] else null

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isPlayingThis) 0x2AB8975A else 0xFF151311.toInt())
                    setStroke(dp(1), if (isPlayingThis) brass else 0x22FFFFFF)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (f) 0x3AB8975A else if (isPlayingThis) 0x2AB8975A else 0xFF151311.toInt())
                        setStroke(dp(if (f) 2 else 1), if (f) brassLight else if (isPlayingThis) brass else 0x22FFFFFF)
                    }
                }
                setOnClickListener {
                    MusicEngine.playTrack(this@RadioMusicActivity, item, items, index)
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
                setPadding(dp(11), 0, dp(8), 0)
            }
            col.addView(tv(item.title, 13.5f, if (isPlayingThis) brassLight else ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val subText = if (scanInfo != null && scanInfo.state == 3) {
                "${item.artist} · ${scanInfo.detail}"
            } else {
                item.artist
            }
            col.addView(tv(subText, 11.5f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            // Pill Status Radio (AKTIF / MATI / CEK) atau Status Diputar
            if (isPlayingThis) {
                val playingPill = tv("DIPUTAR", 9.5f, bgDark, Typeface.DEFAULT_BOLD).apply {
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(brassLight)
                    }
                }
                row.addView(playingPill)
            } else if (scanInfo != null && scanInfo.state != 0) {
                val pillColor = when (scanInfo.state) {
                    2 -> greenOk
                    3 -> redDead
                    else -> brassLight
                }
                val scanPill = tv("● ${scanInfo.label}", 9.5f, pillColor, Typeface.DEFAULT_BOLD).apply {
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor((pillColor and 0x00FFFFFF) or 0x24000000)
                    }
                }
                row.addView(scanPill)
            }

            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            listBox.addView(row, lp)
        }
    }

    private fun syncPlayerUi() {
        val t = MusicEngine.currentTrack
        val st = MusicEngine.state

        if (t == null) {
            nowTitle.text = "Belum ada audio diputar"
            nowArtist.text = "Pilih lagu, radio, atau murottal"
            btnPlayPause.text = "▶"
        } else {
            nowTitle.text = t.title
            nowArtist.text = t.artist
            Images.load(t.thumbUrl, artView)
            btnPlayPause.text = if (st == PlayState.PLAYING) "⏸" else "▶"
        }

        // Atur Spinner Loading & Keterangan Status Jelas
        val isBusy = (st == PlayState.RESOLVING || st == PlayState.BUFFERING)
        loadingSpinner.visibility = if (isBusy) View.VISIBLE else View.GONE
        statusTextView.text = MusicEngine.statusDetail

        val pillBgColor = when (st) {
            PlayState.PLAYING -> 0x2852C47A
            PlayState.ERROR -> 0x33E06C65
            PlayState.RESOLVING, PlayState.BUFFERING -> 0x33B8975A
            else -> 0x1EFFFFFF
        }
        val pillTextColor = when (st) {
            PlayState.PLAYING -> greenOk
            PlayState.ERROR -> redDead
            PlayState.RESOLVING, PlayState.BUFFERING -> brassLight
            else -> mute
        }
        statusTextView.setTextColor(pillTextColor)
        statusRow.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(pillBgColor)
        }

        rebuildLyricViews()
    }

    private fun animateEqualizer() {
        if (eqBars.isEmpty()) return
        val active = MusicEngine.state == PlayState.PLAYING
        if (!active) {
            for (bar in eqBars) {
                val lp = bar.layoutParams
                if (lp.height != dp(5)) {
                    lp.height = dp(5)
                    bar.layoutParams = lp
                }
            }
            return
        }
        eqPhase += 0.85
        for (i in eqBars.indices) {
            val wave = (sin(eqPhase + i * 1.4) + 1.0) * 0.5
            val h = dp(5 + (wave * 17).toInt())
            val lp = eqBars[i].layoutParams
            lp.height = h
            eqBars[i].layoutParams = lp
        }
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
        val exo = MusicEngine.player ?: return
        val track = MusicEngine.currentTrack ?: return
        try {
            if (track.isLive) {
                timeLabel.text = "LIVE · HEMAT KUOTA"
                seekBar.isEnabled = false
                seekBar.progress = 1000
                return
            }
            seekBar.isEnabled = true
            val pos = exo.currentPosition
            val dur = exo.duration
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
