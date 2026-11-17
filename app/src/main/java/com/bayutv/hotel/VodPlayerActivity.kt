package com.bayutv.hotel

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ContinueWatch {
    data class Entry(
        val key: String,
        val movie: Movie,
        val season: Int,
        val episode: Int,
        val posMs: Long
    )

    fun saveFromIntent(ctx: Context, intent: Intent, posMs: Long) {
        val key = intent.getStringExtra("resume_key") ?: return
        if (key.isEmpty()) return
        val title = intent.getStringExtra("title") ?: return
        val tmdbId = intent.getIntExtra("tmdb_id", 0)
        val kind = intent.getStringExtra("tmdb_kind") ?: (if (key.startsWith("jf_")) "jf_movie" else "movie")
        val jfId = intent.getStringExtra("jf_id") ?: (if (key.startsWith("jf_")) key.removePrefix("jf_") else "")
        val m = Movie(
            id = tmdbId,
            title = title,
            year = intent.getStringExtra("year") ?: "",
            poster = intent.getStringExtra("poster"),
            rating = intent.getDoubleExtra("rating", 0.0),
            overview = intent.getStringExtra("overview") ?: "",
            kind = kind,
            jfId = jfId,
            backdrop = intent.getStringExtra("backdrop")
        )
        upsert(ctx, Entry(key, m, intent.getIntExtra("season", 1), intent.getIntExtra("episode", 1), posMs))
    }

    @Synchronized
    fun upsert(ctx: Context, item: Entry) {
        val prefs = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val old = load(ctx).filterNot { it.key == item.key }
        val combined = (listOf(item) + old).take(10)
        val arr = JSONArray()
        for (e in combined) {
            val o = JSONObject()
            o.put("key", e.key)
            o.put("id", e.movie.id)
            o.put("title", e.movie.title)
            o.put("year", e.movie.year)
            o.put("poster", e.movie.poster ?: "")
            o.put("backdrop", e.movie.backdrop ?: "")
            o.put("rating", e.movie.rating)
            o.put("overview", e.movie.overview)
            o.put("kind", e.movie.kind)
            o.put("jfId", e.movie.jfId)
            o.put("season", e.season)
            o.put("episode", e.episode)
            o.put("pos", e.posMs)
            arr.put(o)
        }
        prefs.edit().putString("continue_watch", arr.toString()).apply()
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        if (key.isEmpty()) return
        val prefs = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        prefs.edit().remove("pos_$key").apply()
        val left = load(ctx).filterNot { it.key == key }
        val arr = JSONArray()
        for (e in left) {
            val o = JSONObject()
            o.put("key", e.key)
            o.put("id", e.movie.id)
            o.put("title", e.movie.title)
            o.put("year", e.movie.year)
            o.put("poster", e.movie.poster ?: "")
            o.put("backdrop", e.movie.backdrop ?: "")
            o.put("rating", e.movie.rating)
            o.put("overview", e.movie.overview)
            o.put("kind", e.movie.kind)
            o.put("jfId", e.movie.jfId)
            o.put("season", e.season)
            o.put("episode", e.episode)
            o.put("pos", e.posMs)
            arr.put(o)
        }
        prefs.edit().putString("continue_watch", arr.toString()).apply()
    }

    fun load(ctx: Context): List<Entry> {
        val prefs = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(prefs.getString("continue_watch", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.optString("key")
                val title = o.optString("title")
                if (key.isEmpty() || title.isEmpty()) continue
                val p = o.optString("poster")
                val b = o.optString("backdrop")
                val m = Movie(
                    id = o.optInt("id"),
                    title = title,
                    year = o.optString("year"),
                    poster = p.ifEmpty { null },
                    rating = o.optDouble("rating", 0.0),
                    overview = o.optString("overview"),
                    kind = o.optString("kind", "movie"),
                    jfId = o.optString("jfId"),
                    backdrop = b.ifEmpty { null }
                )
                out.add(Entry(key, m, o.optInt("season", 1), o.optInt("episode", 1), o.optLong("pos", 0L)))
            }
        } catch (_: Exception) {
        }
        return out
    }
}

class VodPlayerActivity : AppCompatActivity() {

