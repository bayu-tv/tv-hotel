package com.bayutv.hotel

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.random.Random

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

    data class AlbumItem(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val thumbUrl: String?
    )

    data class UserPlaylist(
        val name: String,
        val tracks: MutableList<Track>,
        val isGoogleSynced: Boolean = false,
        val browseId: String = ""
    )

    data class LyricLine(val timeMs: Long, val text: String)

    data class ResolvedStream(
        val url: String,
        val userAgent: String,
        val sourceLabel: String,
        val cookie: String = ""
    )

    data class RadioScanResult(
        val state: Int, // 0 = belum dicek, 1 = mengecek, 2 = aktif, 3 = mati
        val label: String,
        val detail: String
    )

    enum class PlayState {
        IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ERROR
    }

    // Autentikasi Sesi Akun Google untuk YouTube Music (SAPISIDHASH)
    object GoogleMusicAuth {
        private const val YTM_ORIGIN = "https://music.youtube.com"
        const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun getCookie(): String {
            return try {
                CookieManager.getInstance().getCookie(YTM_ORIGIN) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        fun isLoggedIn(): Boolean {
            val c = getCookie()
            return c.contains("SAPISID=") || c.contains("__Secure-3PAPISID=")
        }

        fun buildSapisidHashAuth(): String? {
            val cookie = getCookie()
            if (cookie.isBlank()) return null
            val parts = cookie.split(";")
            var sapisid = ""
            for (p in parts) {
                val kv = p.trim()
                if (kv.startsWith("SAPISID=")) {
                    sapisid = kv.substringAfter("=")
                    break
                }
                if (kv.startsWith("__Secure-3PAPISID=") && sapisid.isEmpty()) {
                    sapisid = kv.substringAfter("=")
                }
            }
            if (sapisid.isBlank()) return null
            return try {
                val ts = System.currentTimeMillis() / 1000L
                val raw = "$ts $sapisid $YTM_ORIGIN"
                val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                "SAPISIDHASH ${ts}_$hex"
            } catch (_: Exception) {
                null
            }
        }

        fun logout() {
            try {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            } catch (_: Exception) {
            }
        }
    }

    // Implementasi Downloader HTTP bawaan untuk NewPipeExtractor
    class NativeHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val targetUrl = URL(request.url())
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
            }

            for ((key, values) in request.headers()) {
                for (value in values) {
                    conn.addRequestProperty(key, value)
                }
            }

            val cookie = GoogleMusicAuth.getCookie()
            if (cookie.isNotBlank() && request.url().contains("youtube.com", ignoreCase = true)) {
                conn.setRequestProperty("Cookie", cookie)
            }

            val payload = request.dataToSend()
            if (payload != null && payload.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(payload) }
            }

            val code = conn.responseCode
            val message = conn.responseMessage ?: ""
            val inStream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = inStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            val respHeaders = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null) {
                    respHeaders[k] = v
                }
            }

            val latestUrl = conn.url?.toString() ?: request.url()
            conn.disconnect()
            return Response(code, message, respHeaders, body, latestUrl)
        }
    }

    // Penyimpanan Playlist Lokal & Sinkronisasi Playlist/Album YouTube Music
    object PlaylistStore {
        private const val PREF_NAME = "bayutv_playlists"
        private const val KEY_DATA = "playlists_json"

        fun getLocalPlaylists(ctx: Context): MutableList<UserPlaylist> {
            val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DATA, null)
            val result = ArrayList<UserPlaylist>()
            if (!raw.isNullOrBlank()) {
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name", "Playlist ${i + 1}")
                        val tArr = obj.optJSONArray("tracks") ?: JSONArray()
                        val tracks = ArrayList<Track>()
                        for (j in 0 until tArr.length()) {
                            val to = tArr.getJSONObject(j)
                            tracks.add(
                                Track(
                                    id = to.optString("id", ""),
                                    title = to.optString("title", "Tanpa Judul"),
                                    artist = to.optString("artist", ""),
                                    streamUrl = to.optString("streamUrl", ""),
                                    thumbUrl = to.optString("thumbUrl", "").ifBlank { null },
                                    isLive = to.optBoolean("isLive", false),
                                    isYtMusic = to.optBoolean("isYtMusic", false)
                                )
                            )
                        }
                        result.add(UserPlaylist(name, tracks))
                    }
                } catch (_: Exception) {
                }
            }
            if (result.isEmpty()) {
                result.add(UserPlaylist("Favorit Saya", ArrayList()))
                saveLocalPlaylists(ctx, result)
            }
            return result
        }

        fun saveLocalPlaylists(ctx: Context, list: List<UserPlaylist>) {
            val arr = JSONArray()
            for (pl in list) {
                if (pl.isGoogleSynced) continue
                val obj = JSONObject()
                obj.put("name", pl.name)
                val tArr = JSONArray()
                for (t in pl.tracks) {
                    val to = JSONObject()
                    to.put("id", t.id)
                    to.put("title", t.title)
                    to.put("artist", t.artist)
                    to.put("streamUrl", t.streamUrl)
                    to.put("thumbUrl", t.thumbUrl ?: "")
                    to.put("isLive", t.isLive)
                    to.put("isYtMusic", t.isYtMusic)
                    tArr.put(to)
                }
                obj.put("tracks", tArr)
                arr.put(obj)
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        }

        fun createPlaylist(ctx: Context, name: String): Int {
            val all = getLocalPlaylists(ctx)
            all.add(UserPlaylist(name.trim(), ArrayList()))
            saveLocalPlaylists(ctx, all)
            return all.lastIndex
        }

        fun deletePlaylist(ctx: Context, index: Int) {
            val all = getLocalPlaylists(ctx)
            if (index in all.indices) {
                all.removeAt(index)
                saveLocalPlaylists(ctx, all)
            }
        }

        fun addTrackToPlaylist(ctx: Context, playlistIndex: Int, track: Track): Boolean {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex !in all.indices) return false
            val target = all[playlistIndex]
            if (target.tracks.any { it.id == track.id && it.title == track.title }) {
                return false
            }
            target.tracks.add(track)
            saveLocalPlaylists(ctx, all)
            return true
        }

        fun addManyTracksToPlaylist(ctx: Context, playlistIndex: Int, newTracks: List<Track>): Int {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex !in all.indices) return 0
            val target = all[playlistIndex]
            var addedCount = 0
            for (t in newTracks) {
                if (target.tracks.none { it.id == t.id && it.title == t.title }) {
                    target.tracks.add(t)
                    addedCount++
                }
            }
            if (addedCount > 0) saveLocalPlaylists(ctx, all)
            return addedCount
        }

        fun removeTrackFromPlaylist(ctx: Context, playlistIndex: Int, trackIndex: Int) {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex in all.indices && trackIndex in all[playlistIndex].tracks.indices) {
                all[playlistIndex].tracks.removeAt(trackIndex)
                saveLocalPlaylists(ctx, all)
            }
        }

        fun fetchGoogleAccountPlaylists(): List<UserPlaylist> {
            val auth = GoogleMusicAuth.buildSapisidHashAuth() ?: return emptyList()
            val cookie = GoogleMusicAuth.getCookie()
            val found = ArrayList<UserPlaylist>()
            found.add(UserPlaylist("★ Lagu Disukai (Google)", ArrayList(), isGoogleSynced = true, browseId = "VLLM"))

            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", auth)
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("X-Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)

                val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"browseId":"FEmusic_liked_playlists"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractGooglePlaylists(json, found)
                }
            } catch (_: Exception) {
            }
            return found
        }

        private fun extractGooglePlaylists(node: Any?, out: MutableList<UserPlaylist>) {
            if (node == null) return
            when (node) {
                is JSONObject -> {
                    val twoRow = node.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val browseId = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId", "") ?: ""
                        val runs = twoRow.optJSONObject("title")?.optJSONArray("runs")
                        val title = if (runs != null && runs.length() > 0) runs.getJSONObject(0).optString("text", "") else ""
                        if (browseId.startsWith("VL") && browseId != "VLLM" && title.isNotBlank() && out.none { it.browseId == browseId }) {
                            out.add(UserPlaylist("☁ $title", ArrayList(), isGoogleSynced = true, browseId = browseId))
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) extractGooglePlaylists(node.opt(keys.next()), out)
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) extractGooglePlaylists(node.opt(i), out)
                }
            }
        }

        fun fetchBrowseTracks(
            browseId: String,
            fallbackArtist: String = "",
            fallbackThumb: String? = null
        ): List<Track> {
            val auth = GoogleMusicAuth.buildSapisidHashAuth()
            val cookie = GoogleMusicAuth.getCookie()
            val tracks = ArrayList<Track>()
            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("X-Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
                if (auth != null) {
                    conn.setRequestProperty("Authorization", auth)
                    conn.setRequestProperty("Cookie", cookie)
                }

                val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"browseId":"$browseId"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractTracksFromBrowse(json, tracks, fallbackArtist, fallbackThumb)
                }
            } catch (_: Exception) {
            }
            return tracks
        }

        private fun extractTracksFromBrowse(
            node: Any?,
            out: MutableList<Track>,
            fallbackArtist: String,
            fallbackThumb: String?
        ) {
            if (out.size >= 80 || node == null) return
            when (node) {
                is JSONObject -> {
                    val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                    if (renderer != null) {
                        val vid = renderer.optJSONObject("playlistItemData")?.optString("videoId", "")
                            ?: renderer.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId", "") ?: ""

                        val cols = renderer.optJSONArray("flexColumns")
                        var title = ""
                        var artist = ""
                        if (cols != null && cols.length() > 0) {
                            title = readRuns(cols.getJSONObject(0))
                            if (cols.length() > 1) artist = readRuns(cols.getJSONObject(1))
                        }
                        if (vid.isNotEmpty() && title.isNotEmpty() && out.none { it.id == vid }) {
                            val resolvedArtist = if (artist.isNotBlank() && !artist.matches(Regex("""\d+:\d+"""))) {
                                artist
                            } else {
                                fallbackArtist.ifBlank { "YouTube Music" }
                            }
                            out.add(
                                Track(
                                    id = vid,
                                    title = title,
                                    artist = resolvedArtist,
                                    thumbUrl = fallbackThumb ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                    isLive = false,
                                    isYtMusic = true
                                )
                            )
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        extractTracksFromBrowse(node.opt(keys.next()), out, fallbackArtist, fallbackThumb)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        extractTracksFromBrowse(node.opt(i), out, fallbackArtist, fallbackThumb)
                    }
                }
            }
        }

        private fun readRuns(obj: JSONObject?): String {
            val runs = obj?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.getJSONObject(i).optString("text", ""))
            return sb.toString().trim()
        }
    }

    // Mesin pemutar global berbasis Media3 ExoPlayer + NewPipeExtractor
    object MusicEngine {
        private const val WEB_UA = GoogleMusicAuth.CHROME_UA
        private val mainHandler = Handler(Looper.getMainLooper())
        var player: ExoPlayer? = null
        var currentTrack: Track? = null
        var queue: List<Track> = emptyList()
        var queueIndex: Int = -1
        var state: PlayState = PlayState.IDLE
        var statusDetail: String = "Pilih lagu, album, atau radio untuk mulai"
        var lyrics: List<LyricLine> = emptyList()
        var lyricStatus: String = "Pilih lagu untuk melihat lirik karaoke"
        var lyricsEnabled: Boolean = false
        var shuffleEnabled: Boolean = false
        var repeatMode: Int = 0 // 0 = Normal, 1 = Ulang Semua, 2 = Ulang 1 Lagu
        var onStateChanged: (() -> Unit)? = null

        private var playSeq = 0
        private var newPipeInitialized = false

        @Synchronized
        private fun ensureNewPipeInit() {
            if (!newPipeInitialized) {
                NewPipe.init(NativeHttpDownloader())
                newPipeInitialized = true
            }
        }

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
                track.isYtMusic -> "Mendekripsi aliran audio YouTube..."
                track.isLive -> "Menghubungkan ke server siaran..."
                else -> "Membuka file audio..."
            }
            lyrics = emptyList()
            lyricStatus = when {
                !lyricsEnabled -> "Lirik dinonaktifkan"
                track.isLive -> "Siaran Langsung · Lirik tidak tersedia"
                else -> "Mencari lirik karaoke..."
            }
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
                        track.isYtMusic && track.streamUrl.isEmpty() -> resolveViaNewPipe(track)
                        track.isLive -> resolveRadioUrl(track.streamUrl)
                        else -> ResolvedStream(track.streamUrl, WEB_UA, "Lokal")
                    }

                    if (seq != playSeq) return@Thread

                    if (resolved == null || resolved.url.isBlank()) {
                        state = PlayState.ERROR
                        if (statusDetail.startsWith("Mendekripsi")) {
                            statusDetail = "Gagal memuat audio lagu ini. Coba lagu lain."
                        }
                        lyricStatus = statusDetail
                        notifyUi()
                        return@Thread
                    }

                    if (!track.isLive && lyricsEnabled) {
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
                } catch (e: Exception) {
                    if (seq == playSeq) {
                        state = PlayState.ERROR
                        statusDetail = "Gagal memuat audio (${e.javaClass.simpleName})"
                        lyricStatus = statusDetail
                        notifyUi()
                    }
                }
            }.start()
        }

        private fun resolveViaNewPipe(track: Track): ResolvedStream? {
            ensureNewPipeInit()

            extractSingleVideoWithNewPipe(track.id)?.let { return it }

            statusDetail = "Mencari versi video musik alternatif..."
            notifyUi()
            val cleanArtist = track.artist.split("•", "·", ",").firstOrNull()?.trim() ?: ""
            val altId = searchFallbackVideoId("${track.title} $cleanArtist", track.id)
            if (altId != null) {
                extractSingleVideoWithNewPipe(altId)?.let { alt ->
                    return ResolvedStream(alt.url, alt.userAgent, "${alt.sourceLabel} (Alt)", alt.cookie)
                }
            }
            return null
        }

        private fun extractSingleVideoWithNewPipe(videoId: String): ResolvedStream? {
            return try {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()

                val audioStreams = extractor.audioStreams
                val bestM4a = audioStreams
                    .filter { it.format == MediaFormat.M4A && it.content.startsWith("http") }
                    .maxByOrNull { it.averageBitrate }
                val anyAudio = audioStreams
                    .filter { it.content.startsWith("http") }
                    .maxByOrNull { it.averageBitrate }

                val chosenAudio = bestM4a ?: anyAudio
                if (chosenAudio != null) {
                    val kbps = if (chosenAudio.averageBitrate > 0) "${chosenAudio.averageBitrate}kbps" else "HQ"
                    return ResolvedStream(
                        url = chosenAudio.content,
                        userAgent = WEB_UA,
                        sourceLabel = "YT Audio $kbps",
                        cookie = GoogleMusicAuth.getCookie()
                    )
                }

                val muxed = extractor.videoStreams.firstOrNull { it.content.startsWith("http") }
                if (muxed != null) {
                    return ResolvedStream(
                        url = muxed.content,
                        userAgent = WEB_UA,
                        sourceLabel = "YT Stream",
                        cookie = GoogleMusicAuth.getCookie()
                    )
                }
                null
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                statusDetail = "YT: ${msg.take(48)}"
                null
            }
        }

        private fun searchFallbackVideoId(query: String, excludeId: String): String? {
            return try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()
                val items = searchExtractor.initialPage.items
                for (item in items) {
                    val u = item.url ?: continue
                    val vid = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(u)?.groupValues?.getOrNull(1)
                    if (vid != null && vid != excludeId) return vid
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        fun refreshLyricsIfNeeded() {
            val track = currentTrack ?: return
            if (!lyricsEnabled || track.isLive || lyrics.isNotEmpty()) return
            lyricStatus = "Mencari lirik karaoke..."
            notifyUi()
            val seq = playSeq
            Thread {
                val fetched = fetchSyncedLyrics(track.title, track.artist)
                if (seq == playSeq) {
                    lyrics = fetched
                    lyricStatus = if (fetched.isEmpty()) "Lirik sinkron tidak tersedia untuk lagu ini" else ""
                    notifyUi()
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
                if (resolved.cookie.isNotBlank()) {
                    dsf.setDefaultRequestProperties(mapOf("Cookie" to resolved.cookie))
                }
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
                            if (currentTrack?.isLive != true) {
                                if (repeatMode == 2) {
                                    exo.seekTo(0)
                                    exo.playWhenReady = true
                                } else {
                                    playNext(appCtx, autoAdvance = true)
                                }
                            }
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
                        else -> "Stream audio gagal diputar (${error.errorCodeName})"
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

        fun playNext(ctx: Context, autoAdvance: Boolean = false) {
            if (queue.isEmpty()) return
            if (shuffleEnabled && queue.size > 1) {
                var rnd = Random.nextInt(queue.size)
                if (rnd == queueIndex) rnd = (rnd + 1) % queue.size
                playTrack(ctx, queue[rnd], queue, rnd)
                return
            }
            val next = queueIndex + 1
            if (next >= queue.size) {
                if (autoAdvance && repeatMode == 0) {
                    state = PlayState.PAUSED
                    statusDetail = "Daftar putar selesai"
                    notifyUi()
                    return
                }
                playTrack(ctx, queue[0], queue, 0)
            } else {
                playTrack(ctx, queue[next], queue, next)
            }
        }

        fun playPrev(ctx: Context) {
            if (queue.isEmpty()) return
            val prev = if (queueIndex - 1 < 0) queue.size - 1 else queueIndex - 1
            playTrack(ctx, queue[prev], queue, prev)
        }

        private fun resolveRadioUrl(rawUrl: String): ResolvedStream? {
            var cur = rawUrl.trim()
            if (!cur.startsWith("http", ignoreCase = true)) return null

            for (hop in 0..4) {
                try {
                    val conn = URL(cur).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("User-Agent", WEB_UA)
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
                    return ResolvedStream(cur, WEB_UA, fmt)
                } catch (_: Exception) {
                    return null
                }
            }
            return ResolvedStream(cur, WEB_UA, "Stream Live")
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

        private fun fetchSyncedLyrics(title: String, artist: String): List<LyricLine> {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat.", "ft.", "·", "•").firstOrNull()?.trim() ?: ""
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

    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = arrayOf("Cari Lagu", "Playlist Saya", "Radio ID", "Murottal & Lofi", "Musik Lokal")
    private val tabViews = ArrayList<TextView>()
    private var activeTab = 0

    // Klasifikasi Pencarian & Genre (Tab 0)
    private val searchCategories = listOf(
        Pair("Semua Lagu", ""),
        Pair("Album", "ALBUM"),
        Pair("Pop Indo", "Top Hits Pop Indonesia Terbaru"),
        Pair("Lagu Barat", "Top Global Pop Hits Western Songs"),
        Pair("K-Pop & J-Pop", "Top K-Pop and Japanese Hits"),
        Pair("Rock & Jazz", "Classic Rock and Smooth Jazz Hits")
    )
    private val categoryViews = ArrayList<TextView>()
    private var activeCategoryIdx = 0
    private var lastAlbumSearchQuery = ""
    private var lastFoundAlbums: List<AlbumItem> = emptyList()

    private var combinedPlaylists: List<UserPlaylist> = emptyList()
    private var selectedPlaylistIdx = 0

    // Browse Screen Views
    private lateinit var browseContainer: LinearLayout
    private lateinit var btnGoogleAccount: TextView
    private lateinit var searchSectionBox: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var playlistBarBox: LinearLayout
    private lateinit var playlistChipRow: LinearLayout

    private lateinit var radioBarBox: LinearLayout
    private lateinit var btnScanRadio: TextView
    private lateinit var radioScanSummary: TextView

    private lateinit var listBox: LinearLayout
    private var currentRenderedTracks: List<Track> = emptyList()
    private val radioScanMap = ConcurrentHashMap<String, RadioScanResult>()
    private var isScanningRadios = false

    // Spotify Mini Player Bar (Bottom of Browse Screen)
    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var miniProgressBar: ProgressBar
    private lateinit var miniArtView: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniBtnPlayPause: TextView

    // Spotify Full-Screen Now Playing Overlay
    private var isFullPlayerOpen = false
    private lateinit var fullPlayerOverlay: LinearLayout
    private lateinit var fullBtnToggleLyrics: TextView
    private lateinit var fullCoverSection: LinearLayout
    private lateinit var fullArtView: ImageView
    private lateinit var fullNowTitle: TextView
    private lateinit var fullNowArtist: TextView
    private lateinit var fullStatusRow: LinearLayout
    private lateinit var fullSpinner: ProgressBar
    private lateinit var fullStatusText: TextView
    private val eqBars = ArrayList<View>()

    // Header Kompak di Dalam Full Player Saat Lirik Aktif
    private lateinit var fullLyricsSection: LinearLayout
    private lateinit var lyricMiniArt: ImageView
    private lateinit var lyricMiniTitle: TextView
    private lateinit var lyricMiniSub: TextView
    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricBox: LinearLayout
    private val lyricViews = ArrayList<TextView>()
    private var lastHighlightedIdx = -1

    // Kontrol Bawah Full Player
    private lateinit var fullQualityLabel: TextView
    private lateinit var fullTimeLabel: TextView
    private lateinit var fullSeekBar: SeekBar
    private lateinit var fullBtnShuffle: TextView
    private lateinit var fullBtnPlayPause: TextView
    private lateinit var fullBtnRepeat: TextView

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

        MusicEngine.lyricsEnabled = prefs.getBoolean("music_lyrics_enabled", false)

        val isPhonePortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600
        val padH = dp(if (isPhonePortrait) 14 else 28)

        val root = FrameLayout(this).apply { setBackgroundColor(bgDark) }

        // =========================================================================
        // 1. HALAMAN UTAMA (JELAJAH LAGU, ALBUM, PLAYLIST, RADIO + MINI PLAYER)
        // =========================================================================
        browseContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, dp(10), padH, dp(10))
        }

        // Baris Header 1: Logo Brand & Login Google (Tidak lagi menghimpit tab!)
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val brand = tv("BAYU AUDIO", 18f, brassLight, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.1f
        }
        brandRow.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        btnGoogleAccount = actionPillBtn(if (GoogleMusicAuth.isLoggedIn()) "● Google Aktif" else "Login Google") {
            handleGoogleAccountButton()
        }
        brandRow.addView(btnGoogleAccount)
        browseContainer.addView(brandRow)

        // Baris Header 2: Bar Tab Penuh Selebar Layar
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in tabs.indices) {
            val t = tv(tabs[i], 13f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f -> styleTab(this, i, f) }
                setOnClickListener { selectTab(i) }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            tabViews.add(t)
        }
        tabScroll.addView(tabRow)
        browseContainer.addView(tabScroll)

        // Sub-bar Tab 0: Kotak Pencarian + Klasifikasi Genre/Album
        searchSectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchBarBox = LinearLayout(this).apply {
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
            hint = "Cari lagu, artis, atau album (Indo & Barat)..."
            setHintTextColor(0xFF8C8578.toInt())
            setTextColor(ivory)
            textSize = 14f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    executeSearchWithCategory()
                    hideKeyboard()
                    true
                } else false
            }
        }
        val searchBtn = actionPillBtn("Cari", primary = true) {
            executeSearchWithCategory()
            hideKeyboard()
        }
        searchBarBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchBarBox.addView(searchBtn)
        searchSectionBox.addView(searchBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Deretan Chip Klasifikasi (Semua Lagu, Album, Pop Indo, Lagu Barat, K-Pop, Rock & Jazz)
        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryViews.clear()
        for (i in searchCategories.indices) {
            val chip = tv(searchCategories[i].first, 11.5f, mute, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f -> styleCategoryChip(this, i, f) }
                setOnClickListener { selectCategory(i) }
            }
            catRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            categoryViews.add(chip)
        }
        catScroll.addView(catRow)
        searchSectionBox.addView(catScroll)
        browseContainer.addView(searchSectionBox)

        // Sub-bar Tab 1: Manajemen Playlist
        playlistBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(cardBg)
                setStroke(dp(1), 0x33B8975A)
            }
        }
        val plScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        playlistChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        plScroll.addView(playlistChipRow)
        playlistBarBox.addView(plScroll, LinearLayout.LayoutParams(0, -2, 1f))

        val btnNewPl = actionPillBtn("+ Playlist", primary = true) { promptCreatePlaylist() }
        val btnDelPl = actionPillBtn("Hapus") { promptDeleteCurrentPlaylist() }
        playlistBarBox.addView(btnNewPl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        playlistBarBox.addView(btnDelPl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        browseContainer.addView(playlistBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Sub-bar Tab 2: Scan Radio Otomatis
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
        btnScanRadio = actionPillBtn("Scan Radio", primary = true) { startRadioScan() }
        radioBarBox.addView(radioScanSummary, LinearLayout.LayoutParams(0, -2, 1f))
        radioBarBox.addView(btnScanRadio)
        browseContainer.addView(radioBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Daftar Lagu / Album / Radio Penuh dari Atas ke Bawah
        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(12))
        }
        listScroll.addView(listBox, FrameLayout.LayoutParams(-1, -2))
        browseContainer.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bilah Mini Player Minimalis ala Spotify di Bawah Layar
        miniPlayerBar = buildSpotifyMiniPlayer()
        browseContainer.addView(miniPlayerBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        root.addView(browseContainer, FrameLayout.LayoutParams(-1, -1))

        // =========================================================================
        // 2. HALAMAN PEMUTAR LAYAR PENUH (FULL-SCREEN NOW PLAYING & LIRIK)
        // =========================================================================
        fullPlayerOverlay = buildSpotifyFullScreenPlayer(isPhonePortrait)
        fullPlayerOverlay.visibility = View.GONE
        root.addView(fullPlayerOverlay, FrameLayout.LayoutParams(-1, -1))

        notice = Notice(this, root)
        setContentView(root)

        // Tombol Back memperkecil pemutar penuh ke Mini Player terlebih dahulu
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullPlayerOpen) {
                    closeFullPlayer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        MusicEngine.onStateChanged = {
            ui.post {
                if (!isFinishing) {
                    syncAllPlayerViews()
                    if (currentRenderedTracks.isNotEmpty()) {
                        val isLocalPl = (activeTab == 1 && combinedPlaylists.getOrNull(selectedPlaylistIdx)?.isGoogleSynced == false)
                        renderTrackList(currentRenderedTracks, isLocalPlaylistTab = isLocalPl, openFullOnPlay = true)
                    }
                }
            }
        }

        refreshGoogleAccountButton()
        selectCategory(0, triggerSearch = false)
        selectTab(0)
        syncAllPlayerViews()
    }

    // =========================================================================
    // MINI PLAYER ALA SPOTIFY (MINIMALIS DI BAWAH)
    // =========================================================================
    private fun buildSpotifyMiniPlayer(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF231E18.toInt(), 0xFF191612.toInt())
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x66B8975A)
            }
            clipToOutline = true
        }

        // Garis progres tipis di bagian atas Mini Player
        miniProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(brassLight)
            progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
        }
        wrapper.addView(miniProgressBar, LinearLayout.LayoutParams(-1, dp(3)))

        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // Area kiri-tengah: Ketuk untuk memperbesar ke Layar Penuh
        val expandArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setOnClickListener { openFullPlayer() }
        }

        val artBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF2B261F.toInt())
                setStroke(dp(1), 0x55B8975A)
            }
            clipToOutline = true
        }
        miniArtView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(miniArtView, FrameLayout.LayoutParams(-1, -1))
        expandArea.addView(artBox, LinearLayout.LayoutParams(dp(44), dp(44)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        miniTitle = tv("Belum ada lagu", 13.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniSubtitle = tv("Ketuk untuk layar penuh & lirik ▲", 11f, brassLight).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(miniTitle)
        textCol.addView(miniSubtitle)
        expandArea.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        contentRow.addView(expandArea, LinearLayout.LayoutParams(0, -2, 1f))

        // Tombol kontrol cepat di sisi kanan Mini Player
        val btnMiniPrev = miniIconBtn("⏮") { MusicEngine.playPrev(this) }
        miniBtnPlayPause = miniIconBtn("▶", primary = true) { MusicEngine.togglePause() }
        val btnMiniNext = miniIconBtn("⏭") { MusicEngine.playNext(this) }

        contentRow.addView(btnMiniPrev)
        contentRow.addView(miniBtnPlayPause, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        contentRow.addView(btnMiniNext)

        wrapper.addView(contentRow)
        return wrapper
    }

    private fun miniIconBtn(symbol: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        return tv(symbol, 14f, if (primary) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (primary) brassLight else 0x22FFFFFF)
            }
            setOnClickListener { onClick() }
        }
    }

    // =========================================================================
    // HALAMAN PEMUTAR LAYAR PENUH ALA SPOTIFY (FULL-SCREEN + LIRIK BESAR)
    // =========================================================================
    private fun buildSpotifyFullScreenPlayer(isPhone: Boolean): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPhone) 18 else 36), dp(14), dp(if (isPhone) 18 else 36), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF241E17.toInt(), 0xFF12100D.toInt(), 0xFF0A0908.toInt())
            )
            isClickable = true
        }

        // Baris Atas Full Player: Perkecil (Minimize) | Status | Lirik ON/OFF | + Playlist
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val btnMinimize = actionPillBtn("⌄ Perkecil") {
            closeFullPlayer()
        }
        topHeader.addView(btnMinimize)

        val nowHeaderLabel = tv("PEMUTAR PENUH", 10.5f, mute, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
        }
        topHeader.addView(nowHeaderLabel, LinearLayout.LayoutParams(0, -2, 1f))

        fullBtnToggleLyrics = actionPillBtn(if (MusicEngine.lyricsEnabled) "Lirik: AKTIF" else "Lirik: NONAKTIF") {
            MusicEngine.lyricsEnabled = !MusicEngine.lyricsEnabled
            prefs.edit().putBoolean("music_lyrics_enabled", MusicEngine.lyricsEnabled).apply()
            if (MusicEngine.lyricsEnabled) {
                MusicEngine.refreshLyricsIfNeeded()
            }
            syncAllPlayerViews()
        }
        topHeader.addView(fullBtnToggleLyrics)

        val btnSaveFull = actionPillBtn("+ Playlist") {
            val cur = MusicEngine.currentTrack
            if (cur != null) showAddToPlaylistDialog(cur)
        }
        topHeader.addView(btnSaveFull, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        page.addView(topHeader)

        // ---------------------------------------------------------------------
        // MODE A: TAMPILAN SAMPUL ALBUM BESAR (SAAT LIRIK NONAKTIF)
        // ---------------------------------------------------------------------
        fullCoverSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val artSize = dp(if (isPhone) 220 else 230)
        val bigArtFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF26211A.toInt())
                setStroke(dp(2), 0x88B8975A)
            }
            clipToOutline = true
        }
        fullArtView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        bigArtFrame.addView(fullArtView, FrameLayout.LayoutParams(-1, -1))
        fullCoverSection.addView(bigArtFrame, LinearLayout.LayoutParams(artSize, artSize).apply {
            bottomMargin = dp(18)
        })

        fullNowTitle = tv("Belum ada lagu diputar", if (isPhone) 20f else 22f, ivory, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fullNowArtist = tv("Pilih lagu atau album", 14.5f, brassLight).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
        }
        fullCoverSection.addView(fullNowTitle, LinearLayout.LayoutParams(-1, -2))
        fullCoverSection.addView(fullNowArtist, LinearLayout.LayoutParams(-1, -2))

        // Pill Status & Spinner & Equalizer
        fullStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x22FFFFFF)
            }
        }
        fullSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }
        fullStatusRow.addView(fullSpinner, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) })

        fullStatusText = tv("Siap memutar", 12f, mute, Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        fullStatusRow.addView(fullStatusText)

        // Equalizer 4 batang di samping status
        val eqBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), 0, 0, 0)
        }
        eqBars.clear()
        for (i in 0 until 4) {
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(brassLight)
                }
            }
            eqBox.addView(bar, LinearLayout.LayoutParams(dp(3), dp(6)).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
            eqBars.add(bar)
        }
        fullStatusRow.addView(eqBox, LinearLayout.LayoutParams(-2, dp(18)))
        fullCoverSection.addView(fullStatusRow, LinearLayout.LayoutParams(-2, -2))

        page.addView(fullCoverSection, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---------------------------------------------------------------------
        // MODE B: TAMPILAN LIRIK KARAOKE LAYAR PENUH (SAAT LIRIK AKTIF)
        // ---------------------------------------------------------------------
        fullLyricsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Bar Info Lagu Ringkas di Atas Lirik
        val lyricSongBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22FFFFFF)
            }
        }
        val smallArtFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF26211A.toInt())
            }
            clipToOutline = true
        }
        lyricMiniArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        smallArtFrame.addView(lyricMiniArt, FrameLayout.LayoutParams(-1, -1))
        lyricSongBar.addView(smallArtFrame, LinearLayout.LayoutParams(dp(44), dp(44)))

        val lyricMetaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        lyricMiniTitle = tv("Judul Lagu", 14.5f, ivory, Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lyricMiniSub = tv("Penyanyi · Status", 12f, brassLight).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lyricMetaCol.addView(lyricMiniTitle)
        lyricMetaCol.addView(lyricMiniSub)
        lyricSongBar.addView(lyricMetaCol, LinearLayout.LayoutParams(0, -2, 1f))
        fullLyricsSection.addView(lyricSongBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Kotak Lirik Karaoke Raksasa
        lyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF0E0D0B.toInt())
                setStroke(dp(1), 0x33B8975A)
            }
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        lyricBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lyricScroll.addView(lyricBox, FrameLayout.LayoutParams(-1, -2))
        fullLyricsSection.addView(lyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(fullLyricsSection, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---------------------------------------------------------------------
        // BAGIAN BAWAH FULL PLAYER: SEEKBAR & TOMBOL KONTROL BESAR
        // ---------------------------------------------------------------------
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        fullQualityLabel = tv("BAYU HI-FI AUDIO", 10.5f, mute, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.1f
        }
        fullTimeLabel = tv("00:00 / 00:00", 12f, brassLight, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.END
        }
        timeRow.addView(fullQualityLabel, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(fullTimeLabel)
        bottomControls.addView(timeRow)

        fullSeekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(brassLight)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setPadding(0, dp(4), 0, dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = MusicEngine.player?.duration ?: 0
                        if (dur > 0) {
                            val target = (dur * progress) / 1000L
                            fullTimeLabel.text = "${fmtTime(target)} / ${fmtTime(dur)}"
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
        bottomControls.addView(fullSeekBar, LinearLayout.LayoutParams(-1, -2))

        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        fullBtnShuffle = controlBtn("🔀", small = true) {
            MusicEngine.shuffleEnabled = !MusicEngine.shuffleEnabled
            syncAllPlayerViews()
            notice.show("Mode Acak", if (MusicEngine.shuffleEnabled) "Acak lagu aktif" else "Acak lagu nonaktif", 2000L)
        }
        val btnPrev = controlBtn("⏮") { MusicEngine.playPrev(this) }
        fullBtnPlayPause = controlBtn("▶", primary = true) { MusicEngine.togglePause() }
        val btnNext = controlBtn("⏭") { MusicEngine.playNext(this) }
        fullBtnRepeat = controlBtn("🔁", small = true) {
            MusicEngine.repeatMode = (MusicEngine.repeatMode + 1) % 3
            syncAllPlayerViews()
            val msg = when (MusicEngine.repeatMode) {
                1 -> "Ulang semua daftar aktif"
                2 -> "Ulang 1 lagu aktif"
                else -> "Mode ulang nonaktif"
            }
            notice.show("Mode Ulang", msg, 2000L)
        }

        transportRow.addView(fullBtnShuffle)
        transportRow.addView(btnPrev, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        transportRow.addView(fullBtnPlayPause, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), 0, dp(14), 0) })
        transportRow.addView(btnNext, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        transportRow.addView(fullBtnRepeat)
        bottomControls.addView(transportRow)

        page.addView(bottomControls)
        return page
    }

    private fun openFullPlayer() {
        isFullPlayerOpen = true
        fullPlayerOverlay.visibility = View.VISIBLE
        syncAllPlayerViews()
    }

    private fun closeFullPlayer() {
        isFullPlayerOpen = false
        fullPlayerOverlay.visibility = View.GONE
        syncAllPlayerViews()
    }

    private fun controlBtn(symbol: String, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit): TextView {
        return tv(symbol, if (primary) 20f else if (small) 14f else 17f, if (primary) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(
                dp(if (primary) 26 else if (small) 12 else 17),
                dp(if (primary) 12 else if (small) 9 else 11),
                dp(if (primary) 26 else if (small) 12 else 17),
                dp(if (primary) 12 else if (small) 9 else 11)
            )
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(if (primary) 16 else 12).toFloat()
                setColor(if (primary) brassLight else 0x26FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(if (primary) 16 else 12).toFloat()
                    setColor(if (f) Color.WHITE else if (primary) brassLight else 0x26FFFFFF)
                }
                setTextColor(if (f || primary) bgDark else ivory)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun actionPillBtn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        return tv(label, 11.5f, if (primary) bgDark else ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (primary) brassLight else 0x26FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
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

    private fun styleCategoryChip(v: TextView, idx: Int, focused: Boolean) {
        val active = idx == activeCategoryIdx
        v.setTextColor(if (focused || active) bgDark else ivory)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            when {
                focused -> {
                    setColor(Color.WHITE)
                    setStroke(dp(1), brassLight)
                }
                active -> setColor(brassLight)
                else -> {
                    setColor(0x1CFFFFFF)
                    setStroke(dp(1), 0x22FFFFFF)
                }
            }
        }
    }

    private fun selectCategory(idx: Int, triggerSearch: Boolean = true) {
        activeCategoryIdx = idx
        for (i in categoryViews.indices) {
            styleCategoryChip(categoryViews[i], i, categoryViews[i].hasFocus())
        }
        val (catName, catQuery) = searchCategories[idx]
        searchInput.hint = when (idx) {
            1 -> "Ketik nama artis atau judul album..."
            2 -> "Cari lagu Pop Indonesia..."
            3 -> "Cari lagu Barat / Western..."
            4 -> "Cari lagu K-Pop / Jepang..."
            5 -> "Cari lagu Rock / Jazz..."
            else -> "Cari lagu, artis, atau album (Indo & Barat)..."
        }

        if (!triggerSearch) return

        val typed = searchInput.text.toString().trim()
        if (idx == 1) {
            val q = typed.ifEmpty { "Tulus Album" }
            searchYtAlbums(q)
        } else if (catQuery.isNotEmpty()) {
            // Jika user sudah mengetik kata kunci, cari kata kunci itu; jika kosong, tampilkan chart klasifikasi terpilih
            val q = typed.ifEmpty { catQuery }
            searchYtMusicSongs(q, headerLabel = if (typed.isEmpty()) "Klasifikasi: $catName" else "")
        } else {
            val q = typed.ifEmpty { "Lagu Pop Indonesia & Barat Terbaru" }
            searchYtMusicSongs(q)
        }
    }

    private fun executeSearchWithCategory() {
        val typed = searchInput.text.toString().trim()
        if (activeCategoryIdx == 1) {
            searchYtAlbums(typed.ifEmpty { "Album Pop Indonesia" })
        } else {
            val defaultQ = searchCategories[activeCategoryIdx].second.ifEmpty { "Lagu Pop Indonesia & Barat Terbaru" }
            searchYtMusicSongs(typed.ifEmpty { defaultQ })
        }
    }

    private fun selectTab(idx: Int) {
        activeTab = idx
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
        searchSectionBox.visibility = if (idx == 0) View.VISIBLE else View.GONE
        playlistBarBox.visibility = if (idx == 1) View.VISIBLE else View.GONE
        radioBarBox.visibility = if (idx == 2) View.VISIBLE else View.GONE
        currentRenderedTracks = emptyList()
        listBox.removeAllViews()

        when (idx) {
            0 -> executeSearchWithCategory()
            1 -> loadUserPlaylists()
            2 -> loadIndonesianRadios()
            3 -> loadMurottalAndLofi()
            4 -> loadLocalMusic()
        }
    }

    // --- GOOGLE ACCOUNT LOGIN ---
    private fun refreshGoogleAccountButton() {
        val logged = GoogleMusicAuth.isLoggedIn()
        btnGoogleAccount.text = if (logged) "● Google Aktif" else "Login Google"
        btnGoogleAccount.setTextColor(if (logged) greenOk else brassLight)
    }

    private fun handleGoogleAccountButton() {
        if (GoogleMusicAuth.isLoggedIn()) {
            AlertDialog.Builder(this)
                .setTitle("Akun Google Terhubung")
                .setMessage("Sesi YouTube Music Anda aktif. Playlist dari akun Google bisa langsung dibuka di tab Playlist Saya.")
                .setPositiveButton("Sinkronkan Playlist") { _, _ -> selectTab(1) }
                .setNeutralButton("Login Ulang") { _, _ -> openGoogleLoginDialog() }
                .setNegativeButton("Keluar Akun") { _, _ ->
                    GoogleMusicAuth.logout()
                    refreshGoogleAccountButton()
                    notice.show("Akun Google", "Sesi akun Google telah dihapus.", 3000L)
                    if (activeTab == 1) loadUserPlaylists()
                }
                .show()
        } else {
            openGoogleLoginDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openGoogleLoginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val infoBar = tv(
            "Masuk ke akun Google Anda untuk menyinkronkan Playlist YouTube Music.",
            12f,
            Color.BLACK,
            Typeface.DEFAULT_BOLD
        ).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF3E9D2.toInt())
        }
        container.addView(infoBar)

        val wv = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(420))
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = GoogleMusicAuth.CHROME_UA
        }
        container.addView(wv)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Login Akun Google · YouTube Music")
            .setView(container)
            .setNegativeButton("Tutup", null)
            .create()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val u = url ?: ""
                if (u.startsWith("https://music.youtube.com") && GoogleMusicAuth.isLoggedIn()) {
                    CookieManager.getInstance().flush()
                    refreshGoogleAccountButton()
                    notice.show("Login Berhasil", "Akun Google terhubung! Memuat playlist YouTube Music Anda...", 4000L)
                    dialog.dismiss()
                    selectTab(1)
                }
            }
        }

        dialog.setOnDismissListener {
            try {
                wv.destroy()
            } catch (_: Exception) {
            }
        }

        dialog.show()
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F&hl=id")
    }

    // --- TAB 0: PENCARIAN LAGU & ALBUM YOUTUBE MUSIC ---
    private fun searchYtMusicSongs(query: String, headerLabel: String = "") {
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
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
                val auth = GoogleMusicAuth.buildSapisidHashAuth()
                if (auth != null) {
                    conn.setRequestProperty("Authorization", auth)
                    conn.setRequestProperty("Cookie", GoogleMusicAuth.getCookie())
                }

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
                    if (headerLabel.isNotEmpty()) {
                        listBox.addView(
                            tv(headerLabel.uppercase(Locale.US), 11f, brassLight, Typeface.DEFAULT_BOLD).apply {
                                letterSpacing = 0.1f
                                setPadding(dp(4), 0, 0, dp(8))
                            }
                        )
                    }
                    renderTrackList(results, clearFirst = false, openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun searchYtAlbums(query: String) {
        lastAlbumSearchQuery = query
        listBox.removeAllViews()
        listBox.addView(tv("Mencari album \"$query\"...", 13f, mute))
        Thread {
            val albums = ArrayList<AlbumItem>()
            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)

                val safeQ = query.replace("\"", "")
                val payload = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"query":"$safeQ","params":"EgWKAQIYAWoKEAMQBBAJEAoQBQ%3D%3D"}"""
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val rootJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractAlbumItems(rootJson, albums)
                }
            } catch (_: Exception) {
            }

            ui.post {
                if (isFinishing || activeTab != 0) return@post
                lastFoundAlbums = albums
                renderAlbumList(albums)
            }
        }.start()
    }

    private fun extractAlbumItems(node: Any?, out: MutableList<AlbumItem>) {
        if (out.size >= 20 || node == null) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    val browseId = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "") ?: ""
                    val cols = renderer.optJSONArray("flexColumns")
                    var title = ""
                    var sub = ""
                    if (cols != null && cols.length() > 0) {
                        title = readRunsText(cols.getJSONObject(0))
                        if (cols.length() > 1) sub = readRunsText(cols.getJSONObject(1))
                    }
                    val thumbs = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                        thumbs.getJSONObject(thumbs.length() - 1).optString("url", "").ifBlank { null }
                    } else null

                    if (browseId.isNotEmpty() && title.isNotEmpty() && out.none { it.browseId == browseId }) {
                        out.add(AlbumItem(browseId, title, sub.ifBlank { "Album YouTube Music" }, thumbUrl))
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) extractAlbumItems(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractAlbumItems(node.opt(i), out)
            }
        }
    }

    private fun renderAlbumList(albums: List<AlbumItem>) {
        currentRenderedTracks = emptyList()
        listBox.removeAllViews()
        if (albums.isEmpty()) {
            listBox.addView(tv("Album tidak ditemukan. Coba ketik nama penyanyi atau judul album lain.", 13f, mute))
            return
        }

        listBox.addView(
            tv("HASIL ALBUM · KETUK UNTUK BUKA DAFTAR LAGU", 11f, brassLight, Typeface.DEFAULT_BOLD).apply {
                letterSpacing = 0.08f
                setPadding(dp(4), 0, 0, dp(8))
            }
        )

        for (album in albums) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFF171512.toInt())
                    setStroke(dp(1), 0x33B8975A)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (f) 0x3AB8975A else 0xFF171512.toInt())
                        setStroke(dp(if (f) 2 else 1), if (f) brassLight else 0x33B8975A)
                    }
                }
                setOnClickListener { openAlbumDetail(album) }
            }

            val coverFrame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFF26211A.toInt())
                    setStroke(dp(1), 0x55B8975A)
                }
                clipToOutline = true
            }
            val coverImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            Images.load(album.thumbUrl, coverImg)
            coverFrame.addView(coverImg, FrameLayout.LayoutParams(-1, -1))
            row.addView(coverFrame, LinearLayout.LayoutParams(dp(54), dp(54)))

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            col.addView(tv(album.title, 14f, ivory, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(tv(album.subtitle, 12f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val openPill = tv("ALBUM ›", 10.5f, brassLight, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0x28B8975A)
                }
            }
            row.addView(openPill)

            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun openAlbumDetail(album: AlbumItem) {
        listBox.removeAllViews()
        listBox.addView(tv("Memuat lagu dalam album \"${album.title}\"...", 13f, mute))

        Thread {
            val artistHint = album.subtitle.split("•", "·").firstOrNull {
                !it.trim().equals("Album", true) && !it.trim().equals("EP", true) && !it.trim().equals("Single", true)
            }?.trim() ?: album.title
            val tracks = PlaylistStore.fetchBrowseTracks(
                browseId = album.browseId,
                fallbackArtist = artistHint,
                fallbackThumb = album.thumbUrl
            )

            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeAllViews()

                // Tombol Kembali ke Daftar Album
                val backRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(10))
                }
                val btnBackAlbums = actionPillBtn("← Kembali ke Daftar Album") {
                    if (lastFoundAlbums.isNotEmpty()) {
                        renderAlbumList(lastFoundAlbums)
                    } else {
                        searchYtAlbums(lastAlbumSearchQuery.ifEmpty { "Album Pop Indonesia" })
                    }
                }
                backRow.addView(btnBackAlbums)
                listBox.addView(backRow)

                // Kartu Header Album
                val headerCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(0xFF1D1914.toInt())
                        setStroke(dp(1), 0x55B8975A)
                    }
                }
                val artBox = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(0xFF26211A.toInt())
                    }
                    clipToOutline = true
                }
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                Images.load(album.thumbUrl, img)
                artBox.addView(img, FrameLayout.LayoutParams(-1, -1))
                headerCard.addView(artBox, LinearLayout.LayoutParams(dp(72), dp(72)))

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                infoCol.addView(tv(album.title, 15.5f, ivory, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                infoCol.addView(tv("${album.subtitle} · ${tracks.size} Lagu", 12f, brassLight).apply {
                    setPadding(0, dp(3), 0, dp(8))
                })

                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                if (tracks.isNotEmpty()) {
                    val btnPlayAlbum = actionPillBtn("▶ Putar Semua", primary = true) {
                        MusicEngine.playTrack(this, tracks[0], tracks, 0)
                        openFullPlayer()
                    }
                    val btnSaveAlbum = actionPillBtn("+ Simpan Album") {
                        showSaveAlbumToPlaylistDialog(album.title, tracks)
                    }
                    btnRow.addView(btnPlayAlbum)
                    btnRow.addView(btnSaveAlbum, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                }
                infoCol.addView(btnRow)
                headerCard.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
                listBox.addView(headerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                if (tracks.isEmpty()) {
                    listBox.addView(tv("Daftar lagu album ini tidak tersedia.", 13f, mute))
                } else {
                    renderTrackList(tracks, clearFirst = false, openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun showSaveAlbumToPlaylistDialog(albumTitle: String, tracks: List<Track>) {
        val all = PlaylistStore.getLocalPlaylists(this)
        val names = all.map { "${it.name} (${it.tracks.size})" }.toMutableList()
        names.add("+ Buat Playlist Baru \"$albumTitle\"")

        AlertDialog.Builder(this)
            .setTitle("Simpan ${tracks.size} lagu album ke:")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == all.size) {
                    val newIdx = PlaylistStore.createPlaylist(this, albumTitle)
                    val added = PlaylistStore.addManyTracksToPlaylist(this, newIdx, tracks)
                    notice.show("Album Tersimpan", "$added lagu disimpan ke playlist \"$albumTitle\".", 3500L)
                } else {
                    val added = PlaylistStore.addManyTracksToPlaylist(this, which, tracks)
                    notice.show("Album Tersimpan", "$added lagu baru ditambahkan ke \"${all[which].name}\".", 3500L)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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

    // --- TAB 1: PLAYLIST SAYA ---
    private fun loadUserPlaylists() {
        listBox.removeAllViews()
        listBox.addView(tv("Memuat playlist lokal & akun Google...", 13f, mute))

        Thread {
            val locals = PlaylistStore.getLocalPlaylists(this)
            val googlePls = if (GoogleMusicAuth.isLoggedIn()) {
                PlaylistStore.fetchGoogleAccountPlaylists()
            } else {
                emptyList()
            }
            val merged = ArrayList<UserPlaylist>()
            merged.addAll(locals)
            merged.addAll(googlePls)

            ui.post {
                if (isFinishing || activeTab != 1) return@post
                combinedPlaylists = merged
                if (selectedPlaylistIdx !in combinedPlaylists.indices) selectedPlaylistIdx = 0
                renderPlaylistChipsAndTracks()
            }
        }.start()
    }

    private fun renderPlaylistChipsAndTracks() {
        playlistChipRow.removeAllViews()
        for ((idx, pl) in combinedPlaylists.withIndex()) {
            val isSel = idx == selectedPlaylistIdx
            val label = if (pl.isGoogleSynced && pl.tracks.isEmpty()) pl.name else "${pl.name} (${pl.tracks.size})"
            val chip = tv(label, 12f, if (isSel) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (isSel) brassLight else 0x22FFFFFF)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (f) Color.WHITE else if (isSel) brassLight else 0x22FFFFFF)
                    }
                    setTextColor(if (f || isSel) bgDark else ivory)
                }
                setOnClickListener {
                    selectedPlaylistIdx = idx
                    renderPlaylistChipsAndTracks()
                }
            }
            playlistChipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }

        val currentPl = combinedPlaylists.getOrNull(selectedPlaylistIdx) ?: return
        listBox.removeAllViews()

        if (currentPl.isGoogleSynced && currentPl.tracks.isEmpty() && currentPl.browseId.isNotEmpty()) {
            listBox.addView(tv("Mengambil daftar lagu dari ${currentPl.name}...", 13f, mute))
            Thread {
                val fetched = PlaylistStore.fetchBrowseTracks(currentPl.browseId)
                currentPl.tracks.clear()
                currentPl.tracks.addAll(fetched)
                ui.post {
                    if (!isFinishing && activeTab == 1) {
                        renderPlaylistChipsAndTracks()
                    }
                }
            }.start()
            return
        }

        if (currentPl.tracks.isEmpty()) {
            val hintMsg = if (currentPl.isGoogleSynced) {
                "Tidak ada lagu ditemukan di \"${currentPl.name}\"."
            } else {
                "Playlist \"${currentPl.name}\" masih kosong.\n\nTekan tombol \"+ Playlist\" pada lagu atau album untuk menyimpannya ke sini."
            }
            listBox.addView(
                tv(hintMsg, 13f, mute).apply {
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                    setLineSpacing(0f, 1.3f)
                }
            )
            return
        }

        val playAllRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x33B8975A)
                setStroke(dp(1), brass)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (f) brassLight else 0x33B8975A)
                    setStroke(dp(if (f) 2 else 1), if (f) Color.WHITE else brass)
                }
            }
            setOnClickListener {
                MusicEngine.playTrack(this@RadioMusicActivity, currentPl.tracks[0], currentPl.tracks, 0)
                openFullPlayer()
            }
        }
        playAllRow.addView(
            tv("▶  Putar Semua (${currentPl.tracks.size} Lagu)", 13.5f, brassLight, Typeface.DEFAULT_BOLD)
        )
        listBox.addView(playAllRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        renderTrackList(currentPl.tracks, clearFirst = false, isLocalPlaylistTab = !currentPl.isGoogleSynced, openFullOnPlay = true)
    }

    private fun promptCreatePlaylist() {
        val input = EditText(this).apply {
            hint = "Nama playlist baru (misal: Favorit Keluarga)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Buat Playlist Baru")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    selectedPlaylistIdx = PlaylistStore.createPlaylist(this, name)
                    if (activeTab == 1) loadUserPlaylists()
                    notice.show("Playlist Dibuat", "\"$name\" siap diisi lagu.", 3000L)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun promptDeleteCurrentPlaylist() {
        val target = combinedPlaylists.getOrNull(selectedPlaylistIdx) ?: return
        if (target.isGoogleSynced) {
            notice.show("Info", "Playlist dari akun Google tidak bisa dihapus dari sini.", 3000L)
            return
        }
        val locals = PlaylistStore.getLocalPlaylists(this)
        if (locals.size <= 1) {
            notice.show("Info", "Minimal harus ada 1 playlist lokal tersimpan.", 3000L)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus Playlist?")
            .setMessage("Hapus playlist \"${target.name}\" beserta seluruh isinya?")
            .setPositiveButton("Hapus") { _, _ ->
                PlaylistStore.deletePlaylist(this, selectedPlaylistIdx)
                selectedPlaylistIdx = 0
                loadUserPlaylists()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val all = PlaylistStore.getLocalPlaylists(this)
        val names = all.map { "${it.name} (${it.tracks.size})" }.toMutableList()
        names.add("+ Buat Playlist Baru...")

        AlertDialog.Builder(this)
            .setTitle("Simpan \"${track.title}\" ke:")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == all.size) {
                    val input = EditText(this).apply {
                        hint = "Nama playlist baru..."
                        setSingleLine(true)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Playlist Baru")
                        .setView(input)
                        .setPositiveButton("Simpan") { _, _ ->
                            val nm = input.text.toString().trim()
                            if (nm.isNotEmpty()) {
                                val newIdx = PlaylistStore.createPlaylist(this, nm)
                                PlaylistStore.addTrackToPlaylist(this, newIdx, track)
                                notice.show("Tersimpan", "Ditambahkan ke \"$nm\".", 3000L)
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else {
                    val added = PlaylistStore.addTrackToPlaylist(this, which, track)
                    if (added) {
                        notice.show("Tersimpan", "Ditambahkan ke \"${all[which].name}\".", 3000L)
                    } else {
                        notice.show("Sudah Ada", "Lagu ini sudah ada di \"${all[which].name}\".", 3000L)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // --- TAB 2: RADIO INDONESIA ---
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
        renderTrackList(curated, openFullOnPlay = true)

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
                if (!isFinishing && activeTab == 2 && fetched.size > curated.size) {
                    renderTrackList(sortRadiosByScan(fetched), openFullOnPlay = true)
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
        renderTrackList(targets, openFullOnPlay = true)

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
                        if (activeTab == 2) {
                            renderTrackList(sortRadiosByScan(targets), openFullOnPlay = true)
                        }
                    } else if (activeTab == 2 && done % 3 == 0) {
                        renderTrackList(targets, openFullOnPlay = true)
                    }
                }
            }
        }
        pool.shutdown()
    }

    private fun sortRadiosByScan(list: List<Track>): List<Track> {
        return list.sortedBy { t ->
            when (radioScanMap[t.id]?.state) {
                2 -> 0
                1, null -> 1
                else -> 2
            }
        }
    }

    private fun probeRadioStation(rawUrl: String): RadioScanResult {
        var cur = rawUrl.trim()
        if (!cur.startsWith("http", ignoreCase = true)) {
            return RadioScanResult(3, "MATI", "Alamat URL tidak valid")
        }
        val ua = GoogleMusicAuth.CHROME_UA
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

    // --- TAB 3: MUROTTAL & LOFI ---
    private fun loadMurottalAndLofi() {
        val list = listOf(
            Track("murottal1", "Murottal Al-Qur'an 24 Jam", "Mishary Rashid Alafasy", "https://backup.qurango.net/radio/mishary_alafasi", isLive = true),
            Track("murottal2", "Radio Al-Qur'an Makkah", "Siaran Langsung Masjidil Haram", "https://backup.qurango.net/radio/tarateel", isLive = true),
            Track("murottal3", "Murottal Abdurrahman As-Sudais", "Imam Masjidil Haram", "https://backup.qurango.net/radio/abdulrahman_alsudaes", isLive = true),
            Track("lofi1", "Lofi Hip Hop & Belajar", "Musik Santai Tanpa Vokal", "https://streams.ilovemusic.de/iloveradio17.mp3", isLive = true),
            Track("piano1", "Instrumental Piano & Hujan", "Relaksasi & Pengantar Tidur", "https://strm112.1.fm/spa_mobile_mp3", isLive = true)
        )
        renderTrackList(list, openFullOnPlay = true)
    }

    // --- TAB 4: MUSIK LOKAL ---
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
                if (isFinishing || activeTab != 4) return@post
                listBox.removeAllViews()
                if (locals.isEmpty()) {
                    listBox.addView(tv("Tidak ada file MP3 ditemukan di penyimpanan perangkat.", 13f, mute))
                } else {
                    renderTrackList(locals, openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun renderTrackList(
        items: List<Track>,
        clearFirst: Boolean = true,
        isLocalPlaylistTab: Boolean = false,
        openFullOnPlay: Boolean = true
    ) {
        currentRenderedTracks = items
        if (clearFirst) listBox.removeAllViews()
        val curId = MusicEngine.currentTrack?.id

        for ((index, item) in items.withIndex()) {
            val isPlayingThis = item.id == curId
            val scanInfo = if (activeTab == 2) radioScanMap[item.id] else null

            val outerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(8), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isPlayingThis) 0x2AB8975A else 0xFF151311.toInt())
                    setStroke(dp(1), if (isPlayingThis) brass else 0x22FFFFFF)
                }
            }

            val playArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f ->
                    outerRow.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (f) 0x3AB8975A else if (isPlayingThis) 0x2AB8975A else 0xFF151311.toInt())
                        setStroke(dp(if (f) 2 else 1), if (f) brassLight else if (isPlayingThis) brass else 0x22FFFFFF)
                    }
                }
                setOnClickListener {
                    MusicEngine.playTrack(this@RadioMusicActivity, item, items, index)
                    if (openFullOnPlay) openFullPlayer()
                }
            }

            val badge = tv(if (item.isLive) "LIVE" else if (item.isYtMusic) "YT" else "MP3", 10f, brassLight, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0x28B8975A)
                }
            }
            playArea.addView(badge)

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
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
            playArea.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            if (isPlayingThis) {
                val playingPill = tv("DIPUTAR", 9.5f, bgDark, Typeface.DEFAULT_BOLD).apply {
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(brassLight)
                    }
                }
                playArea.addView(playingPill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
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
                playArea.addView(scanPill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }

            outerRow.addView(playArea, LinearLayout.LayoutParams(0, -2, 1f))

            if (isLocalPlaylistTab) {
                val btnRem = actionPillBtn("Hapus") {
                    PlaylistStore.removeTrackFromPlaylist(this, selectedPlaylistIdx, index)
                    loadUserPlaylists()
                }
                outerRow.addView(btnRem)
            } else {
                val btnAdd = actionPillBtn("+ Playlist") {
                    showAddToPlaylistDialog(item)
                }
                outerRow.addView(btnAdd)
            }

            listBox.addView(outerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    // =========================================================================
    // SINKRONISASI UI MINI PLAYER & FULL-SCREEN PLAYER
    // =========================================================================
    private fun syncAllPlayerViews() {
        val t = MusicEngine.currentTrack
        val st = MusicEngine.state

        if (t == null) {
            miniPlayerBar.visibility = View.GONE
            fullNowTitle.text = "Belum ada lagu diputar"
            fullNowArtist.text = "Pilih lagu atau album"
            fullBtnPlayPause.text = "▶"
            miniBtnPlayPause.text = "▶"
        } else {
            miniPlayerBar.visibility = View.VISIBLE
            miniTitle.text = t.title
            miniSubtitle.text = "${t.artist} · ${MusicEngine.statusDetail}"
            Images.load(t.thumbUrl, miniArtView)
            miniBtnPlayPause.text = if (st == PlayState.PLAYING) "⏸" else "▶"

            fullNowTitle.text = t.title
            fullNowArtist.text = t.artist
            Images.load(t.thumbUrl, fullArtView)
            fullBtnPlayPause.text = if (st == PlayState.PLAYING) "⏸" else "▶"

            lyricMiniTitle.text = t.title
            lyricMiniSub.text = "${t.artist} · ${MusicEngine.statusDetail}"
            Images.load(t.thumbUrl, lyricMiniArt)
        }

        // Sakelar Lirik: Saat aktif, tampilan penuh berubah menjadi Layar Lirik Karaoke
        fullBtnToggleLyrics.text = if (MusicEngine.lyricsEnabled) "Lirik: AKTIF" else "Lirik: NONAKTIF"
        fullBtnToggleLyrics.setTextColor(if (MusicEngine.lyricsEnabled) brassLight else mute)

        if (MusicEngine.lyricsEnabled) {
            fullCoverSection.visibility = View.GONE
            fullLyricsSection.visibility = View.VISIBLE
            rebuildLyricViews()
        } else {
            fullLyricsSection.visibility = View.GONE
            fullCoverSection.visibility = View.VISIBLE
        }

        fullBtnShuffle.setTextColor(if (MusicEngine.shuffleEnabled) brassLight else mute)
        fullBtnRepeat.text = if (MusicEngine.repeatMode == 2) "🔂" else "🔁"
        fullBtnRepeat.setTextColor(if (MusicEngine.repeatMode > 0) brassLight else mute)

        val isBusy = (st == PlayState.RESOLVING || st == PlayState.BUFFERING)
        fullSpinner.visibility = if (isBusy) View.VISIBLE else View.GONE
        fullStatusText.text = MusicEngine.statusDetail
        fullQualityLabel.text = MusicEngine.statusDetail.uppercase(Locale.US)

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
        fullStatusText.setTextColor(pillTextColor)
        fullStatusRow.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(pillBgColor)
        }
    }

    private fun animateEqualizer() {
        if (eqBars.isEmpty()) return
        val active = MusicEngine.state == PlayState.PLAYING
        if (!active) {
            for (bar in eqBars) {
                val lp = bar.layoutParams
                if (lp.height != dp(4)) {
                    lp.height = dp(4)
                    bar.layoutParams = lp
                }
            }
            return
        }
        eqPhase += 0.85
        for (i in eqBars.indices) {
            val wave = (sin(eqPhase + i * 1.4) + 1.0) * 0.5
            val h = dp(4 + (wave * 12).toInt())
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
            lyricBox.addView(tv(MusicEngine.lyricStatus, 15f, mute).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(36), dp(12), dp(36))
            })
            return
        }

        for (line in lines) {
            val ltv = tv(line.text, 16f, 0xFF8C8478.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setLineSpacing(0f, 1.2f)
                isClickable = true
                // Ketuk baris lirik untuk melompat ke detik tersebut
                setOnClickListener {
                    MusicEngine.player?.seekTo(line.timeMs)
                }
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
                fullTimeLabel.text = "LIVE · HEMAT KUOTA"
                fullSeekBar.isEnabled = false
                fullSeekBar.progress = 1000
                miniProgressBar.progress = 1000
                return
            }
            fullSeekBar.isEnabled = true
            val pos = exo.currentPosition
            val dur = exo.duration
            if (dur > 0 && !isSeeking) {
                val permille = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                fullSeekBar.progress = permille
                miniProgressBar.progress = permille
                fullTimeLabel.text = "${fmtTime(pos)} / ${fmtTime(dur)}"
            }

            if (!MusicEngine.lyricsEnabled || !isFullPlayerOpen) return

            val lines = MusicEngine.lyrics
            if (lines.isNotEmpty() && lyricViews.size == lines.size) {
                var activeIdx = -1
                for (i in lines.indices) {
                    if (pos >= lines[i].timeMs) activeIdx = i else break
                }
                if (activeIdx != lastHighlightedIdx && activeIdx >= 0) {
                    if (lastHighlightedIdx in lyricViews.indices) {
                        lyricViews[lastHighlightedIdx].setTextColor(0xFF8C8478.toInt())
                        lyricViews[lastHighlightedIdx].setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 16f))
                    }
                    val activeView = lyricViews[activeIdx]
                    activeView.setTextColor(brassLight)
                    activeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 20f))
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
        refreshGoogleAccountButton()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }
}
