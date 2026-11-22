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
import android.text.InputType
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

    private val heroCache = LruCache<String, Bitmap>(4)
    private val resolvedBackdrops = HashMap<String, String>()
    private var resolvedImdbId: String = ""

    private val tabs = arrayOf(
        "Beranda", "Film", "Anime & Serial", "Indonesia",
        "YouTube Resmi", "Koleksi Saya", "Daftar Saya", "Cari"
    )
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

    private data class RankedStream(
        val source: StremioAddons.StreamSource,
        val score: Int,
        val isWebDl: Boolean
    )

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
        val iconLetter = tv(
            "B", 16f, 0xFF0E0C0A.toInt(),
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply { gravity = Gravity.CENTER }
        iconFrame.addView(iconLetter, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        box.addView(iconFrame, LinearLayout.LayoutParams(dp(28), dp(28)))

        val brandText = tv(
            "B-BOX", 19f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            letterSpacing = 0.16f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(10), 0, 0, 0)
        }
        box.addView(brandText)
        return box
    }

    private fun upgradeImageUrl(url: String?, isBackdrop: Boolean): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()
        val targetSize = if (isBackdrop) "w1280" else "w780"
        if (clean.startsWith("/")) {
            return "https://image.tmdb.org/t/p/$targetSize$clean"
        }
        return clean
            .replace("/w185/", "/$targetSize/")
            .replace("/w300/", "/$targetSize/")
            .replace("/w342/", "/$targetSize/")
            .replace("/w500/", "/$targetSize/")
            .replace("/w780/", "/$targetSize/")
    }

    private fun loadHeroImage(
        rawUrl: String?,
        isBackdrop: Boolean,
        target: ImageView,
        fallbackDrawable: Drawable? = null
    ) {
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

    private val applyHeroRunnable = Runnable {
        val m = focusedMovie ?: return@Runnable
        renderHero(m)
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
            clipChildren = true
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
            val t = tv(
                tabs[i], 14f, mute,
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            ).apply {
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

        val addonBtn = tv(
            "Addon", 12f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x28B8975A)
                setStroke(dp(1), brass)
            }
            setOnFocusChangeListener { _, f ->
                setTextColor(if (f) bgDark else brassLight)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (f) {
                        setColor(brassLight)
                        setStroke(dp(2), Color.WHITE)
                    } else {
                        setColor(0x28B8975A)
                        setStroke(dp(1), brass)
                    }
                }
            }
            setOnClickListener { showAddonConfigDialog() }
        }
        val alp = LinearLayout.LayoutParams(-2, -2)
        alp.leftMargin = dp(10)
        topBar.addView(addonBtn, alp)
        val domainAnimeBtn = tv(
            "Domain Anime", 12f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x28B8975A)
                setStroke(dp(1), brass)
            }
            setOnFocusChangeListener { _, f ->
                setTextColor(if (f) bgDark else brassLight)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (f) {
                        setColor(brassLight)
                        setStroke(dp(2), Color.WHITE)
                    } else {
                        setColor(0x28B8975A)
                        setStroke(dp(1), brass)
                    }
                }
            }
            setOnClickListener {
                AnimeIndoProvider.showDomainDialog(this@FilmActivity) { newUrl ->
                    notice.show("Domain Anime Diperbarui", newUrl, 3000L)
                }
            }
        }
        val dlp = LinearLayout.LayoutParams(-2, -2)
        dlp.leftMargin = dp(8)
        topBar.addView(domainAnimeBtn, dlp)

        mainCol.addView(topBar)
        buildSearchPanel(mainCol)

        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(8), dp(44), dp(14))
        }
        heroBadge = tv(
            "B-BOX STREAMING - TORRENTIO WEB-DL & STREMIO - SUB INDO", 11f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            letterSpacing = 0.14f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        heroTitle = tv(
            "Pilih Film atau Anime", 33f, ivory,
            Typeface.create(Typeface.SERIF, Typeface.BOLD)
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
            setPadding(0, dp(2), 0, dp(4))
        }
        heroMeta = tv(
            "", 13f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply { setShadowLayer(6f, 0f, 2f, Color.BLACK) }
        heroOverview = tv(
            "Geser remote ke poster di bawah untuk melihat sinopsis, lalu tekan OK untuk menonton tanpa iklan.",
            13f, 0xFFE6E0D6.toInt(), Typeface.SANS_SERIF
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
            clipToPadding = true
            clipChildren = true
        }
        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(32))
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

    private fun showAddonConfigDialog() {
        val currentUrls = StremioAddons.getAddonUrls(this).joinToString("\n")
        val input = EditText(this).apply {
            setText(currentUrls)
            hint = "https://torrentio.strem.fun/sort=seeders|qualityfilter=4k,scr,cam"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Konfigurasi Torrentio / Stremio Addon")
            .setMessage("Masukkan URL Addon (satu per baris, mendukung Torrentio P2P & Stremio HTTP):")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                StremioAddons.saveAddonUrls(this, input.text.toString())
                val total = StremioAddons.getAddonUrls(this).size
                notice.show("Addon Tersimpan", "$total sumber Addon aktif.", 3000L)
            }
            .setNeutralButton("Reset Default") { _, _ ->
                StremioAddons.saveAddonUrls(this, "")
                notice.show("Reset Berhasil", "Kembali ke Torrentio bawaan.", 3000L)
            }
            .setNegativeButton("Batal", null)
            .show()
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

        val clearBtn = tv(
            "Hapus", 12f, mute,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
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
            val chip = tv(
                tag, 12f, ivory,
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            ).apply {
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
        Thread {
            val animeHits = AnimeIndoProvider.search(this, q).take(16)
            if (animeHits.isNotEmpty() && my == token && !isFinishing) {
                ui.post {
                    if (my == token && !isFinishing) {
                        notice.hide()
                        addAnimeIndoRow("Anime Sub-Indo: \"$q\"", animeHits)
                    }
                }
            }
        }.start()
        val enc = URLEncoder.encode(q, "UTF-8")
        loadCategoryRows(
            my,
            listOf(Triple("Hasil TMDB: \"$q\"", "/search/multi", "language=id-ID&include_adult=false&query=$enc")),
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
        v.background = GradientDrawable().apply {
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
    }

    private fun refreshTabStyles() {
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
    }

    private fun scheduleHero(m: Movie, cw: ContinueWatch.Entry? = null) {
        focusedMovie = m
        ui.removeCallbacks(applyHeroRunnable)
        ui.postDelayed({ renderHero(m, cw) }, 140L)
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
        heroBadge.text = typeTag + cwTag + (if (hasLocal) "  |  TERSEDIA DI KOLEKSI SAYA" else "  |  TORRENTIO WEB-DL - SUB INDO")
        heroMeta.text = (if (m.year.isNotEmpty()) m.year + "   |   " else "") + "\u2605 " + r + "   |   Tekan OK untuk Tonton"
        heroOverview.text = m.overview.ifBlank {
            "Tekan OK untuk melihat detail lengkap, pilihan episode, dan Subtitle Indonesia otomatis."
        }

        val key = watchKey(m)
        val bdUrl = resolvedBackdrops[key] ?: m.backdrop
        if (!bdUrl.isNullOrBlank()) {
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
            2 -> {
                loadAnimeIndoRows(my)
                loadCategoryRows(
                    my,
                    listOf(
                        Triple("Anime Populer (TMDB)", "/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=1"),
                        Triple("Anime Rating Tertinggi (TMDB)", "/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=vote_average.desc&vote_count.gte=150&page=1"),
                        Triple("Serial TV Populer", "/tv/popular", "language=id-ID&page=1"),
                        Triple("Drama Korea Populer", "/discover/tv", "language=id-ID&with_original_language=ko&sort_by=popularity.desc&page=1")
                    ),
                    "tv"
                )
            }
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

    private fun loadAnimeIndoRows(my: Int) {
        notice.show(null, "Memuat katalog Anime Sub-Indo...")
        Thread {
            val ongoing = AnimeIndoProvider.fetchOngoing(this).take(16)
            if (ongoing.isNotEmpty() && my == token && !isFinishing) {
                ui.post {
                    if (my == token && !isFinishing) {
                        notice.hide()
                        addAnimeIndoRow("Anime Sub-Indo Terbaru (Ongoing)", ongoing)
                    }
                }
            }
            val complete = AnimeIndoProvider.fetchComplete(this).take(16)
            if (complete.isNotEmpty() && my == token && !isFinishing) {
                ui.post {
                    if (my == token && !isFinishing) {
                        notice.hide()
                        addAnimeIndoRow("Anime Sub-Indo Tamat (Full Episode)", complete)
                    }
                }
            }
        }.start()
    }

    private fun addAnimeIndoRow(rowTitle: String, items: List<AnimeIndoProvider.AnimeItem>) {
        if (items.isEmpty()) return
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(10))
        }
        val header = tv(
            rowTitle, 17f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(44), 0, dp(44), dp(6))
        }
        section.addView(header)

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(44), dp(8), dp(44), dp(8))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                isFocusable = true
                isClickable = true
            }
            val posterFrame = FrameLayout(this).apply {
                foreground = posterStroke(false)
            }
            roundPoster(posterFrame, 10)

            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1B1916.toInt())
            }
            posterFrame.addView(img, FrameLayout.LayoutParams(-1, dp(192)))

            if (item.badge.isNotEmpty()) {
                val b = tv(
                    item.badge, 9.5f, brassLight,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                ).apply {
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0xDD0B0A09.toInt())
                    }
                }
                val blp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
                blp.setMargins(dp(6), dp(6), dp(6), 0)
                posterFrame.addView(b, blp)
            }

            card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(192)))

            val title = tv(
                item.title, 12f, mute,
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            ).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }
            card.addView(title)

            card.setOnFocusChangeListener { _, hasFocus ->
                posterFrame.foreground = posterStroke(hasFocus)
                title.setTextColor(if (hasFocus) ivory else mute)
                card.z = if (hasFocus) 8f else 0f
                card.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(130)
                    .start()
                if (hasFocus) {
                    focusedMovie = null
                    ui.removeCallbacks(applyHeroRunnable)
                    heroTitle.text = item.title
                    heroBadge.text = "ANIME SUB-INDO  |  ${item.status.uppercase()}  |  BEBAS IKLAN"
                    heroMeta.text = "${item.badge}   |   Tekan OK untuk Pilih Episode"
                    heroOverview.text = "Tonton seluruh episode ${item.title} dengan Subtitle Indonesia langsung di pemutar B-BOX Native."
                    if (item.poster.isNotEmpty()) loadHeroImage(item.poster, false, heroBackdrop)
                    val parentRow = card.parent?.parent?.parent as? View
                    if (parentRow != null) {
                        rowsScroll.smoothScrollTo(0, (parentRow.top - dp(10)).coerceAtLeast(0))
                    }
                }
            }

            card.setOnClickListener {
                AnimeIndoProvider.openEpisodeSelector(
                    context = this,
                    item = item,
                    onStatus = { t, msg, ms ->
                        if (ms > 0L) notice.show(t, msg, ms) else notice.show(t, msg)
                    },
                    onHideStatus = { notice.hide() }
                )
            }

            if (item.poster.isNotEmpty()) Images.load(item.poster, img)
            val lp = LinearLayout.LayoutParams(dp(136), -2)
            lp.rightMargin = dp(16)
            strip.addView(card, lp)
        }

        hScroll.addView(strip)
        section.addView(hScroll)
        rowsBox.addView(section)
    }

    private fun addYoutubeRow(my: Int, rowTitle: String, items: List<YoutubeCatalog.Entry>) {
        if (items.isEmpty()) return
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(10))
        }
        val header = tv(
            rowTitle, 17f, ivory,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(44), 0, dp(44), dp(6))
        }
        section.addView(header)

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(44), dp(8), dp(44), dp(8))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        for ((idx, item) in items.withIndex()) {
            val card = buildYoutubeCard(my, item, idx == 0 && rowTitle.startsWith("Anime"))
            val lp = LinearLayout.LayoutParams(dp(136), -2)
            lp.rightMargin = dp(16)
            strip.addView(card, lp)
        }

        hScroll.addView(strip)
        section.addView(hScroll)
        rowsBox.addView(section)
    }

    private fun roundPoster(v: View, radiusDp: Int) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
        v.clipToOutline = true
    }

    private fun buildYoutubeCard(my: Int, item: YoutubeCatalog.Entry, isFirstHero: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            isFocusable = true
            isClickable = true
        }

        val posterFrame = FrameLayout(this).apply {
            foreground = posterStroke(false)
        }
        roundPoster(posterFrame, 10)

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(YoutubeCatalog.fallbackPoster(this@FilmActivity, item))
        }
        posterFrame.addView(img, FrameLayout.LayoutParams(-1, dp(192)))

        val badge = tv(
            item.channelName, 9f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0xDD0B0A09.toInt())
            }
        }
        val blp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        blp.setMargins(dp(6), dp(6), dp(6), 0)
        posterFrame.addView(badge, blp)

        card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(192)))

        val title = tv(
            item.title, 12f, mute,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        card.addView(title)

        if (item.posterUrl != null) {
            Images.load(item.posterUrl, img)
        }

        if (item.tmdbId > 0) {
            Thread {
                val ok = YoutubeCatalog.hydrateFromTmdb(this, item)
                if (ok && my == token && !isFinishing) {
                    ui.post {
                        item.posterUrl?.let { Images.load(it, img) }
                        if (isFirstHero || card.hasFocus()) renderYoutubeHero(item)
                    }
                }
            }.start()
        }

        if (isFirstHero) renderYoutubeHero(item)

        card.setOnFocusChangeListener { _, hasFocus ->
            posterFrame.foreground = posterStroke(hasFocus)
            title.setTextColor(if (hasFocus) ivory else mute)
            card.z = if (hasFocus) 8f else 0f
            card.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(130)
                .start()
            if (hasFocus) {
                renderYoutubeHero(item)
                val parentRow = card.parent?.parent?.parent as? View
                if (parentRow != null) {
                    rowsScroll.smoothScrollTo(0, (parentRow.top - dp(10)).coerceAtLeast(0))
                }
            }
        }

        card.setOnClickListener { openYoutubeDetail(item) }
        return card
    }

    private fun renderYoutubeHero(item: YoutubeCatalog.Entry) {
        focusedMovie = null
        ui.removeCallbacks(applyHeroRunnable)
        heroTitle.text = item.title
        val r = if (item.rating > 0) "   |   \u2605 " + String.format(Locale.US, "%.1f", item.rating) else ""
        val yr = if (item.year.isNotEmpty()) item.year + "   |   " else ""
        heroBadge.text = "YOUTUBE RESMI  |  ${item.channelName.uppercase()}  |  SUB INDO"
        heroMeta.text = yr + item.category + r + "   |   Gratis & Legal"
        heroOverview.text = item.overview.ifBlank {
            "Tonton episode resmi ${item.title} dengan Subtitle Indonesia jernih melalui kanal ${item.channelName} di YouTube TV."
        }
        val fallback = YoutubeCatalog.fallbackPoster(this, item)
        if (!item.backdropUrl.isNullOrBlank()) {
            loadHeroImage(item.backdropUrl, true, heroBackdrop, fallback)
        } else if (!item.posterUrl.isNullOrBlank()) {
            loadHeroImage(item.posterUrl, false, heroBackdrop, fallback)
        } else {
            heroBackdrop.tag = null
            heroBackdrop.setImageDrawable(fallback)
        }
    }

    private fun openYoutubeDetail(item: YoutubeCatalog.Entry) {
        detailTitle.text = item.title
        val r = if (item.rating > 0) "  |  \u2605 " + String.format(Locale.US, "%.1f", item.rating) else ""
        val yr = if (item.year.isNotEmpty()) item.year + "  |  " else ""
        detailMeta.text = yr + item.category + r + "  |  Subtitle Indonesia"
        detailOverview.text = item.overview.ifBlank {
            "Tayangan resmi dari ${item.channelName}. Pilih tombol di bawah untuk langsung membuka daftar episode Sub Indo di aplikasi YouTube."
        }
        detailProviders.text = "Kanal Resmi: ${item.channelName} (Gratis & Jernih di YouTube)"

        val fallback = YoutubeCatalog.fallbackPoster(this, item)
        if (item.posterUrl != null) {
            Images.load(item.posterUrl, detailPoster)
        } else {
            detailPoster.setImageDrawable(fallback)
        }
        if (!item.backdropUrl.isNullOrBlank()) {
            loadHeroImage(item.backdropUrl, true, detailBackdrop, fallback)
        } else if (!item.posterUrl.isNullOrBlank()) {
            loadHeroImage(item.posterUrl, false, detailBackdrop, fallback)
        } else {
            detailBackdrop.tag = null
            detailBackdrop.setImageDrawable(fallback)
        }

        detailActions.removeAllViews()
        if (!item.videoUrl.isNullOrBlank()) {
            addDetailButton("Putar Episode Ini di YouTube", true) {
                YoutubeCatalog.openUrl(this, item.videoUrl) {
                    notice.show("YouTube tidak terbuka", "Pastikan aplikasi YouTube terpasang di perangkat.", 4000L)
                }
            }
        } else if (item.searchQuery.isNotBlank()) {
            addDetailButton("Tonton di YouTube (Sub Indo)", true) {
                YoutubeCatalog.openYoutubeSearch(this, item.searchQuery, item.channelUrl) {
                    notice.show("YouTube tidak terbuka", "Pastikan aplikasi YouTube terpasang di perangkat.", 4000L)
                }
            }
            addDetailButton("Playlist ${item.channelName}", false) {
                YoutubeCatalog.openUrl(this, item.channelUrl) {
                    notice.show("YouTube tidak terbuka", "Pastikan aplikasi YouTube terpasang di perangkat.", 4000L)
                }
            }
        } else {
            addDetailButton("Buka Semua Playlist", true) {
                YoutubeCatalog.openUrl(this, item.channelUrl) {
                    notice.show("YouTube tidak terbuka", "Pastikan aplikasi YouTube terpasang di perangkat.", 4000L)
                }
            }
        }
        addDetailButton("Tutup", false) { closeDetail() }

        detailOverlay.visibility = View.VISIBLE
        detailActions.getChildAt(0)?.requestFocus()
    }

    private fun loadHomeRows(my: Int) {
        val cwList = ContinueWatch.load(this)
        if (cwList.isNotEmpty()) {
            addContinueWatchRow("Lanjutkan Menonton", cwList)
        }
        if (Tmdb.key(this).isEmpty()) {
            addYoutubeRow(my, "Anime Sub Indo Resmi (YouTube)", YoutubeCatalog.ANIME_PICKS)
            addYoutubeRow(my, "Drama China Sub Indo Resmi (YouTube)", YoutubeCatalog.DRACIN_PICKS)
            return
        }
        notice.hide()
        val specs = listOf(
            Triple("Sedang Tren Minggu Ini", "/trending/all/week", "language=id-ID"),
            Triple("Film Populer", "/movie/popular", "language=id-ID&region=ID&page=1"),
            Triple("Anime & Animasi Populer", "/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=1"),
            Triple("Film Indonesia Terbaru", "/discover/movie", "language=id-ID&with_original_language=id&sort_by=primary_release_date.desc&vote_count.gte=3&page=1"),
            Triple("Sedang Tayang", "/movie/now_playing", "language=id-ID&region=ID&page=1")
        )
        loadCategoryRows(my, specs, "movie")
        val wl = loadWatch()
        if (wl.isNotEmpty()) addCarouselRow("Daftar Saya", wl)
    }

    private fun loadCategoryRows(my: Int, specs: List<Triple<String, String, String>>, defKind: String) {
        if (Tmdb.key(this).isEmpty()) {
            notice.show("Kunci TMDB belum diisi", "Buka Pengaturan lewat HP, isi bagian Film, atau buka tab YouTube Resmi.", 5000L)
            return
        }
        Thread {
            for ((title, path, query) in specs) {
                if (my != token) return@Thread
                val json = Tmdb.get(this, path, query) ?: continue
                val list = Tmdb.parseList(json, defKind).take(16)
                if (list.isEmpty()) continue
                ui.post {
                    if (my == token && !isFinishing) {
                        notice.hide()
                        addCarouselRow(title, list)
                    }
                }
            }
        }.start()
    }

    private fun loadJellyfinTab(my: Int) {
        if (!Jellyfin.configured(this)) {
            notice.show(
                "Koleksi Saya (Cadangan) belum diatur",
                "Jellyfin bersifat opsional sebagai cadangan lokal. Anda tetap bisa menonton langsung dari tab Beranda, Film, atau YouTube Resmi."
            )
            return
        }
        notice.show(null, "Memuat Koleksi Saya...")
        Thread {
            val list = Jellyfin.latest(this)
            ui.post {
                if (my != token) return@post
                if (list == null) {
                    notice.show("Server Jellyfin tidak terhubung", "Pastikan PC server menyala jika ingin memutar koleksi lokal.", 5000L)
                } else {
                    notice.hide()
                    if (list.isEmpty()) {
                        notice.show(null, "Koleksi Jellyfin masih kosong.", 3000L)
                    } else {
                        jfTitles = list.map { norm(it.title) }.toSet()
                        addCarouselRow("Koleksi Lokal Jellyfin (${list.size})", list)
                    }
                }
            }
        }.start()
    }

    private fun addContinueWatchRow(rowTitle: String, items: List<ContinueWatch.Entry>) {
        if (items.isEmpty()) return
        if (focusedMovie == null) scheduleHero(items[0].movie, items[0])

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(10))
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(44), 0, dp(44), dp(6))
        }
        val header = tv(
            rowTitle, 17f, brassLight,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply { setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        headerRow.addView(header)

        val clearAllBtn = tv(
            "Hapus Semua", 11f, mute,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0x33FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                setTextColor(if (f) bgDark else mute)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (f) brassLight else 0x22FFFFFF)
                }
            }
            setOnClickListener { confirmClearAllHistory() }
        }
        val clp = LinearLayout.LayoutParams(-2, -2)
        clp.leftMargin = dp(14)
        headerRow.addView(clearAllBtn, clp)
        section.addView(headerRow)

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(44), dp(8), dp(44), dp(8))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        for (cw in items) {
            val card = buildPosterCard(cw.movie, cw)
            val lp = LinearLayout.LayoutParams(dp(136), -2)
            lp.rightMargin = dp(16)
            strip.addView(card, lp)
        }

        hScroll.addView(strip)
        section.addView(hScroll)
        rowsBox.addView(section)
    }

    private fun confirmClearAllHistory() {
        AlertDialog.Builder(this)
            .setTitle("Hapus Semua Riwayat?")
            .setMessage("Seluruh daftar Lanjutkan Menonton dan posisi menit terakhir akan dihapus.")
            .setPositiveButton("Hapus Semua") { _, _ ->
                clearAllContinueHistory()
                closeDetail()
                notice.show(null, "Semua riwayat tontonan telah dihapus", 2500L)
                if (tabIdx == 0) {
                    tabIdx = -1
                    selectTab(0)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun clearAllContinueHistory() {
        for (entry in ContinueWatch.load(this)) {
            ContinueWatch.remove(this, entry.key)
        }
        val ed = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith("pos_") || k.startsWith("srv_")) ed.remove(k)
        }
        ed.apply()
    }

    private fun addCarouselRow(rowTitle: String, items: List<Movie>) {
        if (items.isEmpty()) return
        if (focusedMovie == null) scheduleHero(items[0])

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(10))
        }
        val header = tv(
            rowTitle, 17f, ivory,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(44), 0, dp(44), dp(6))
        }
        section.addView(header)

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(44), dp(8), dp(44), dp(8))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        for (m in items) {
            val card = buildPosterCard(m, null)
            val lp = LinearLayout.LayoutParams(dp(136), -2)
            lp.rightMargin = dp(16)
            strip.addView(card, lp)
        }

        hScroll.addView(strip)
        section.addView(hScroll)
        rowsBox.addView(section)
    }

    private fun buildPosterCard(m: Movie, cw: ContinueWatch.Entry?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            isFocusable = true
            isClickable = true
        }

        val posterFrame = FrameLayout(this).apply {
            foreground = posterStroke(false)
        }
        roundPoster(posterFrame, 10)

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1916.toInt())
        }
        posterFrame.addView(img, FrameLayout.LayoutParams(-1, dp(192)))

        val badgeText = when {
            cw != null && cw.posMs > 60000L -> "Lanjut ${cw.posMs / 60000L}m"
            cw != null -> "Lanjut"
            m.isJellyfin -> "JF"
            m.rating > 0 -> "\u2605 " + String.format(Locale.US, "%.1f", m.rating)
            else -> ""
        }
        if (badgeText.isNotEmpty()) {
            val b = tv(
                badgeText, 10f, brassLight,
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            ).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0xDD0B0A09.toInt())
                }
            }
            val blp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            blp.setMargins(0, dp(6), dp(6), 0)
            posterFrame.addView(b, blp)
        }

        card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(192)))

        val title = tv(
            m.title, 12f, mute,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        card.addView(title)

        card.setOnFocusChangeListener { _, hasFocus ->
            posterFrame.foreground = posterStroke(hasFocus)
            title.setTextColor(if (hasFocus) ivory else mute)
            card.z = if (hasFocus) 8f else 0f
            card.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(130)
                .start()
            if (hasFocus) {
                scheduleHero(m, cw)
                val parentRow = card.parent?.parent?.parent as? View
                if (parentRow != null) {
                    rowsScroll.smoothScrollTo(0, (parentRow.top - dp(10)).coerceAtLeast(0))
                }
            }
        }

        card.setOnClickListener {
            if (cw != null) openContinueDetail(cw) else openDetail(m)
        }
        Images.load(m.poster, img)
        return card
    }

    private fun posterStroke(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.TRANSPARENT)
        if (focused) {
            setStroke(dp(3), brassLight)
        } else {
            setStroke(dp(1), 0x26FFFFFF)
        }
    }

    private fun buildDetailOverlay(root: FrameLayout) {
        detailOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xF50B0A09.toInt())
            visibility = View.GONE
            isClickable = true
        }

        detailBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.28f
        }
        detailOverlay.addView(detailBackdrop, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(40), dp(56), dp(40))
        }

        detailPoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1D1B18.toInt())
        }
        roundPoster(detailPoster, 12)
        content.addView(detailPoster, LinearLayout.LayoutParams(dp(220), dp(320)))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), 0, 0, 0)
        }
        detailTitle = tv("", 28f, ivory, Typeface.SERIF)
        detailMeta = tv("", 14f, brassLight, Typeface.SANS_SERIF).apply {
            setPadding(0, dp(6), 0, dp(12))
        }
        detailOverview = tv("", 14f, 0xFFE2DDD3.toInt(), Typeface.SANS_SERIF).apply {
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        }
        detailProviders = tv("", 12f, mute, Typeface.SANS_SERIF).apply {
            setPadding(0, dp(12), 0, dp(16))
        }
        detailActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        right.addView(detailTitle)
        right.addView(detailMeta)
        right.addView(detailOverview)
        right.addView(detailProviders)
        right.addView(detailActions)

        content.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        detailOverlay.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(detailOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildResumeKey(m: Movie, season: Int, episode: Int): String {
        return if (m.kind == "tv") "tmdb_tv_${m.id}_${season}_${episode}" else "tmdb_mv_${m.id}"
    }

    private fun saveLastServer(rKey: String, source: StremioAddons.StreamSource) {
        try {
            val o = JSONObject().apply {
                put("is_torrent", source.isTorrent)
                put("url", source.url)
                put("file_idx", source.fileIdx)
                put("label", source.displayLabel())
                put("referer", source.referer)
                put("origin", source.origin)
                put("user_agent", source.userAgent)
            }
            prefs.edit().putString("srv_$rKey", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadLastServer(rKey: String): JSONObject? {
        val raw = prefs.getString("srv_$rKey", null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (o.optString("url", "").isNotEmpty()) o else null
        } catch (_: Exception) {
            null
        }
    }

    private fun openContinueDetail(cw: ContinueWatch.Entry) {
        val m = cw.movie
        resolvedImdbId = ""
        detailTitle.text = m.title
        val menit = if (cw.posMs > 60000L) "Menit ke-${cw.posMs / 60000L}" else "Terakhir ditonton"
        detailMeta.text = (if (m.year.isNotEmpty()) m.year + "  |  " else "") + menit + "  |  Subtitle Indonesia"
        detailOverview.text = m.overview.ifBlank { "Lanjutkan menonton dari posisi terakhir Anda." }

        val rKey = buildResumeKey(m, cw.season, cw.episode)
        val savedSrv = if (!m.isJellyfin) loadLastServer(rKey) else null
        detailProviders.text = if (savedSrv != null) {
            "Server Terakhir: " + savedSrv.optString("label", "Tersimpan otomatis")
        } else {
            "Riwayat B-BOX tersimpan otomatis di perangkat TV."
        }

        Images.load(m.poster, detailPoster)
        val bdUrl = resolvedBackdrops[watchKey(m)] ?: m.backdrop
        if (!bdUrl.isNullOrBlank()) {
            loadHeroImage(bdUrl, true, detailBackdrop)
        } else {
            loadHeroImage(m.poster, false, detailBackdrop)
        }
        detailActions.removeAllViews()

        if (m.isJellyfin) {
            addDetailButton("Lanjutkan Menonton", true) {
                closeDetail()
                playJellyfin(m)
            }
        } else if (savedSrv != null) {
            addDetailButton("Lanjutkan Menonton", true) {
                closeDetail()
                launchSavedServer(m, cw.season, cw.episode, cw.posMs, savedSrv)
            }
            addDetailButton("Ganti Server", false) {
                startStremioStream(m, cw.season, cw.episode, "", cw.posMs)
            }
        } else {
            addDetailButton("Lanjutkan Menonton", true) {
                startStremioStream(m, cw.season, cw.episode, "", cw.posMs)
            }
        }

        addDetailButton("Hapus Riwayat", false) {
            ContinueWatch.remove(this, cw.key)
            prefs.edit().remove("srv_$rKey").remove("pos_$rKey").apply()
            closeDetail()
            if (tabIdx == 0) {
                tabIdx = -1
                selectTab(0)
            }
        }
        addDetailButton("Hapus Semua", false) { confirmClearAllHistory() }
        addDetailButton("Tutup", false) { closeDetail() }

        detailOverlay.visibility = View.VISIBLE
        detailActions.getChildAt(0)?.requestFocus()

        if (!m.isJellyfin && m.id > 0) {
            Thread { resolvedImdbId = Tmdb.imdbId(this, m.id, m.kind) }.start()
        }
    }

    private fun launchSavedServer(
        m: Movie,
        season: Int,
        episode: Int,
        resumeMs: Long,
        savedSrv: JSONObject
    ) {
        val isTorrent = savedSrv.optBoolean("is_torrent", true)
        val url = savedSrv.optString("url", "")
        if (url.isEmpty()) {
            startStremioStream(m, season, episode, "", resumeMs)
            return
        }
        val fileIdx = savedSrv.optInt("file_idx", -1)
        val fullTitle = if (m.kind == "tv") "${m.title} - S${season}E${episode}".trim() else m.title
        val rKey = buildResumeKey(m, season, episode)
        val targetClass = if (isTorrent) TorrentBufferActivity::class.java else VodPlayerActivity::class.java

        val i = Intent(this, targetClass).apply {
            putExtra("title", fullTitle)
            if (isTorrent) {
                putExtra("magnet_url", url)
                putExtra("file_idx", fileIdx)
            } else {
                putExtra("url", url)
                putExtra("referer", savedSrv.optString("referer", ""))
                putExtra("origin", savedSrv.optString("origin", ""))
                putExtra("user_agent", savedSrv.optString("user_agent", "").ifEmpty { Playlist.DEFAULT_UA })
            }
            putExtra("resume_key", rKey)
            putExtra("resume_ms", resumeMs)
            putExtra("tmdb_id", m.id)
            putExtra("imdb_id", resolvedImdbId)
            putExtra("tmdb_kind", m.kind)
            putExtra("season", season)
            putExtra("episode", episode)
            putExtra("year", m.year)
            putExtra("poster", m.poster ?: "")
            putExtra("backdrop", resolvedBackdrops[watchKey(m)] ?: m.backdrop ?: "")
            putExtra("rating", m.rating)
            putExtra("overview", m.overview)
        }
        if (!isTorrent) {
            ContinueWatch.saveFromIntent(this, i, resumeMs)
        }
        startActivity(i)
    }

    private fun openDetail(m: Movie) {
        if (m.isJellyfin) {
            openJellyfin(m)
            return
        }
        resolvedImdbId = ""
        detailTitle.text = m.title
        detailMeta.text = (if (m.year.isNotEmpty()) m.year + "  |  " else "") +
            (if (m.kind == "tv") "Serial / Anime" else "Film") + "  |  Torrentio WEB-DL"
        detailOverview.text = m.overview.ifBlank { "Memuat sinopsis lengkap..." }
        detailProviders.text = "Memuat info durasi & Subtitle Indonesia..."
        Images.load(m.poster, detailPoster)
        val bdUrl = resolvedBackdrops[watchKey(m)] ?: m.backdrop
        if (!bdUrl.isNullOrBlank()) {
            loadHeroImage(bdUrl, true, detailBackdrop)
        } else {
            loadHeroImage(m.poster, false, detailBackdrop)
        }
        detailActions.removeAllViews()

        addDetailButton("Tonton Gratis (WEB-DL)", true) { startStremioStream(m) }
        addDetailButton("YouTube TV", false) {
            openYoutubeSearch(m.title + if (m.year.isNotEmpty()) " ${m.year}" else "")
        }
        if (Jellyfin.configured(this)) {
            addDetailButton("Koleksi Saya", false) { playFromCollection(m) }
        }
        val inList = loadWatch().any { watchKey(it) == watchKey(m) }
        addDetailButton(if (inList) "Di Daftar Saya" else "+ Daftar Saya", false) {
            val added = toggleWatch(m)
            notice.show(null, if (added) "Ditambahkan ke Daftar saya" else "Dihapus dari Daftar saya", 2000L)
            closeDetail()
        }
        addDetailButton("Tutup", false) { closeDetail() }

        detailOverlay.visibility = View.VISIBLE
        detailActions.getChildAt(0)?.requestFocus()

        Thread {
            val imdb = Tmdb.imdbId(this, m.id, m.kind)
            if (imdb.isNotEmpty()) resolvedImdbId = imdb

            val path = "/" + (if (m.kind == "tv") "tv" else "movie") + "/" + m.id
            val q = "language=id-ID&append_to_response=videos,watch/providers&include_video_language=id,en,ja,null"
            val json = Tmdb.get(this, path, q) ?: return@Thread
            val o = try { JSONObject(json) } catch (e: Exception) { return@Thread }
            ui.post {
                if (detailOverlay.visibility != View.VISIBLE || isFinishing) return@post
                val bp = o.optString("backdrop_path", "")
                if (bp.isNotEmpty() && bp != "null") {
                    val fullBd = "https://image.tmdb.org/t/p/w1280$bp"
                    resolvedBackdrops[watchKey(m)] = fullBd
                    loadHeroImage(fullBd, true, detailBackdrop)
                }

                val metaParts = ArrayList<String>()
                if (m.year.isNotEmpty()) metaParts.add(m.year)
                if (m.rating > 0) metaParts.add("\u2605 " + String.format(Locale.US, "%.1f", m.rating))
                val rt = o.optInt("runtime", 0)
                if (rt > 0) metaParts.add("$rt menit")
                val seasons = o.optInt("number_of_seasons", 0)
                if (seasons > 0) metaParts.add("$seasons Musim - ${o.optInt("number_of_episodes", 0)} Episode")
                val g = o.optJSONArray("genres")
                if (g != null && g.length() > 0) {
                    val gl = ArrayList<String>()
                    for (i in 0 until g.length()) gl.add(g.getJSONObject(i).optString("name"))
                    metaParts.add(gl.joinToString(", "))
                }
                detailMeta.text = metaParts.joinToString("  |  ")

                val ov = o.optString("overview", "").trim()
                if (ov.isNotEmpty()) detailOverview.text = ov

                var trailer: String? = null
                val vs = o.optJSONObject("videos")?.optJSONArray("results")
                if (vs != null) {
                    for (i in 0 until vs.length()) {
                        val v = vs.getJSONObject(i)
                        if (v.optString("site") == "YouTube" &&
                            (v.optString("type") == "Trailer" || v.optString("type") == "Teaser")
                        ) {
                            trailer = v.optString("key")
                            break
                        }
                    }
                }

                val wp = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("ID")
                val provNames = ArrayList<String>()
                if (wp != null) {
                    for (k in listOf("flatrate", "free", "ads")) {
                        val s = names(wp.optJSONArray(k))
                        if (s.isNotEmpty()) provNames.addAll(s.split(", "))
                    }
                }
                detailProviders.text = if (provNames.isNotEmpty()) {
                    "Layanan Resmi ID: " + provNames.distinct().joinToString(", ")
                } else {
                    "Pemutar B-BOX Native (Prioritas WEB-DL Ringkas & Sub Indo Otomatis)"
                }

                detailActions.removeAllViews()
                if (m.kind == "tv" && seasons > 0) {
                    addDetailButton("Pilih Episode", true) { pickEpisodeAndStream(m, seasons) }
                } else {
                    addDetailButton("Tonton Gratis (WEB-DL)", true) { startStremioStream(m) }
                }
                addDetailButton("YouTube TV", false) {
                    openYoutubeSearch(m.title + if (m.year.isNotEmpty()) " ${m.year}" else "")
                }
                if (Jellyfin.configured(this)) {
                    addDetailButton("Koleksi Saya", false) { playFromCollection(m) }
                }
                val tk = trailer
                if (!tk.isNullOrEmpty()) {
                    addDetailButton("Trailer", false) { openTrailer(tk) }
                }
                val installed = findInstalled(provNames)
                if (installed != null) {
                    addDetailButton("Buka " + installed.first, false) {
                        try {
                            val i = Intent(Intent.ACTION_MAIN)
                            i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
                            i.component = installed.second
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } catch (e: Exception) {
                            notice.show("Tidak terbuka", "Buka aplikasinya dari layar utama TV.", 3000L)
                        }
                    }
                }
                val curInList = loadWatch().any { watchKey(it) == watchKey(m) }
                addDetailButton(if (curInList) "Di Daftar Saya" else "+ Daftar Saya", false) {
                    val added = toggleWatch(m)
                    notice.show(null, if (added) "Ditambahkan ke Daftar saya" else "Dihapus dari Daftar saya", 2000L)
                    closeDetail()
                }
                detailActions.getChildAt(0)?.requestFocus()
            }
        }.start()
    }

    private fun openYoutubeSearch(query: String) {
        try {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            notice.show("YouTube tidak terbuka", "Pastikan aplikasi YouTube sudah terpasang di TV.", 4000L)
        }
    }

    private fun addDetailButton(label: String, primary: Boolean, onClick: () -> Unit) {
        val btn = tv(
            label, 14f, if (primary) Color.BLACK else ivory,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isFocusable = true
            isClickable = true
            background = buttonBg(primary, false)
            setOnFocusChangeListener { _, f ->
                background = buttonBg(primary, f)
                setTextColor(if (f || primary) Color.BLACK else ivory)
            }
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(10)
        detailActions.addView(btn, lp)
    }

    private fun buttonBg(primary: Boolean, focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        when {
            focused -> {
                setColor(brassLight)
                setStroke(dp(2), Color.WHITE)
            }
            primary -> setColor(brass)
            else -> {
                setColor(0x28FFFFFF)
                setStroke(dp(1), 0x55FFFFFF)
            }
        }
    }

    private fun closeDetail() {
        detailOverlay.visibility = View.GONE
    }

    private fun rankStreamsForTv(streams: List<StremioAddons.StreamSource>): List<RankedStream> {
        val webDlRegex = Regex("\\bweb[\\s._-]?(dl|rip)?\\b", RegexOption.IGNORE_CASE)
        val goodRipRegex = Regex("\\b(blu[\\s._-]?ray|bdrip|brrip|hdrip|hdtv)\\b", RegexOption.IGNORE_CASE)
        val camRegex = Regex("\\b(cam|hdcam|ts|hdts|telesync|telecine|scr|dvdscr)\\b", RegexOption.IGNORE_CASE)
        val seedRegex = Regex("(?:\\uD83D\\uDC64|seeders?:?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(gb|mb)", RegexOption.IGNORE_CASE)

        return streams.map { s ->
            val lbl = s.displayLabel()
            val low = lbl.lowercase(Locale.US)
            var score = 0
            val isCam = camRegex.containsMatchIn(low)
            val isWebDl = !isCam && webDlRegex.containsMatchIn(low)

            when {
                isCam -> score -= 5000
                isWebDl -> score += 2000
                goodRipRegex.containsMatchIn(low) -> score += 900
            }

            when {
                low.contains("1080p") -> score += 450
                low.contains("720p") -> score += 380
                low.contains("480p") -> score += 120
                low.contains("2160p") || low.contains("4k") -> score -= 250
            }

            val seeds = seedRegex.find(lbl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            if (s.isTorrent && seeds == 0) {
                score -= 4000
            } else if (seeds > 0) {
                score += minOf(seeds, 300) * 3
            }

            val sizeMatch = sizeRegex.find(lbl)
            if (sizeMatch != null) {
                val num = sizeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val unit = sizeMatch.groupValues[2].lowercase(Locale.US)
                val sizeMb = if (unit == "gb") num * 1024.0 else num
                when {
                    sizeMb in 300.0..2600.0 -> score += 400
                    sizeMb in 2600.1..4500.0 -> score += 120
                    sizeMb > 8000.0 -> score -= 1600
                    sizeMb > 4500.0 -> score -= 650
                }
            }

            RankedStream(s, score, isWebDl)
        }.sortedByDescending { it.score }
    }

    private fun startStremioStream(
        m: Movie,
        season: Int = 1,
        episode: Int = 1,
        epTitle: String = "",
        resumeMs: Long = 0L
    ) {
        notice.show("Mencari Sumber WEB-DL...", "Memilih kualitas terbaik & ukuran file ringkas...")
        Thread {
            val imdb = resolvedImdbId.ifEmpty { Tmdb.imdbId(this, m.id, m.kind) }
            if (imdb.isNotEmpty()) resolvedImdbId = imdb
            val rawStreams = if (imdb.startsWith("tt")) {
                StremioAddons.fetchStreams(this, imdb, m.kind, season, episode)
            } else {
                emptyList()
            }

            ui.post {
                if (isFinishing) return@post
                notice.hide()
                if (rawStreams.isEmpty()) {
                    val info = if (imdb.isEmpty()) "IMDb ID tidak tersedia" else imdb
                    AlertDialog.Builder(this)
                        .setTitle("Sumber Torrentio Tidak Ditemukan")
                        .setMessage("Belum ditemukan sumber untuk judul ini ($info). Coba cari di YouTube TV?")
                        .setPositiveButton("Cari di YouTube TV") { _, _ ->
                            openYoutubeSearch(m.title + if (m.year.isNotEmpty()) " ${m.year}" else "")
                        }
                        .setNegativeButton("Tutup", null)
                        .show()
                } else {
                    val ranked = rankStreamsForTv(rawStreams)
                    if (ranked.size == 1) {
                        closeDetail()
                        launchSelectedSource(m, season, episode, epTitle, imdb, ranked[0].source, resumeMs)
                    } else {
                        val hasWebDl = ranked.any { it.isWebDl }
                        val dialogTitle = if (hasWebDl) {
                            "Pilih Sumber (Prioritas WEB-DL Ringkas di Atas)"
                        } else {
                            "Pilih Sumber (WEB-DL Kosong - Menampilkan Rilisan Terbaik)"
                        }
                        val labels = ranked.mapIndexed { idx, item ->
                            val prefix = if (idx == 0) {
                                if (item.isWebDl) "[WEB-DL Terbaik] " else "[Rekomendasi] "
                            } else {
                                ""
                            }
                            prefix + item.source.displayLabel()
                        }.toTypedArray()

                        AlertDialog.Builder(this)
                            .setTitle(dialogTitle)
                            .setItems(labels) { _, which ->
                                closeDetail()
                                launchSelectedSource(m, season, episode, epTitle, imdb, ranked[which].source, resumeMs)
                            }
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun launchSelectedSource(
        m: Movie,
        season: Int,
        episode: Int,
        epTitle: String,
        imdbId: String,
        source: StremioAddons.StreamSource,
        resumeMs: Long = 0L
    ) {
        val fullTitle = if (m.kind == "tv") "${m.title} - S${season}E${episode} $epTitle".trim() else m.title
        val rKey = buildResumeKey(m, season, episode)
        saveLastServer(rKey, source)

        val targetClass = if (source.isTorrent) {
            TorrentBufferActivity::class.java
        } else {
            VodPlayerActivity::class.java
        }

        val i = Intent(this, targetClass).apply {
            putExtra("title", fullTitle)
            if (source.isTorrent) {
                putExtra("magnet_url", source.url)
                putExtra("file_idx", source.fileIdx)
            } else {
                putExtra("url", source.url)
                putExtra("referer", source.referer)
                putExtra("origin", source.origin)
                putExtra("user_agent", source.userAgent.ifEmpty { Playlist.DEFAULT_UA })
            }
            putExtra("resume_key", rKey)
            putExtra("resume_ms", resumeMs)
            putExtra("tmdb_id", m.id)
            putExtra("imdb_id", imdbId)
            putExtra("tmdb_kind", m.kind)
            putExtra("season", season)
            putExtra("episode", episode)
            putExtra("year", m.year)
            putExtra("poster", m.poster ?: "")
            putExtra("backdrop", resolvedBackdrops[watchKey(m)] ?: m.backdrop ?: "")
            putExtra("rating", m.rating)
            putExtra("overview", m.overview)
        }
        if (!source.isTorrent) {
            ContinueWatch.saveFromIntent(this, i, resumeMs)
        }
        startActivity(i)
    }

    private fun pickEpisodeAndStream(m: Movie, totalSeasons: Int) {
        if (totalSeasons <= 1) {
            loadSeasonEpisodes(m, 1)
            return
        }
        val labels = (1..totalSeasons).map { "Season $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(m.title + " - Pilih Season")
            .setItems(labels) { _, which -> loadSeasonEpisodes(m, which + 1) }
            .show()
    }

    private fun loadSeasonEpisodes(m: Movie, season: Int) {
        notice.show(null, "Memuat daftar episode Season $season...")
        Thread {
            val eps = Tmdb.episodes(this, m.id, season)
            ui.post {
                notice.hide()
                if (eps.isEmpty()) {
                    startStremioStream(m, season, 1)
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("${m.title} - Season $season")
                    .setItems(eps.map { it.title }.toTypedArray()) { _, w ->
                        val ep = eps[w]
                        startStremioStream(m, ep.season, ep.episode, ep.title)
                    }
                    .show()
            }
        }.start()
    }

    private fun openJellyfin(m: Movie) {
        if (m.kind == "jf_series") {
            notice.show(null, "Memuat episode...")
            Thread {
                val eps = Jellyfin.episodes(this, m.jfId)
                ui.post {
                    notice.hide()
                    if (eps.isNullOrEmpty()) {
                        notice.show(null, "Episode tidak ditemukan.", 3000L)
                    } else {
                        val labels = eps.map { e ->
                            e.title + (if (resumeOf(e.jfId) > 0L) "  \u25B6" else "")
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(m.title)
                            .setItems(labels) { _, w -> playJellyfin(eps[w]) }
                            .show()
                    }
                }
            }.start()
        } else {
            playJellyfin(m)
        }
    }

    private fun resumeOf(id: String): Long = prefs.getLong("pos_jf_" + id, 0L)

    private fun playJellyfin(m: Movie) {
        notice.show(null, "Menyiapkan " + m.title + "...")
        Thread {
            val pl = Jellyfin.playable(this, m.jfId)
            ui.post {
                notice.hide()
                if (pl == null) {
                    notice.show("Tidak bisa diputar", "Server Jellyfin tidak menjawab.", 4000L)
                    return@post
                }
                val i = Intent(this, VodPlayerActivity::class.java)
                i.putExtra("title", m.title)
                i.putExtra("url", pl.url)
                i.putExtra("fallback", pl.fallback)
                i.putExtra("resume_key", "jf_" + m.jfId)
                i.putExtra("jf_id", m.jfId)
                i.putExtra("tmdb_kind", m.kind)
                i.putExtra("year", m.year)
                i.putExtra("poster", m.poster ?: "")
                i.putExtra("rating", m.rating)
                i.putExtra("overview", m.overview)
                i.putExtra("sub_urls", pl.subs.map { it.url }.toTypedArray())
                i.putExtra("sub_langs", pl.subs.map { it.lang }.toTypedArray())
                i.putExtra("sub_labels", pl.subs.map { it.label }.toTypedArray())
                startActivity(i)
            }
        }.start()
    }

    private fun playFromCollection(m: Movie) {
        if (!Jellyfin.configured(this)) {
            notice.show("Koleksi Saya belum diatur", "Isi server Jellyfin di Pengaturan lewat HP.", 4000L)
            return
        }
        notice.show(null, "Mencari di Koleksi Saya...")
        Thread {
            val found = Jellyfin.search(this, m.title)
            ui.post {
                notice.hide()
                if (found.isNullOrEmpty()) {
                    notice.show(null, "\"" + m.title + "\" belum ada di Koleksi Saya.", 3000L)
                } else if (found.size == 1) {
                    closeDetail()
                    openJellyfin(found[0])
                } else {
                    val labels = found.map {
                        it.title + (if (it.year.isNotEmpty()) " (" + it.year + ")" else "")
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Pilih dari Koleksi Saya")
                        .setItems(labels) { _, w ->
                            closeDetail()
                            openJellyfin(found[w])
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun loadWatch(): List<Movie> {
        val out = ArrayList<Movie>()
        try {
            val a = JSONArray(prefs.getString("watch", "[]"))
            for (i in 0 until a.length()) {
                val m = Tmdb.toMovie(a.getJSONObject(i))
                if (m != null) out.add(m)
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun watchKey(m: Movie): String = m.kind + ":" + m.id

    private fun toggleWatch(m: Movie): Boolean {
        val list = ArrayList(loadWatch())
        val had = list.removeAll { watchKey(it) == watchKey(m) }
        if (!had) list.add(0, m)
        val a = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("title", x.title)
            o.put("release_date", x.year)
            o.put("poster_path", x.poster ?: "")
            o.put("backdrop_path", resolvedBackdrops[watchKey(x)] ?: x.backdrop ?: "")
            o.put("vote_average", x.rating)
            o.put("overview", x.overview)
            o.put("kind", x.kind)
            a.put(o)
        }
        prefs.edit().putString("watch", a.toString()).apply()
        return !had
    }

    private fun names(a: JSONArray?): String {
        if (a == null) return ""
        val l = ArrayList<String>()
        for (i in 0 until a.length()) l.add(a.getJSONObject(i).optString("provider_name"))
        return l.joinToString(", ")
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun findInstalled(providers: List<String>): Pair<String, ComponentName>? {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = pm.queryIntentActivities(q, 0)
        for (p in providers) {
            val np = norm(p)
            if (np.length < 4) continue
            for (a in apps) {
                val label = a.loadLabel(pm).toString()
                val nl = norm(label)
                if (nl.length >= 4 && (nl.contains(np) || np.contains(nl))) {
                    return Pair(label, ComponentName(a.activityInfo.packageName, a.activityInfo.name))
                }
            }
        }
        return null
    }

    private fun openTrailer(key: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + key))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e2: Exception) {
                notice.show("Trailer tidak terbuka", "Pasang aplikasi YouTube di TV.", 4000L)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && detailOverlay.visibility == View.VISIBLE) {
            closeDetail()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        heroCache.evictAll()
        super.onDestroy()
    }
}
// END OF FilmActivity.kt
