package com.bayutv.hotel

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

// Pemutar film/episode (Streaming & Koleksi Saya) dengan Sub Indo otomatis.
class VodPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var notice: Notice
    private var usedFallback = false
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val resumeKey: String get() = "pos_" + (intent.getStringExtra("resume_key") ?: "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        view = PlayerView(this)
        view.useController = true
        view.setShowSubtitleButton(true)
        view.controllerShowTimeoutMs = 3500
        view.subtitleView?.apply {
            setStyle(
                CaptionStyleCompat(
                    0xFFEFE8DA.toInt(),
                    0x99000000.toInt(),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    null
                )
            )
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setApplyEmbeddedStyles(false)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        notice = Notice(this, root)
        setContentView(root)

        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 35000, 2000, 4000)
            .build()

        val ref = intent.getStringExtra("referer") ?: ""
        val ua = intent.getStringExtra("user_agent") ?: Playlist.DEFAULT_UA
        val dsf = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)
            .setUserAgent(ua)
        if (ref.isNotEmpty()) {
            val extra = HashMap<String, String>()
            extra["Referer"] = ref
            dsf.setDefaultRequestProperties(extra)
        }

        val p = ExoPlayer.Builder(this)
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .build()

        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("id", "in", "ind", "ms", "en")
            .setPreferredAudioLanguages("id", "ja", "en")
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val fb = intent.getStringExtra("fallback") ?: ""
                if (!usedFallback && fb.isNotEmpty()) {
                    usedFallback = true
                    val pos = p.currentPosition
                    notice.show(null, "Mengonversi video di server...", 3000L)
                    p.setMediaItem(item(fb, emptyList()), pos)
                    p.prepare()
                    p.playWhenReady = true
                    return
                }
                notice.show("Tidak bisa diputar", error.errorCodeName)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefs.edit().remove(resumeKey).apply()
                    finish()
                }
            }
        })
        view.player = p
        player = p

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }
        val resume = prefs.getLong(resumeKey, 0L)
        p.setMediaItem(item(url, emptyList()), resume)
        p.prepare()
        p.playWhenReady = true

        val title = intent.getStringExtra("title") ?: ""
        if (resume > 60000L) notice.show(title, "Melanjutkan dari menit " + (resume / 60000L), 3000L)
        else if (title.isNotEmpty()) notice.show(null, title, 2500L)

        // Jika diputar dari TMDB tanpa subtitle bawaan, cari Sub Indo otomatis di latar belakang
        val tmdbId = intent.getIntExtra("tmdb_id", 0)
        val hasExistingSubs = (intent.getStringArrayExtra("sub_urls")?.isNotEmpty() == true)
        if (tmdbId > 0 && !hasExistingSubs) {
            val kind = intent.getStringExtra("tmdb_kind") ?: "movie"
            val season = intent.getIntExtra("season", 0)
            val episode = intent.getIntExtra("episode", 0)
            Thread {
                val tracks = Subtitles.fetch(tmdbId, kind, season, episode)
                if (tracks.isNotEmpty()) {
                    ui.post {
                        val activePlayer = player ?: return@post
                        if (isFinishing) return@post
                        val curPos = activePlayer.currentPosition
                        val wasPlaying = activePlayer.playWhenReady
                        activePlayer.setMediaItem(item(url, tracks), curPos)
                        activePlayer.prepare()
                        activePlayer.playWhenReady = wasPlaying
                        val hasId = tracks.any { it.lang == "id" }
                        notice.show(
                            null,
                            if (hasId) "Subtitle Indonesia aktif" else "Subtitle tersedia (tekan tombol CC)",
                            2500L
                        )
                    }
                }
            }.start()
        }
    }

    private fun item(url: String, fetchedSubs: List<Subtitles.Track>): MediaItem {
        val urls = intent.getStringArrayExtra("sub_urls") ?: emptyArray()
        val langs = intent.getStringArrayExtra("sub_langs") ?: emptyArray()
        val labels = intent.getStringArrayExtra("sub_labels") ?: emptyArray()
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()

        for (i in urls.indices) {
            val subUrl = urls[i]
            val lang = langs.getOrNull(i) ?: "und"
            val indo = lang.startsWith("id") || lang.startsWith("in") || lang == "ind"
            val mime = if (subUrl.lowercase().contains(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            subs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setMimeType(mime)
                    .setLanguage(lang)
                    .setLabel(labels.getOrNull(i) ?: lang)
                    .setSelectionFlags(if (indo) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }

        for (t in fetchedSubs) {
            val indo = t.lang == "id"
            val mime = if (t.isVtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            subs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setMimeType(mime)
                    .setLanguage(t.lang)
                    .setLabel(t.label)
                    .setSelectionFlags(if (indo) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }

        return MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos > 60000L && (dur <= 0L || pos < dur - 180000L)) prefs.edit().putLong(resumeKey, pos).apply()
        p.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
