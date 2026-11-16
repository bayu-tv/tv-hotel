package com.bayutv.hotel

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
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
import java.net.URLEncoder
import java.util.Locale

class FilmActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()
    private val bgDark = 0xFF0D0C0B.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private val tabs = arrayOf("Beranda", "Film", "Anime & Serial", "Indonesia", "Koleksi Saya", "Daftar Saya", "Cari")
    private val searchIdx = 6
    private val tabViews = ArrayList<TextView>()
    private var tabIdx = -1
    private var token = 0
    private var jfTitles: Set<String> = emptySet()

    // Hero Spotlight Views
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroOverview: TextView

    // Rows Container
    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout

    // Detail Overlay Views
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

    private val applyHeroRunnable = Runnable {
        val m = focusedMovie ?: return@Runnable
        renderHero(m)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(bgDark)

        // 1. Hero Backdrop (kanan atas) dengan gradasi ala Netflix
        val heroFrame = FrameLayout(this)
        heroBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF151311.toInt())
        }
        val bdLp = FrameLayout.LayoutParams(dp(640), dp(340), Gravity.TOP or Gravity.END)
        heroFrame.addView(heroBackdrop, bdLp)

        // Gradasi kiri -> kanan agar teks kiri tajam
        val fadeLeft = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(bgDark, bgDark, 0xCC0D0C0B.toInt(), 0x220D0C0B)
            )
        }
        heroFrame.addView(fadeLeft, FrameLayout.LayoutParams(dp(720), dp(340), Gravity.TOP or Gravity.END))

        // Gradasi atas -> bawah menyatu ke daftar baris
        val fadeBottom = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x330D0C0B, 0x000D0C0B, 0xCC0D0C0B.toInt(), bgDark)
            )
        }
        heroFrame.addView(fadeBottom, FrameLayout.LayoutParams(-1, dp(345), Gravity.TOP))
        root.addView(heroFrame, FrameLayout.LayoutParams(-1, dp(345), Gravity.TOP))

        // 2. Konten Utama: Top Nav + Hero Info + Baris Horizontal
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Navigation Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(42), dp(20), dp(42), dp(8))
        }
        val brand = tv("BAYU CINEMA", 20f, brassLight, Typeface.SERIF).apply {
            letterSpacing = 0.14f
            setPadding(0, 0, dp(24), 0)
        }
        topBar.addView(brand)

        for (i in tabs.indices) {
            val label = if (i == searchIdx) "\uD83D\uDD0D  " + tabs[i] else tabs[i]
            val t = tv(label, 15f, mute, Typeface.SANS_SERIF).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    styleTab(this, i, hasFocus)
                    if (hasFocus && i < searchIdx) selectTab(i)
                }
                setOnClickListener {
                    if (i == searchIdx) askSearch() else selectTab(i)
                }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(6)
            topBar.addView(t, lp)
            tabViews.add(t)
        }
        mainCol.addView(topBar)

        // Hero Info Box
        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(6), dp(42), dp(10))
        }
        heroBadge = tv("STREAMING  ·  SUB INDO", 11f, brassLight, Typeface.SANS_SERIF).apply {
            letterSpacing = 0.12f
        }
        heroTitle = tv("Pilih Film atau Anime", 30f, ivory, Typeface.SERIF).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(4))
        }
        heroMeta = tv("", 13f, brass, Typeface.SANS_SERIF)
        heroOverview = tv(
            "Geser remote ke poster di bawah untuk melihat sinopsis, lalu tekan OK untuk menonton lengkap dengan Subtitle Indonesia.",
            13f,
            0xFFD4CEC2.toInt(),
            Typeface.SANS_SERIF
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        heroInfo.addView(heroBadge)
        heroInfo.addView(heroTitle)
        heroInfo.addView(heroMeta)
        heroInfo.addView(heroOverview, LinearLayout.LayoutParams(dp(560), -2))
        mainCol.addView(heroInfo)

        // Baris-baris poster ala Netflix
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(28))
        }
        rowsScroll.addView(rowsBox, FrameLayout.LayoutParams(-1, -2))
        mainCol.addView(rowsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(mainCol, FrameLayout.LayoutParams(-1, -1))

        // 3. Layar Detail Sinematik (pengganti AlertDialog)
        buildDetailOverlay(root)

        notice = Notice(this, root)
        setContentView(root)

        // Cek indeks judul Jellyfin di latar belakang untuk badge cadangan lokal
        if (Jellyfin.configured(this)) {
            Thread {
                val jf = Jellyfin.latest(this) ?: emptyList()
                jfTitles = jf.map { norm(it.title) }.toSet()
            }.start()
        }

        tabViews[0].requestFocus()
        if (Tmdb.key(this).isEmpty()) {
            notice.show(
                "Kunci TMDB belum diisi",
                "Buka Pengaturan lewat HP, isi bagian Film (kunci gratis dari themoviedb.org), lalu kembali ke sini."
            )
        }
    }

    private fun styleTab(v: TextView, idx: Int, focused: Boolean) {
        val active = idx == tabIdx
        v.setTextColor(if (focused) Color.BLACK else if (active) brassLight else mute)
        val g = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (focused) {
                setColor(brassLight)
            } else if (active) {
                setColor(0x28B8975A)
                setStroke(dp(1), brass)
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
        v.background = g
    }

    private fun refreshTabStyles() {
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
    }

    private fun scheduleHero(m: Movie) {
        focusedMovie = m
        ui.removeCallbacks(applyHeroRunnable)
        ui.postDelayed(applyHeroRunnable, 160L)
    }

    private fun renderHero(m: Movie) {
        heroTitle.text = m.title
        val r = if (m.rating > 0) String.format(Locale.US, "%.1f", m.rating) else "-"
        val typeTag = when (m.kind) {
            "tv" -> "SERIAL / ANIME"
            "jf_movie", "jf_series" -> "KOLEKSI SAYA (JELLYFIN)"
            else -> "FILM"
        }
        val hasLocal = !m.isJellyfin && jfTitles.contains(norm(m.title))
        heroBadge.text = typeTag + (if (hasLocal) "  ·  TERSEDIA DI KOLEKSI SAYA" else "  ·  STREAMING SUB INDO")
        heroMeta.text = (if (m.year.isNotEmpty()) m.year + "   ·   " else "") + "\u2605 " + r + "   ·   Tekan OK untuk Tonton"
        heroOverview.text = if (m.overview.isNotBlank()) m.overview else "Tekan OK untuk melihat detail lengkap, pilihan server streaming, dan Subtitle Indonesia."
        Images.load(m.backdrop ?: m.poster, heroBackdrop)
    }

    private fun selectTab(i: Int) {
        if (i == tabIdx) return
        tabIdx = i
        refreshTabStyles()
        val my = ++token
        rowsBox.removeAllViews()
        rowsScroll.scrollTo(0, 0)

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
            4 -> loadJellyfinTab(my)
            5 -> {
                val wl = loadWatch()
                if (wl.isEmpty()) {
                    notice.show("Daftar saya kosong", "Buka detail film, lalu pilih Tambah ke Daftar saya.", 3500L)
                } else {
                    notice.hide()
                    addCarouselRow("Daftar Saya (${wl.size})", wl)
                }
            }
        }
    }

    private fun loadHomeRows(my: Int) {
        if (Tmdb.key(this).isEmpty()) return
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
            notice.show("Kunci TMDB belum diisi", "Buka Pengaturan lewat HP, isi bagian Film.", 5000L)
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
                "Jellyfin bersifat opsional sebagai cadangan lokal. Anda tetap bisa menonton langsung dari tab Beranda, Film, atau Anime."
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

    private fun addCarouselRow(rowTitle: String, items: List<Movie>) {
        if (items.isEmpty()) return
        if (focusedMovie == null) scheduleHero(items[0])

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        val header = tv(rowTitle, 16f, ivory, Typeface.SANS_SERIF).apply {
            setPadding(dp(42), 0, dp(42), dp(8))
        }
        section.addView(header)

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(42), dp(4), dp(42), dp(4))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for (m in items) {
            val card = buildPosterCard(m)
            val lp = LinearLayout.LayoutParams(dp(122), -2)
            lp.rightMargin = dp(12)
            strip.addView(card, lp)
        }

        hScroll.addView(strip)
        section.addView(hScroll)
        rowsBox.addView(section)
    }

    private fun buildPosterCard(m: Movie): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            isFocusable = true
            isClickable = true
            background = cardBg(false)
        }

        val posterFrame = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1916.toInt())
        }
        posterFrame.addView(img, FrameLayout.LayoutParams(-1, dp(172)))

        // Badge rating / Jellyfin di sudut poster
        val badgeText = if (m.isJellyfin) "JF" else if (m.rating > 0) "\u2605 " + String.format(Locale.US, "%.1f", m.rating) else ""
        if (badgeText.isNotEmpty()) {
            val b = tv(badgeText, 10f, brassLight, Typeface.SANS_SERIF).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0xCC0D0C0B.toInt())
                }
            }
            val blp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            blp.setMargins(0, dp(6), dp(6), 0)
            posterFrame.addView(b, blp)
        }

        card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(172)))

        val title = tv(m.title, 12f, ivory, Typeface.SANS_SERIF).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        card.addView(title)

        card.setOnFocusChangeListener { _, hasFocus ->
            card.background = cardBg(hasFocus)
            card.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(120).start()
            if (hasFocus) {
                scheduleHero(m)
                // Pastikan baris terlihat nyaman di layar saat pindah vertikal
                val parentRow = card.parent?.parent?.parent as? View
                if (parentRow != null) {
                    rowsScroll.smoothScrollTo(0, (parentRow.top - dp(16)).coerceAtLeast(0))
                }
            }
        }

        card.setOnClickListener { openDetail(m) }
        Images.load(m.poster, img)
        return card
    }

    private fun cardBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        if (focused) {
            setColor(0x28B8975A)
            setStroke(dp(2), brassLight)
        } else {
            setColor(0x14FFFFFF)
        }
    }

    // ---- Layar Detail Sinematik ----

    private fun buildDetailOverlay(root: FrameLayout) {
        detailOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xF50B0A09.toInt())
            visibility = View.GONE
            isClickable = true
        }

        detailBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.25f
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

    private fun openDetail(m: Movie) {
        if (m.isJellyfin) {
            openJellyfin(m)
            return
        }
        detailTitle.text = m.title
        detailMeta.text = (if (m.year.isNotEmpty()) m.year + "  ·  " else "") +
            (if (m.kind == "tv") "Serial / Anime" else "Film") + "  ·  Subtitle Indonesia"
        detailOverview.text = m.overview.ifBlank { "Memuat sinopsis lengkap..." }
        detailProviders.text = "Memuat info durasi & episode..."
        Images.load(m.poster, detailPoster)
        Images.load(m.backdrop ?: m.poster, detailBackdrop)
        detailActions.removeAllViews()

        // Tombol utama langsung muncul seketika
        addDetailButton("▶  Tonton (Sub Indo)", true) { startDirectStream(m) }
        if (Jellyfin.configured(this)) {
            addDetailButton("Koleksi Saya", false) { playFromCollection(m) }
        }
        val inList = loadWatch().any { watchKey(it) == watchKey(m) }
        addDetailButton(if (inList) "✓ Di Daftar Saya" else "+ Daftar Saya", false) {
            val added = toggleWatch(m)
            notice.show(null, if (added) "Ditambahkan ke Daftar saya" else "Dihapus dari Daftar saya", 2000L)
            closeDetail()
        }
        addDetailButton("Tutup", false) { closeDetail() }

        detailOverlay.visibility = View.VISIBLE
        detailActions.getChildAt(0)?.requestFocus()

        // Ambil detail tambahan TMDB (durasi, season, trailer, layanan resmi)
        Thread {
            val path = "/" + (if (m.kind == "tv") "tv" else "movie") + "/" + m.id
            val json = Tmdb.get(this, path, "language=id-ID&append_to_response=videos,watch/providers&include_video_language=id,en,ja,null")
                ?: return@Thread
            val o = try { JSONObject(json) } catch (e: Exception) { return@Thread }
            ui.post {
                if (detailOverlay.visibility != View.VISIBLE || isFinishing) return@post
                val metaParts = ArrayList<String>()
                if (m.year.isNotEmpty()) metaParts.add(m.year)
                if (m.rating > 0) metaParts.add("\u2605 " + String.format(Locale.US, "%.1f", m.rating))
                val rt = o.optInt("runtime", 0)
                if (rt > 0) metaParts.add("$rt menit")
                val seasons = o.optInt("number_of_seasons", 0)
                if (seasons > 0) metaParts.add("$seasons Musim · ${o.optInt("number_of_episodes", 0)} Episode")
                val g = o.optJSONArray("genres")
                if (g != null && g.length() > 0) {
                    val gl = ArrayList<String>()
                    for (i in 0 until g.length()) gl.add(g.getJSONObject(i).optString("name"))
                    metaParts.add(gl.joinToString(", "))
                }
                detailMeta.text = metaParts.joinToString("  ·  ")

                val ov = o.optString("overview", "").trim()
                if (ov.isNotEmpty()) detailOverview.text = ov

                var trailer: String? = null
                val vs = o.optJSONObject("videos")?.optJSONArray("results")
                if (vs != null) {
                    for (i in 0 until vs.length()) {
                        val v = vs.getJSONObject(i)
                        if (v.optString("site") == "YouTube" && (v.optString("type") == "Trailer" || v.optString("type") == "Teaser")) {
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
                    "Streaming langsung tersedia dengan Subtitle Indonesia otomatis."
                }

                // Susun ulang tombol lengkap dengan opsi Season/Episode & Trailer
                detailActions.removeAllViews()
                if (m.kind == "tv" && seasons > 0) {
                    addDetailButton("▶  Pilih Episode (Sub Indo)", true) { pickEpisodeAndStream(m, seasons) }
                } else {
                    addDetailButton("▶  Tonton Sekarang (Sub Indo)", true) { startDirectStream(m) }
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
                addDetailButton(if (curInList) "✓ Daftar Saya" else "+ Daftar Saya", false) {
                    val added = toggleWatch(m)
                    notice.show(null, if (added) "Ditambahkan ke Daftar saya" else "Dihapus dari Daftar saya", 2000L)
                    closeDetail()
                }
                detailActions.getChildAt(0)?.requestFocus()
            }
        }.start()
    }

    private fun addDetailButton(label: String, primary: Boolean, onClick: () -> Unit) {
        val btn = tv(label, 14f, if (primary) Color.BLACK else ivory, Typeface.SANS_SERIF).apply {
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

    // ---- Streaming Langsung + Pilih Episode ----

    private fun startDirectStream(m: Movie, season: Int = 1, episode: Int = 1, epTitle: String = "") {
        closeDetail()
        val fullTitle = if (m.kind == "tv") "${m.title} · S${season}E${episode} $epTitle".trim() else m.title
        val rKey = if (m.kind == "tv") "tmdb_tv_${m.id}_${season}_${episode}" else "tmdb_mv_${m.id}"
        try {
            val i = Intent(this, WebStreamActivity::class.java)
            i.putExtra("title", fullTitle)
            i.putExtra("tmdb_id", m.id)
            i.putExtra("tmdb_kind", m.kind)
            i.putExtra("season", season)
            i.putExtra("episode", episode)
            i.putExtra("resume_key", rKey)
            startActivity(i)
        } catch (e: Exception) {
            notice.show("Aktivitas belum terdaftar", "Tambahkan WebStreamActivity ke AndroidManifest.xml.", 5000L)
        }
    }

    private fun pickEpisodeAndStream(m: Movie, totalSeasons: Int) {
        if (totalSeasons <= 1) {
            loadSeasonEpisodes(m, 1)
            return
        }
        val labels = (1..totalSeasons).map { "Season $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(m.title + " · Pilih Season")
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
                    startDirectStream(m, season, 1)
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("${m.title} · Season $season")
                    .setItems(eps.map { it.title }.toTypedArray()) { _, w ->
                        val ep = eps[w]
                        startDirectStream(m, ep.season, ep.episode, ep.title)
                    }
                    .show()
            }
        }.start()
    }

    // ---- Koleksi Saya (Jellyfin sebagai cadangan) ----

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
                        AlertDialog.Builder(this)
                            .setTitle(m.title)
                            .setItems(eps.map { e -> e.title + (if (resumeOf(e.jfId) > 0L) "  \u25B6" else "") }.toTypedArray()) { _, w ->
                                playJellyfin(eps[w])
                            }
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
                    AlertDialog.Builder(this)
                        .setTitle("Pilih dari Koleksi Saya")
                        .setItems(found.map { it.title + (if (it.year.isNotEmpty()) " (" + it.year + ")" else "") }.toTypedArray()) { _, w ->
                            closeDetail()
                            openJellyfin(found[w])
                        }
                        .show()
                }
            }
        }.start()
    }

    // ---- Pencarian & Daftar Saya ----

    private fun askSearch() {
        val et = EditText(this).apply {
            isSingleLine = true
            hint = "Judul film atau anime"
        }
        AlertDialog.Builder(this)
            .setTitle("Cari Film & Anime")
            .setView(et)
            .setPositiveButton("Cari") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) {
                    tabIdx = searchIdx
                    refreshTabStyles()
                    val my = ++token
                    rowsBox.removeAllViews()
                    loadCategoryRows(
                        my,
                        listOf(Triple("Hasil Pencarian: $q", "/search/multi", "language=id-ID&include_adult=false&query=" + URLEncoder.encode(q, "UTF-8"))),
                        "movie"
                    )
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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
            o.put("backdrop_path", x.backdrop ?: "")
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
        super.onDestroy()
    }
}