    companion object {
        private const val PAUSE_NIGHT_DELAY_MS = 25_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var view: PlayerView
    private lateinit var notice: Notice
    private var usedFallback = false
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val rawKey: String get() = intent.getStringExtra("resume_key") ?: ""
    private val resumeKey: String get() = "pos_" + rawKey

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    // Mode Gelap otomatis saat video dijeda lama
    private var isNightModeOpen = false
    private lateinit var nightModeOverlay: FrameLayout
    private lateinit var nightContentHolder: LinearLayout
    private lateinit var nightClockText: TextView
    private lateinit var nightTitleText: TextView
    private lateinit var nightPosText: TextView
    private val nightClockFormat = SimpleDateFormat("HH:mm", Locale.US)
    private var nightBurnInStep = 0

    private val pauseNightRunnable = Runnable {
        enterNightMode()
    }

    private val nightClockTickRunnable = object : Runnable {
        override fun run() {
            if (!isNightModeOpen) return
            updateNightModeInfo()
            ui.postDelayed(this, 10_000L)
        }
    }

    private lateinit var countdownBanner: TextView
    private lateinit var adzanOverlay: FrameLayout
    private lateinit var adzanTitleView: TextView
    private lateinit var adzanSubtitleView: TextView
    private lateinit var ramadanBox: LinearLayout
    private lateinit var btnDismissAdzan: TextView

    private var prayerTimes: IntArray? = null
    private var prayerDay = ""
    private var pausedForAdzan = false
    private var wasPlayingBeforeAdzan = false
    private var adzanPlayer: ExoPlayer? = null
    private var adzanCountdownRunnable: Runnable? = null

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface = Typeface.SANS_SERIF): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
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
        buildPauseNightModeOverlay()
        buildAdzanAndRamadanOverlays()
        notice = Notice(this, root)
        setContentView(root)

