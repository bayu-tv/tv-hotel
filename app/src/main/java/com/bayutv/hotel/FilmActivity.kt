package com.bayutv.hotel

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class FilmActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()
    private val mute = 0xFFB5AEA0.toInt()
    private val bgDark = 0xFF0B0A09.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private val heroCache = LruCache<String, Bitmap>(6)
    private val resolvedBackdrops = HashMap<String, String>()
    private var resolvedImdbId: String = ""
    private var pendingHeroRunnable: Runnable? = null

    private val tabs = arrayOf("Beranda", "Film", "Anime & Serial", "Indonesia", "YouTube Resmi", "Koleksi Saya", "Daftar Saya", "Cari")
    private val searchIdx = 7
    private val tabViews = ArrayList<TextView>()
    private var tabIdx = -1
    private var token = 0
    private var jfTitles: Set<String> = emptySet()

    private lateinit var heroBackdrop: ImageView
    private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroOverview: TextView

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private var searchDebounce: Runnable? = null

    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout

    private lateinit var detailOverlay: FrameLayout
    private lateinit var detailBackdrop: ImageView
    private lateinit var detailPoster: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailOverview: TextView
    private lateinit var detailProviders: TextView
    private lateinit var detailActions: LinearLayout

    private lateinit var notice: Notice
    private var focusedMovie: Movie? = null

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun buildBBoxLogo(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(22), 0)
        }
        val iconFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(brassLight, brass)
            ).apply {
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0x66FFFFFF)
            }
        }
        val iconLetter = tv("B", 16f, 0xFF0E0C0A.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
        }
        iconFrame.addView(iconLetter, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        box.addView(iconFrame, LinearLayout.LayoutParams(dp(28), dp(28)))

        val brandText = tv("B-BOX", 19f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.16f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(10), 0, 0, 0)
        }
        box.addView(brandText)
        return box
    }

    private fun upgradeImageUrl(url: String?, isBackdrop: Boolean): String? {
        if (url.isNullOrBlank() || url == "null") return null
        val trimmed = url.trim()
        val targetSize = if (isBackdrop) "/w1280/" else "/w780/"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "https://image.tmdb.org/t/p" + (if (isBackdrop) "/w1280" else "/w780") + cleanPath
        }
        return trimmed
            .replace("/w185/", targetSize)
            .replace("/w300/", targetSize)
            .replace("/w342/", targetSize)
            .replace("/w500/", targetSize)
            .replace("/w780/", targetSize)
    }

    private fun loadHeroImage(rawUrl: String?, isBackdrop: Boolean, target: ImageView, fallbackDrawable: Drawable? = null) {
        val hdUrl = upgradeImageUrl(rawUrl, isBackdrop)
        if (hdUrl == null) {
            target.tag = null
            target.setImageDrawable(fallbackDrawable)
            return
        }
        target.tag = hdUrl
        heroCache.get(hdUrl)?.let { cached ->
            target.setImageBitmap(cached)
            return
        }
        Thread {
            try {
                val conn = URL(hdUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "B-BOX-TV")
                val bytes = conn.inputStream.use { it.readBytes() }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                var sample = 1
                while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    heroCache.put(hdUrl, bmp)
                    ui.post {
                        if (target.tag == hdUrl && !isFinishing) {
                            target.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                if (fallbackDrawable != null) {
                    ui.post {
                        if (target.tag == hdUrl && !isFinishing) {
                            target.setImageDrawable(fallbackDrawable)
                        }
                    }
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(bgDark)

        val heroFrame = FrameLayout(this)
        heroBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF141210.toInt())
        }
        heroFrame.addView(heroBackdrop, FrameLayout.LayoutParams(-1, dp(430), Gravity.TOP))

        val fadeLeft = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF80B0A09.toInt(), 0xD80B0A09.toInt(), 0x550B0A09, 0x000B0A09)
            )
        }
        heroFrame.addView(fadeLeft, FrameLayout.LayoutParams(-1, dp(430), Gravity.TOP))

        val fadeBottom = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAA0B0A09.toInt(), 0x000B0A09, 0xCC0B0A09.toInt(), bgDark)
            )
        }
        heroFrame.addView(fadeBottom, FrameLayout.LayoutParams(-1, dp(435), Gravity.TOP))
        root.addView(heroFrame, FrameLayout.LayoutParams(-1, dp(435), Gravity.TOP))

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(44), dp(18), dp(44), dp(8))
        }
        topBar.addView(buildBBoxLogo())

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (i in tabs.indices) {
            val t = tv(tabs[i], 14f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(15), dp(7), dp(15), dp(7))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    styleTab(this, i, hasFocus)
                    if (hasFocus) selectTab(i)
                }
                setOnClickListener {
                    selectTab(i)
                    if (i == searchIdx) {
                        searchInput.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(6)
            tabStrip.addView(t, lp)
            tabViews.add(t)
        }
        tabScroll.addView(tabStrip)
        topBar.addView(tabScroll, LinearLayout.LayoutParams(0, -2, 1f))
        mainCol.addView(topBar)

        buildSearchPanel(mainCol)

        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(8), dp(44), dp(10))
        }
        heroBadge = tv("B-BOX STREAMING - BEBAS IKLAN - SUB INDO", 11f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.14f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        heroTitle = tv("Pilih Film atau Anime", 33f, ivory, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
            setPadding(0, dp(2), 0, dp(4))
        }
        heroMeta = tv("", 13f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        heroOverview = tv(
            "Geser remote ke poster di bawah untuk melihat sinopsis, lalu tekan OK untuk menonton tanpa iklan.",
            13f,
            0xFFE6E0D6.toInt(),
            Typeface.SANS_SERIF
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(0, dp(6), 0, 0)
        }
        heroInfo.addView(heroBadge)
        heroInfo.addView(heroTitle)
        heroInfo.addView(heroMeta)
        heroInfo.addView(heroOverview, LinearLayout.LayoutParams(dp(560), -2))
        mainCol.addView(heroInfo)

        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
        }
        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(32))
        }
        rowsScroll.addView(rowsBox, FrameLayout.LayoutParams(-1, -2))
        mainCol.addView(rowsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(mainCol, FrameLayout.LayoutParams(-1, -1))
        buildDetailOverlay(root)

        notice = Notice(this, root)
        setContentView(root)

        if (Jellyfin.configured(this)) {
            Thread {
                val jf = Jellyfin.latest(this) ?: emptyList()
                jfTitles = jf.map { norm(it.title) }.toSet()
            }.start()
        }

        tabViews[0].requestFocus()
        if (Tmdb.key(this).isEmpty()) {
            notice.show(
                "Poster Asli Belum Aktif",
                "Isi Kunci API TMDB gratis di Pengaturan lewat HP agar semua poster & latar film tampil tajam.",
                5500L
            )
        }
    }

    private fun buildSearchPanel(parent: LinearLayout) {
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(44), dp(6), dp(44), dp(8))
        }

        val barBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(14), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xE6181614.toInt())
                setStroke(dp(1), brass)
            }
        }

        searchInput = EditText(this).apply {
            hint = "Ketik judul film, anime, atau drama..."
            setHintTextColor(0xFF8C8578.toInt())
            setTextColor(ivory)
            textSize = 15f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, dp(8), dp(12), dp(8))
        }

        val clearBtn = tv("Hapus", 12f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x22FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                setTextColor(if (f) bgDark else mute)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (f) brassLight else 0x22FFFFFF)
                }
            }
            setOnClickListener {
                searchInput.setText("")
                searchInput.requestFocus()
            }
        }

        barBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        barBox.addView(clearBtn)
        searchPanel.addView(barBox, LinearLayout.LayoutParams(dp(620), -2))

        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val quickTags = listOf("Spider-Man", "Anime", "Korea", "Indonesia", "Action", "Komedi", "Horor", "Marvel")
        for (tag in quickTags) {
            val chip = tv(tag, 12f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0x24FFFFFF)
                    setStroke(dp(1), 0x44FFFFFF)
                }
                setOnFocusChangeListener { _, f ->
                    setTextColor(if (f) bgDark else ivory)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(if (f) brassLight else 0x24FFFFFF)
                    }
                }
                setOnClickListener {
                    searchInput.setText(tag)
                    runLiveSearch(tag)
                }
            }
            val clp = LinearLayout.LayoutParams(-2, -2)
            clp.rightMargin = dp(8)
            chipsRow.addView(chip, clp)
        }
        chipsScroll.addView(chipsRow)
        searchPanel.addView(chipsScroll)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchDebounce?.let { ui.removeCallbacks(it) }
                if (q.length >= 2) {
                    val r = Runnable { runLiveSearch(q) }
                    searchDebounce = r
                    ui.postDelayed(r, 450L)
                }
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) runLiveSearch(q)
                true
            } else {
                false
            }
        }

        parent.addView(searchPanel)
    }

    private fun runLiveSearch(q: String) {
        val my = ++token
        rowsBox.removeAllViews()
        loadCategoryRows(
            my,
            listOf(Triple("Hasil Pencarian: \"$q\"", "/search/multi", "language=id-ID&include_adult=false&query=" + URLEncoder.encode(q, "UTF-8"))),
            "movie"
        )
    }

    override fun onRestart() {
        super.onRestart()
        if (tabIdx == 0) {
            val prev = tabIdx
            tabIdx = -1
            selectTab(prev)
        }
    }

    private fun styleTab(v: TextView, idx: Int, focused: Boolean) {
        val active = idx == tabIdx
        v.setTextColor(if (focused) bgDark else if (active) brassLight else mute)
        val g = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (focused) {
                setColor(brassLight)
                setStroke(dp(2), Color.WHITE)
            } else if (active) {
                setColor(0x33B8975A)
                setStroke(dp(1), brass)
            } else {
                setColor(0x18000000)
            }
        }
        v.background = g
    }

    private fun refreshTabStyles() {
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
    }

    private fun scheduleHero(m: Movie, cw: ContinueWatch.Entry? = null) {
        focusedMovie = m
        pendingHeroRunnable?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            if (focusedMovie == m && !isFinishing) {
                renderHero(m, cw)
            }
        }
        pendingHeroRunnable = r
        ui.postDelayed(r, 110L)
    }

    private fun renderHero(m: Movie, cw: ContinueWatch.Entry? = null) {
        heroTitle.text = m.title
        val r = if (m.rating > 0) String.format(Locale.US, "%.1f", m.rating) else "-"
        val typeTag = when (m.kind) {
            "tv" -> "SERIAL / ANIME"
            "jf_movie", "jf_series", "jf_episode" -> "KOLEKSI SAYA (JELLYFIN)"
            else -> "FILM"
        }
        val cwTag = when {
            cw != null && cw.posMs > 60000L -> "  |  LANJUTKAN MENIT KE-${cw.posMs / 60000L}"
            cw != null -> "  |  TERAKHIR DITONTON"
            else -> ""
        }
        val hasLocal = !m.isJellyfin && jfTitles.contains(norm(m.title))
        heroBadge.text = typeTag + cwTag + (if (hasLocal) "  |  TERSEDIA DI KOLEKSI SAYA" else "  |  BEBAS IKLAN - SUB INDO")
        heroMeta.text = (if (m.year.isNotEmpty()) m.year + "   |   " else "") + "\u2605 " + r + "   |   Tekan OK untuk Tonton"
        heroOverview.text = if (m.overview.isNotBlank()) m.overview else "Tekan OK untuk melihat detail lengkap, pilihan episode, dan Subtitle Indonesia otomatis."

        val key = watchKey(m)
        val bdUrl = resolvedBackdrops[key] ?: m.backdrop
        if (!bdUrl.isNullOrBlank() && bdUrl != "null") {
            loadHeroImage(bdUrl, true, heroBackdrop)
        } else {
            loadHeroImage(m.poster, false, heroBackdrop)
            if (m.id > 0 && !m.isJellyfin && Tmdb.key(this).isNotEmpty()) {
                Thread {
                    val path = "/" + (if (m.kind == "tv") "tv" else "movie") + "/" + m.id
                    val json = Tmdb.get(this, path, "language=id-ID") ?: return@Thread
                    val bp = try { JSONObject(json).optString("backdrop_path", "") } catch (e: Exception) { "" }
                    if (bp.isNotEmpty() && bp != "null") {
                        val fullBd = "https://image.tmdb.org/t/p/w1280$bp"
                        resolvedBackdrops[key] = fullBd
                        ui.post {
                            if (focusedMovie == m && !isFinishing) {
                                loadHeroImage(fullBd, true, heroBackdrop)
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun selectTab(i: Int) {
        if (i == tabIdx) return
        tabIdx = i
        focusedMovie = null
        refreshTabStyles()
        val my = ++token
        rowsBox.removeAllViews()
        rowsScroll.scrollTo(0, 0)

        searchPanel.visibility = if (i == searchIdx) View.VISIBLE else View.GONE

        when (i) {
            0 -> loadHomeRows(my)
            1 -> loadCategoryRows(
                my,
                listOf(
                    Triple("Film Populer", "/movie/popular", "language=id-ID&region=ID&page=1"),
                    Triple("Sedang Tayang di Bioskop", "/movie/now_playing", "language=id-ID&region=ID&page=1"),
                    Triple("Rating Tertinggi", "/movie/top_rated", "language=id-ID&region=ID&page=1"),
                    Triple("Akan Tayang", "/movie/upcoming", "language=id-ID&region=ID&page=1")
                ),
                "movie"
            )
            2 -> loadCategoryRows(
                my,
                listOf(
                    Triple("Anime Populer", "/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=1"),
                    Triple("Anime Rating Tertinggi", "/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=vote_average.desc&vote_count.gte=150&page=1"),
                    Triple("Serial TV Populer", "/tv/popular", "language=id-ID&page=1"),
                    Triple("Drama Korea Populer", "/discover/tv", "language=id-ID&with_original_language=ko&sort_by=popularity.desc&page=1")
                ),
                "tv"
            )
            3 -> loadCategoryRows(
                my,
                listOf(
                    Triple("Film Indonesia Terbaru", "/discover/movie", "language=id-ID&with_original_language=id&sort_by=primary_release_date.desc&vote_count.gte=3&page=1"),
                    Triple("Film Indonesia Terpopuler", "/discover/movie", "language=id-ID&with_original_language=id&sort_by=popularity.desc&page=1")
                ),
                "movie"
            )
            4 -> loadYoutubeOfficialTab(my)
            5 -> loadJellyfinTab(my)
            6 -> {
                val wl = loadWatch()
                if (wl.isEmpty()) {
                    notice.show("Daftar saya kosong", "Buka detail film, lalu pilih Tambah ke Daftar saya.", 3500L)
                } else {
                    notice.hide()
                    addCarouselRow("Daftar Saya (${wl.size})", wl)
                }
            }
            searchIdx -> {
                notice.hide()
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) {
                    runLiveSearch(q)
                } else {
                    loadCategoryRows(
                        my,
                        listOf(Triple("Pencarian Populer Minggu Ini", "/trending/all/day", "language=id-ID")),
                        "movie"
                    )
                }
            }
        }
    }

    private fun loadYoutubeOfficialTab(my: Int) {
        notice.hide()
        addYoutubeRow(my, "Anime Sub Indo Resmi (Muse & Ani-One)", YoutubeCatalog.ANIME_PICKS)
        addYoutubeRow(my, "Drama China Sub Indo Resmi (WeTV, iQIYI & YOUKU)", YoutubeCatalog.DRACIN_PICKS)
        addYoutubeRow(my, "Semua Playlist Kanal Resmi", YoutubeCatalog.OFFICIAL_CHANNELS)

        Thread {
            val latest = YoutubeCatalog.fetchLatestRss(this)
            if (latest.isNotEmpty() && my == token && !isFinishing) {
                ui.post {
                    if (my == token && tabIdx == 4 && !isFinishing) {
                        addYoutubeRow(my, "Episode Terbaru Otomatis (RSS Resmi)", latest)
                    }
                }
            }
        }.start()
    }

    private fun addYoutubeRow(my: Int, rowTitle: String, items: List<YoutubeCatalog.Entry>)