        Thread {
            val pt = Prayer.fetch(this)
            ui.post {
                prayerTimes = pt
                prayerDay = Prayer.todayStr()
            }
        }.start()

        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 35000, 2000, 4000)
            .build()

        val ref = intent.getStringExtra("referer") ?: ""
        val origin = intent.getStringExtra("origin") ?: ""
        val ua = intent.getStringExtra("user_agent") ?: Playlist.DEFAULT_UA
        val dsf = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)
            .setUserAgent(ua)

        val extraHeaders = HashMap<String, String>()
        if (ref.isNotEmpty()) extraHeaders["Referer"] = ref
        if (origin.isNotEmpty()) {
            extraHeaders["Origin"] = origin
        } else if (ref.startsWith("http")) {
            try {
                val u = Uri.parse(ref)
                if (!u.scheme.isNullOrEmpty() && !u.host.isNullOrEmpty()) {
                    extraHeaders["Origin"] = "${u.scheme}://${u.host}"
                }
            } catch (_: Exception) {
            }
        }
        if (extraHeaders.isNotEmpty()) {
            dsf.setDefaultRequestProperties(extraHeaders)
        }

        val p = ExoPlayer.Builder(this)
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .build()

        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages("id", "in", "ind", "ms", "en")
            .setPreferredAudioLanguages("id", "ja", "en")
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                cancelPauseNightMode()
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
                notice.show("Stream tidak dapat diputar", "Silakan kembali dan pilih server lain (${error.errorCodeName}).")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    cancelPauseNightMode()
                    if (isNightModeOpen) {
                        exitNightMode(resumePlayback = false)
                    }
                } else if (
                    p.playbackState == Player.STATE_READY &&
                    !pausedForAdzan &&
                    adzanOverlay.visibility != View.VISIBLE
                ) {
                    schedulePauseNightMode()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    cancelPauseNightMode()
                    ContinueWatch.remove(this@VodPlayerActivity, rawKey)
                    finish()
                } else if (
                    state == Player.STATE_READY &&
                    !p.playWhenReady &&
                    !pausedForAdzan &&
                    adzanOverlay.visibility != View.VISIBLE
                ) {
                    schedulePauseNightMode()
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
        if (resume > 60000L) notice.show("B-BOX - $title", "Melanjutkan dari menit " + (resume / 60000L), 3000L)
        else if (title.isNotEmpty()) notice.show(null, title, 2500L)

        val tmdbId = intent.getIntExtra("tmdb_id", 0)
        val hasExistingSubs = (intent.getStringArrayExtra("sub_urls")?.isNotEmpty() == true)
        if (tmdbId > 0 && !hasExistingSubs) {
            val kind = intent.getStringExtra("tmdb_kind") ?: "movie"
            val season = intent.getIntExtra("season", 0)
            val episode = intent.getIntExtra("episode", 0)
            val passedImdb = intent.getStringExtra("imdb_id") ?: ""
            Thread {
                val imdbId = passedImdb.ifEmpty { Tmdb.imdbId(this, tmdbId, kind) }
                val tracks = Subtitles.fetch(tmdbId, kind, season, episode, imdbId)
                if (tracks.isNotEmpty()) {
                    ui.post {
                        val activePlayer = player ?: return@post
                        if (isFinishing) return@post
                        val curPos = activePlayer.currentPosition
                        val wasPlaying = activePlayer.playWhenReady
                        activePlayer.setMediaItem(item(url, tracks), curPos)
                        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setPreferredTextLanguages("id", "in", "ind", "ms", "en")
                            .build()
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

    private fun buildPauseNightModeOverlay() {
        nightModeOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isFocusable = true
            isClickable = true
            setOnClickListener { exitNightMode(resumePlayback = true) }
        }

        nightContentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        nightClockText = tv("00:00", 54f, 0xFF5C5448.toInt(), Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
        }
        nightContentHolder.addView(nightClockText)

        nightTitleText = tv("Video Dijeda", 15f, 0xFF4D463C.toInt(), Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(4))
        }
        nightContentHolder.addView(nightTitleText)

        nightPosText = tv("", 13f, 0xFF6B5A3E.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(16))
        }
        nightContentHolder.addView(nightPosText)

        nightContentHolder.addView(
            tv("Mode Layar Gelap · Tekan OK di remote untuk melanjutkan menonton", 12f, 0xFF38332C.toInt()).apply {
                gravity = Gravity.CENTER
            }
        )

        nightModeOverlay.addView(nightContentHolder, FrameLayout.LayoutParams(-1, -1))
        root.addView(nightModeOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun schedulePauseNightMode() {
        ui.removeCallbacks(pauseNightRunnable)
        if (isFinishing || isNightModeOpen || pausedForAdzan || adzanOverlay.visibility == View.VISIBLE) return
        ui.postDelayed(pauseNightRunnable, PAUSE_NIGHT_DELAY_MS)
    }

    private fun cancelPauseNightMode() {
        ui.removeCallbacks(pauseNightRunnable)
    }

    private fun enterNightMode() {
        if (isFinishing || isNightModeOpen || pausedForAdzan || adzanOverlay.visibility == View.VISIBLE) return
        val p = player ?: return
        if (p.isPlaying) return

        isNightModeOpen = true
        view.hideController()
        updateNightModeInfo()
        nightModeOverlay.visibility = View.VISIBLE
        nightModeOverlay.requestFocus()
        ui.removeCallbacks(nightClockTickRunnable)
        ui.postDelayed(nightClockTickRunnable, 10_000L)
    }

    private fun exitNightMode(resumePlayback: Boolean) {
        if (!isNightModeOpen) return
        isNightModeOpen = false
        ui.removeCallbacks(nightClockTickRunnable)
        cancelPauseNightMode()
        nightModeOverlay.visibility = View.GONE

        val p = player
        if (resumePlayback && p != null && !pausedForAdzan) {
            p.playWhenReady = true
            p.play()
            view.showController()
        } else {
            view.showController()
            if (p != null && !p.isPlaying && !pausedForAdzan) {
                schedulePauseNightMode()
            }
        }
    }

    private fun updateNightModeInfo() {
        nightClockText.text = nightClockFormat.format(Date())
        val rawTitle = intent.getStringExtra("title") ?: "Film"
        val season = intent.getIntExtra("season", 0)
        val episode = intent.getIntExtra("episode", 0)
        nightTitleText.text = if (season > 0 && episode > 0) {
            "$rawTitle · S${season}E${episode}"
        } else {
            rawTitle
        }

        val p = player
        if (p != null) {
            val pos = p.currentPosition.coerceAtLeast(0L)
            val dur = p.duration
            nightPosText.text = if (dur > 0L) {
                "Dijeda pada ${formatDuration(pos)} / ${formatDuration(dur)}"
            } else {
                "Dijeda pada ${formatDuration(pos)}"
            }
        }

        // Pergeseran posisi halus tiap 10 detik untuk mencegah burn-in layar TV
        val shifts = intArrayOf(-16, -8, 0, 8, 16, 8, 0, -8)
        val dx = dp(shifts[nightBurnInStep % shifts.size]).toFloat()
        val dy = dp(shifts[(nightBurnInStep + 2) % shifts.size] / 2).toFloat()
        nightContentHolder.translationX = dx
        nightContentHolder.translationY = dy
        nightBurnInStep++
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun buildAdzanAndRamadanOverlays() {
        val isPhone = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600

        countdownBanner = tv("", if (isPhone) 14.5f else 18f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(20), dp(11), dp(20), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE6141210.toInt())
                setStroke(dp(2), brassLight)
            }
            visibility = View.GONE
        }
        val cdlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        cdlp.setMargins(dp(20), dp(24), dp(24), 0)
        root.addView(countdownBanner, cdlp)

        adzanOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC0A0908.toInt())
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPhone) 20 else 32), dp(20), dp(if (isPhone) 20 else 32), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF5161411.toInt())
                setStroke(dp(2), brass)
            }
        }

        adzanTitleView = tv("WAKTU ADZAN", if (isPhone) 18f else 22f, brassLight, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        adzanSubtitleView = tv("Film dijeda sejenak untuk menghormati waktu sholat", if (isPhone) 12.5f else 13.5f, mute, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(adzanTitleView)
        card.addView(adzanSubtitleView)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val contentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        ramadanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x28B8975A)
                setStroke(dp(1), brassLight)
            }
            visibility = View.GONE
        }
        ramadanBox.addView(
            tv("SELAMAT BERBUKA PUASA - DOA BERBUKA", 13f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                letterSpacing = 0.08f
            }
        )
        ramadanBox.addView(
            tv(
                "ذَهَبَ الظَّمَأُ وَابْتَلَّتِ الْعُرُوقُ، وَثَبَتَ الْأَجْرُ إِنْ شَاءَ اللَّهُ",
                if (isPhone) 19f else 22f, ivory, Typeface.SERIF
            ).apply {
                gravity = Gravity.END
                setPadding(0, dp(6), 0, dp(4))
            }
        )
        ramadanBox.addView(
            tv(
                "Dzahabazh-zhama'u wabtallatil-'urūqu wa tsabatal-ajru insyā Allāh.",
                13.5f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            )
        )
        ramadanBox.addView(
            tv(
                "\"Telah hilang rasa haus, telah basah urat-urat, dan telah tetap pahala, insya Allah.\" (HR. Abu Dawud)",
                12.5f, mute, Typeface.SANS_SERIF
            ).apply { setPadding(0, dp(2), 0, 0) }
        )
        val rblp = LinearLayout.LayoutParams(-1, -2)
        rblp.bottomMargin = dp(12)
        contentCol.addView(ramadanBox, rblp)

        val doaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x16FFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        doaBox.addView(
            tv("MENJAWAB ADZAN & DOA SETELAH ADZAN", 13f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                letterSpacing = 0.08f
            }
        )
        doaBox.addView(
            tv(
                "Ucapkan lafal seperti muadzin; saat \"Hayya 'alash-shalāh / Hayya 'alal-falāḥ\" jawab: \"Lā haula wa lā quwwata illā billāh\". Setelah adzan selesai, baca:",
                12.5f, mute, Typeface.SANS_SERIF
            ).apply { setPadding(0, dp(4), 0, dp(8)) }
        )
        doaBox.addView(
            tv(
                "اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ، وَابْعَثْهُ مَقَامًا مَحْمُودًا الَّذِي وَعَدْتَهُ",
                if (isPhone) 18.5f else 21f, ivory, Typeface.SERIF
            ).apply {
                gravity = Gravity.END
                setPadding(0, dp(2), 0, dp(6))
            }
        )
        doaBox.addView(
            tv(
                "Allāhumma rabba hādzihid-da'watit-tāmmah, wash-shalātil-qā'imah, āti Muḥammadanil-wasīlata wal-fadhīlah, wab'atshu maqāmam maḥmūdanil-ladzī wa'adtah.",
                13.5f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            )
        )
        doaBox.addView(
            tv(
                "\"Ya Allah, Tuhan pemilik panggilan yang sempurna ini dan shalat yang didirikan, berilah Nabi Muhammad wasilah dan keutamaan, serta bangkitkanlah beliau di tempat terpuji yang telah Engkau janjikan.\"",
                12.5f, mute, Typeface.SANS_SERIF
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        contentCol.addView(doaBox, LinearLayout.LayoutParams(-1, -2))

        scroll.addView(contentCol, FrameLayout.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        btnDismissAdzan = tv("Lanjutkan Menonton (Tekan OK)", 14.5f, 0xFF100E0C.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(11), dp(24), dp(11))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(brassLight)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (f) Color.WHITE else brassLight)
                }
            }
            setOnClickListener { endAdzan() }
        }
        val blp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        }
        card.addView(btnDismissAdzan, blp)

        val cardLp = if (isPhone) {
            FrameLayout.LayoutParams(-1, dp(460), Gravity.CENTER).apply {
                setMargins(dp(18), dp(24), dp(18), dp(24))
            }
        } else {
            FrameLayout.LayoutParams(dp(760), dp(470), Gravity.CENTER)
        }
        adzanOverlay.addView(card, cardLp)
        root.addView(adzanOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun isRamadanNow(): Boolean {
        for ((_, v) in prefs.all) {
            if (v is String && v.equals("ramadan", ignoreCase = true)) return true
        }
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_MONTH)
        var m = cal.get(Calendar.MONTH) + 1
        var y = cal.get(Calendar.YEAR)
        if (m < 3) {
            y -= 1
            m += 12
        }
        val a = y / 100
        val b = 2 - a + (a / 4)
        val jd = (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + day + b - 1524
        val l = jd - 1948440 + 10632
        val n = (l - 1) / 10631
        val l2 = l - 10631 * n + 354
        val j = ((10985 - l2) / 5316) * ((50 * l2) / 17719) + (l2 / 5670) * ((43 * l2) / 15238)
        val l3 = l2 - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val hijriMonth = (24 * l3) / 709
        return hijriMonth == 9
    }

    private fun prayerOffset(): Int =
        (prefs.getString("adzan_offset", "0") ?: "0").toIntOrNull()?.coerceIn(-15, 15) ?: 0

    private val tick = object : Runnable {
        override fun run() {
            try {
                val today = Prayer.todayStr()
                if (prayerDay != today) {
                    prayerDay = today
                    Thread {
                        val pt = Prayer.fetch(this@VodPlayerActivity)
                        ui.post { prayerTimes = pt }
                    }.start()
                }
                checkPrayer()
            } catch (_: Exception) {
            }
            ui.postDelayed(this, 30000L)
        }
    }

    private fun checkPrayer() {
        if (prefs.getString("adzan", "Ya") == "Tidak") return
        val t = prayerTimes ?: return
        if (t.size < 5) return
        val off = prayerOffset()
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val day = Prayer.todayStr()

        if (isRamadanNow()) {
            val magrib = t[3] + off
            if (now >= magrib - 15 && now < magrib - 1 && prefs.getString("ramadan_dzikir_done", "") != day) {
                prefs.edit().putString("ramadan_dzikir_done", day).apply()
                if (player?.isPlaying == true) {
                    val sisa = magrib - now
                    notice.show(
                        "Menjelang Berbuka Puasa ($sisa menit lagi) - Dzikir Dianjurkan",
                        "Perbanyak Istighfar, Tasbih & baca:\n\"Asyhadu allā ilāha illallāh, astaghfirullāh, as'alukal-jannata wa a'ūdzu bika minan-nār.\"",
                        35000L
                    )
                }
            }
        }

        for (i in 0..4) {
            val pt = t[i] + off
            if (now >= pt && now < pt + 2) {
                val tag = day + "#" + i
                if (prefs.getString("adzan_done", "") == tag) return
                prefs.edit().putString("adzan_done", tag).apply()
                startAdzanCountdown(i, pt)
                return
            }
        }
    }

    private fun startAdzanCountdown(i: Int, minute: Int) {
        if (player?.isPlaying != true && !pausedForAdzan) return
        adzanCountdownRunnable?.let { ui.removeCallbacks(it) }
        var secLeft = 5
        val r = object : Runnable {
            override fun run() {
                if (isFinishing) {
                    countdownBanner.visibility = View.GONE
                    return
                }
                if (secLeft > 0) {
                    countdownBanner.text = "Adzan ${Prayer.NAMES[i]} dalam $secLeft detik..."
                    countdownBanner.visibility = View.VISIBLE
                    secLeft--
                    ui.postDelayed(this, 1000L)
                } else {
                    countdownBanner.visibility = View.GONE
                    startAdzan(i, minute)
                }
            }
        }
        adzanCountdownRunnable = r
        ui.post(r)
    }

    private val adzanEnd = Runnable { endAdzan() }

    private fun startAdzan(i: Int, minute: Int) {
        val p = player ?: return
        cancelPauseNightMode()
        if (isNightModeOpen) {
            isNightModeOpen = false
            ui.removeCallbacks(nightClockTickRunnable)
            nightModeOverlay.visibility = View.GONE
        }
        wasPlayingBeforeAdzan = p.isPlaying || p.playWhenReady
        pausedForAdzan = true
        p.pause()

        val mins = (prefs.getString("adzan_min", "4") ?: "4").toIntOrNull()?.coerceIn(1, 30) ?: 4
        val isMagribRamadan = (i == 3 && isRamadanNow())
        adzanTitleView.text = if (isMagribRamadan) {
            "WAKTU MAGRIB - SELAMAT BERBUKA PUASA (${Prayer.fmt(minute)})"
        } else {
            "WAKTU ADZAN ${Prayer.NAMES[i].uppercase()} - ${Prayer.fmt(minute)}"
        }
        ramadanBox.visibility = if (isMagribRamadan) View.VISIBLE else View.GONE
        adzanOverlay.visibility = View.VISIBLE
        btnDismissAdzan.requestFocus()

        ui.removeCallbacks(adzanEnd)
        ui.postDelayed(adzanEnd, mins * 60000L)
        val url = (prefs.getString("adzan_url", "") ?: "").trim()
        if (url.startsWith("http")) {
            try {
                val ap = ExoPlayer.Builder(this).build()
                ap.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) endAdzan()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                    }
                })
                ap.setMediaItem(MediaItem.fromUri(url))
                ap.prepare()
                ap.playWhenReady = true
                adzanPlayer = ap
                ui.removeCallbacks(adzanEnd)
                ui.postDelayed(adzanEnd, 10L * 60000L)
            } catch (_: Exception) {
            }
        }
    }

    private fun endAdzan() {
        adzanCountdownRunnable?.let { ui.removeCallbacks(it) }
        countdownBanner.visibility = View.GONE
        adzanOverlay.visibility = View.GONE
        ui.removeCallbacks(adzanEnd)
        adzanPlayer?.release()
        adzanPlayer = null
        if (!pausedForAdzan) return
        pausedForAdzan = false
        notice.hide()
        if (wasPlayingBeforeAdzan && !isFinishing) {
            player?.playWhenReady = true
            player?.play()
        } else if (player?.isPlaying != true) {
            schedulePauseNightMode()
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
        if (adzanOverlay.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BACK
            ) {
                if (event.action == KeyEvent.ACTION_UP) endAdzan()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isNightModeOpen) {
            val isResumeKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            if (event.action == KeyEvent.ACTION_UP) {
                exitNightMode(resumePlayback = isResumeKey)
            }
            return true
        }

        if (player?.isPlaying == false && event.action == KeyEvent.ACTION_UP) {
            schedulePauseNightMode()
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 15000L)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        cancelPauseNightMode()
        ui.removeCallbacks(nightClockTickRunnable)
        if (pausedForAdzan) {
            wasPlayingBeforeAdzan = false
            endAdzan()
        }
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos > 60000L && (dur <= 0L || pos < dur - 180000L)) {
            prefs.edit().putLong(resumeKey, pos).apply()
            ContinueWatch.saveFromIntent(this, intent, pos)
        }
        p.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        adzanPlayer?.release()
        adzanPlayer = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
