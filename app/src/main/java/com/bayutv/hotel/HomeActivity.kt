package com.bayutv.hotel

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private val lat: Double get() = prefs.getString("lat", null)?.toDoubleOrNull() ?: -0.0263
    private val lon: Double get() = prefs.getString("lon", null)?.toDoubleOrNull() ?: 109.3425
    private val tzName: String get() = prefs.getString("tz", null) ?: "Asia/Pontianak"
    private val repoApi = "https://api.github.com/repos/" + BuildConfig.REPO + "/releases/latest"

    private var brass = 0xFFB8975A.toInt()
    private var brassLight = 0xFFE6C88F.toInt()
    private var skin: ThemeSpec = Themes.DEFAULT
    private lateinit var decoLayer: FrameLayout
    private lateinit var tvWelcome: TextView
    private val decoAnims = ArrayList<android.animation.Animator>()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val city: String get() = prefs.getString("city", "Pontianak") ?: "Pontianak"

    private lateinit var tvGreet: TextView
    private lateinit var tvName: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvHijri: TextView
    private lateinit var wxIcon: ImageView
    private lateinit var tvWx1: TextView
    private lateinit var tvWx2: TextView
    private lateinit var fc: LinearLayout
    private lateinit var noteBox: LinearLayout
    private lateinit var tvFamilyNote: TextView
    private lateinit var tvCd: TextView
    private lateinit var tvNote: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var notice: Notice
    private lateinit var bgView: ImageView
    private lateinit var glow: View
    private lateinit var glowAnim: ValueAnimator

    private var locSig = ""
    private var isPhoneLayout = false
    private var currentTvTheme = "classic"
    private var useBentoLayout = false
    private var firstTile: View? = null

    private val cells = ArrayList<Pair<TextView, TextView>>()
    private val times = IntArray(5) { -1 }
    private val prayNames = arrayOf("Subuh", "Zuhur", "Asar", "Magrib", "Isya")
    private var prayerDay = ""
    private var prayerTryAt = 0L

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun icon(res: Int, size: Int, color: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return v
    }

    private fun musicIconDrawable(color: Int): Drawable {
        return object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                canvas.drawRoundRect(RectF(w * 0.34f, h * 0.16f, w * 0.82f, h * 0.27f), w * 0.04f, w * 0.04f, p)
                canvas.drawRect(RectF(w * 0.34f, h * 0.20f, w * 0.42f, h * 0.72f), p)
                canvas.drawRect(RectF(w * 0.74f, h * 0.16f, w * 0.82f, h * 0.66f), p)
                canvas.drawOval(RectF(w * 0.16f, h * 0.62f, w * 0.42f, h * 0.82f), p)
                canvas.drawOval(RectF(w * 0.56f, h * 0.56f, w * 0.82f, h * 0.76f), p)
            }
            override fun setAlpha(alpha: Int) { p.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun box(focused: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(if (focused) ((brass and 0x00FFFFFF) or 0x30000000) else 0x14FFFFFF)
        g.setStroke(dp(if (focused) 2 else 1), if (focused) brass else 0x22FFFFFF)
        return g
    }

    // Kartu klasik TV (7 tombol sejajar)
    private fun classicTile(
        label: String,
        res: Int,
        customIcon: ((Int) -> Drawable)? = null,
        action: () -> Unit
    ): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER

        val ic = ImageView(this).apply {
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            if (customIcon != null) {
                setImageDrawable(customIcon(mute))
            } else {
                setImageResource(res)
                setColorFilter(mute, PorterDuff.Mode.SRC_IN)
            }
        }
        t.addView(ic)

        val tx = tv(label, 15.5f, mute, Typeface.SERIF).apply {
            setPadding(dp(4), dp(9), dp(4), 0)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        t.addView(tx)

        t.isFocusable = true
        t.isClickable = true
        t.background = box(false)
        t.setOnClickListener { action() }
        t.setOnFocusChangeListener { _, f ->
            t.background = box(f)
            val c = if (f) brassLight else mute
            if (customIcon != null) {
                ic.setImageDrawable(customIcon(c))
            } else {
                ic.setColorFilter(c, PorterDuff.Mode.SRC_IN)
            }
            tx.setTextColor(c)
            val s = if (f) 1.04f else 1f
            t.animate().scaleX(s).scaleY(s).translationY(if (f) -dp(4).toFloat() else 0f).setDuration(200).start()
            if (f) ObjectAnimator.ofFloat(ic, "rotation", 0f, -8f, 8f, -4f, 0f).setDuration(650).start()
        }

        val lp = LinearLayout.LayoutParams(0, dp(104), 1f)
        lp.setMargins(dp(5), 0, dp(5), 0)
        t.layoutParams = lp
        if (firstTile == null) firstTile = t
        return t
    }

    // Kartu utama Bento 2x2 (Smartphone & TV Bento)
    private fun bentoMainCard(
        title: String,
        subtitle: String,
        res: Int,
        tintColor: Int,
        highlightedBorder: Boolean = false,
        badgeText: String? = null,
        customIcon: ((Int) -> Drawable)? = null,
        onLongClick: (() -> Unit)? = null,
        action: () -> Unit
    ): LinearLayout {
        fun makeBg(focused: Boolean): GradientDrawable {
            val topCol = if (focused) {
                (tintColor and 0x00FFFFFF) or 0x55000000
            } else {
                (tintColor and 0x00FFFFFF) or 0x2E000000
            }
            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(topCol, 0xFF151519.toInt())
            ).apply {
                cornerRadius = dp(16).toFloat()
                when {
                    focused -> setStroke(dp(2), Color.WHITE)
                    highlightedBorder -> setStroke(dp(1), brass)
                    else -> setStroke(dp(1), 0x28FFFFFF)
                }
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            isFocusable = true
            isClickable = true
            background = makeBg(false)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ic = ImageView(this).apply {
            val sz = dp(if (isPhoneLayout) 28 else 32)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            if (customIcon != null) {
                setImageDrawable(customIcon(brassLight))
            } else {
                setImageResource(res)
                setColorFilter(brassLight, PorterDuff.Mode.SRC_IN)
            }
        }
        topRow.addView(ic)
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        if (badgeText != null) {
            val badge = tv(badgeText, 9.5f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                letterSpacing = 0.08f
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFFD32F2F.toInt())
                }
            }
            topRow.addView(badge)
        }
        card.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        card.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val titleTv = tv(
            title,
            if (isPhoneLayout) 16.5f else 18f,
            Color.WHITE,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = tv(
            subtitle,
            if (isPhoneLayout) 11.5f else 12.5f,
            mute,
            Typeface.SANS_SERIF
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(titleTv)
        card.addView(subTv)

        card.setOnClickListener { action() }
        if (onLongClick != null) {
            card.setOnLongClickListener {
                onLongClick()
                true
            }
        }
        card.setOnFocusChangeListener { _, f ->
            card.background = makeBg(f)
            val iconCol = if (f) Color.WHITE else brassLight
            if (customIcon != null) {
                ic.setImageDrawable(customIcon(iconCol))
            } else {
                ic.setColorFilter(iconCol, PorterDuff.Mode.SRC_IN)
            }
            if (!isPhoneLayout) {
                val s = if (f) 1.025f else 1f
                card.animate().scaleX(s).scaleY(s).setDuration(180).start()
            }
        }

        if (firstTile == null) firstTile = card
        return card
    }

    // Kartu utilitas sejajar 1x3 (Pindai Sinyal · Panduan · Pengaturan)
    private fun bentoUtilityCard(label: String, res: Int, action: () -> Unit): LinearLayout {
        fun makeBg(focused: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (focused) ((brass and 0x00FFFFFF) or 0x36000000) else 0xFF17171B.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else 0x26FFFFFF)
            }
        }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isFocusable = true
            isClickable = true
            background = makeBg(false)
        }

        val ic = ImageView(this).apply {
            val sz = dp(if (isPhoneLayout) 22 else 25)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setImageResource(res)
            setColorFilter(brassLight, PorterDuff.Mode.SRC_IN)
        }
        val tx = tv(label, if (isPhoneLayout) 12f else 13f, ivory, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        btn.addView(ic)
        btn.addView(tx)

        btn.setOnClickListener { action() }
        btn.setOnFocusChangeListener { _, f ->
            btn.background = makeBg(f)
            val c = if (f) Color.WHITE else brassLight
            ic.setColorFilter(c, PorterDuff.Mode.SRC_IN)
            tx.setTextColor(if (f) Color.WHITE else ivory)
        }
        return btn
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isPhoneLayout = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600
        currentTvTheme = prefs.getString("tv_home_theme", "classic") ?: "classic"
        useBentoLayout = isPhoneLayout || (currentTvTheme == "bento")

        skin = Themes.pick(this)
        brass = skin.accent
        brassLight = skin.accentLight

        val root = FrameLayout(this)
        val baseBgColor = if (useBentoLayout && skin.id == "default") 0xFF0D0D0F.toInt() else skin.bg
        root.setBackgroundColor(baseBgColor)

        bgView = ImageView(this)
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))

        glow = View(this)
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.gradientType = GradientDrawable.RADIAL_GRADIENT
        gd.gradientRadius = dp(520).toFloat()
        gd.colors = intArrayOf(skin.glow, skin.glow and 0x00FFFFFF)
        glow.background = gd
        root.addView(glow, FrameLayout.LayoutParams(dp(1040), dp(1040)))
        glow.translationX = -dp(300).toFloat()
        glow.translationY = -dp(520).toFloat()
        glowAnim = ValueAnimator.ofFloat(0f, 1f)
        glowAnim.duration = 40000L
        glowAnim.repeatCount = ValueAnimator.INFINITE
        glowAnim.repeatMode = ValueAnimator.REVERSE
        glowAnim.interpolator = LinearInterpolator()
        glowAnim.addUpdateListener {
            val f = it.animatedValue as Float
            glow.translationX = -dp(300) + dp(900) * f
            glow.translationY = -dp(520) + dp(180) * f
        }
        decoLayer = FrameLayout(this)
        root.addView(decoLayer, FrameLayout.LayoutParams(-1, -1))

        // Bangun komponen header & informasi
        buildSharedHeaderAndInfoViews()

        if (isPhoneLayout) {
            buildPhoneBentoScreen(root)
        } else if (useBentoLayout) {
            buildTvBentoScreen(root)
        } else {
            buildTvClassicScreen(root)
        }

        notice = Notice(this, root)
        setContentView(root)
        firstTile?.requestFocus()

        refreshFamilyNote()
        renderWeather()
        loadWeather()
        loadPrayer()
        ui.post(tick)
        Thread { checkUpdate() }.start()
        checkWorkflow()
    }

    private fun buildSharedHeaderAndInfoViews() {
        tvWelcome = tv("SELAMAT DATANG", if (isPhoneLayout) 10.5f else 12.5f, brass, Typeface.SANS_SERIF).apply {
            letterSpacing = 0.22f
            setPadding(dp(8), 0, 0, 0)
        }
        val greetSp = if (isPhoneLayout) 22f else if (useBentoLayout) 28f else 34f
        tvGreet = tv("", greetSp, ivory, Typeface.SERIF)
        tvName = tv(
            prefs.getString("family", "Keluarga Anda") ?: "Keluarga Anda",
            greetSp,
            brass,
            Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        )

        val clockSp = if (isPhoneLayout) 36f else if (useBentoLayout) 46f else 52f
        val dateSp = if (isPhoneLayout) 12f else 14f
        tvClock = tv("", clockSp, ivory, Typeface.create("sans-serif-light", Typeface.NORMAL))
        tvDate = tv("", dateSp, mute, Typeface.SANS_SERIF)
        tvHijri = tv("", dateSp, mute, Typeface.SANS_SERIF)

        wxIcon = icon(R.drawable.ic_cloud_sun, if (isPhoneLayout) 34 else if (useBentoLayout) 42 else 54, brassLight)
        tvWx1 = tv("Memuat cuaca...", if (isPhoneLayout) 14.5f else if (useBentoLayout) 16f else 18f, ivory, Typeface.SANS_SERIF)
        tvWx2 = tv("", if (isPhoneLayout) 12.5f else if (useBentoLayout) 13.5f else 15f, mute, Typeface.SANS_SERIF)
        fc = LinearLayout(this)

        noteBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor((brass and 0x00FFFFFF) or 0x1A000000)
                setStroke(dp(1), (brass and 0x00FFFFFF) or 0x44000000)
            }
            visibility = View.GONE
        }
        noteBox.addView(icon(R.drawable.ic_book, 16, brassLight))
        noteBox.addView(
            tv("CATATAN · ", 11f, brassLight, Typeface.SANS_SERIF).apply {
                letterSpacing = 0.08f
                setPadding(dp(8), 0, 0, 0)
            }
        )
        tvFamilyNote = tv("", 13f, ivory, Typeface.SANS_SERIF).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        noteBox.addView(tvFamilyNote, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun buildBrandBadge(): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val logo = ImageView(this@HomeActivity).apply {
                setImageResource(R.drawable.ic_bbox_mark)
            }
            val logoSz = dp(if (isPhoneLayout) 24 else 30)
            addView(logo, LinearLayout.LayoutParams(logoSz, logoSz))
            addView(tvWelcome)
        }
    }

    private fun buildPrayerDock(): LinearLayout {
        cells.clear()
        val pray = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (useBentoLayout) 0xFF151519.toInt() else 0x14FFFFFF)
                setStroke(dp(1), 0x26FFFFFF)
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        for (i in 0..4) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val n = tv(prayNames[i], if (isPhoneLayout) 11.5f else if (useBentoLayout) 12f else 14f, mute, Typeface.SANS_SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val t = tv("--:--", if (isPhoneLayout) 16.5f else if (useBentoLayout) 17.5f else 22f, ivory, Typeface.SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            c.addView(n)
            c.addView(t)
            cells.add(Pair(n, t))
            pray.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return pray
    }

    private fun buildBentoMenuColumn(): LinearLayout {
        val cardTv = bentoMainCard(
            title = "TV Live",
            subtitle = "Siaran Streaming",
            res = R.drawable.ic_tv,
            tintColor = 0xFFB8975A.toInt(),
            highlightedBorder = true,
            badgeText = "LIVE"
        ) { startActivity(Intent(this, MainActivity::class.java)) }

        val cardFilm = bentoMainCard(
            title = "Film",
            subtitle = "Bioskop & Serial",
            res = R.drawable.ic_film,
            tintColor = 0xFF7A2828.toInt()
        ) {
            if (Lock.isLocked(this, "film")) {
                Lock.ask(this, "Menu Film terkunci", { startActivity(Intent(this, FilmActivity::class.java)) })
            } else {
                startActivity(Intent(this, FilmActivity::class.java))
            }
        }

        val cardMusic = bentoMainCard(
            title = "Musik & Radio",
            subtitle = "Lagu, Karaoke & Radio",
            res = 0,
            tintColor = 0xFF235C42.toInt(),
            customIcon = { c -> musicIconDrawable(c) }
        ) { startActivity(Intent(this, RadioMusicActivity::class.java)) }

        val cardAntenna = bentoMainCard(
            title = "TV Antena",
            subtitle = "Siaran Digital DVB-T2",
            res = R.drawable.ic_antenna,
            tintColor = 0xFF8F7443.toInt(),
            highlightedBorder = true,
            onLongClick = { openAntennaMenu() }
        ) { if (!launchSaved()) chooseAntennaApp() }

        val btnScan = bentoUtilityCard("Pindai Sinyal", R.drawable.ic_scan) {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        val btnPanduan = bentoUtilityCard("Panduan", R.drawable.ic_book) {
            startActivity(Intent(this, PanduanActivity::class.java))
        }
        val btnSettings = bentoUtilityCard("Pengaturan", R.drawable.ic_phone) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val menuCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val cardH = if (isPhoneLayout) dp(112) else 0
        val rowWeight = if (isPhoneLayout) 0f else 1f

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        row1.addView(cardTv, LinearLayout.LayoutParams(0, if (isPhoneLayout) cardH else -1, 1f).apply { rightMargin = dp(6) })
        row1.addView(cardFilm, LinearLayout.LayoutParams(0, if (isPhoneLayout) cardH else -1, 1f).apply { leftMargin = dp(6) })
        menuCol.addView(row1, LinearLayout.LayoutParams(-1, if (isPhoneLayout) -2 else 0, rowWeight).apply { bottomMargin = dp(10) })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        row2.addView(cardMusic, LinearLayout.LayoutParams(0, if (isPhoneLayout) cardH else -1, 1f).apply { rightMargin = dp(6) })
        row2.addView(cardAntenna, LinearLayout.LayoutParams(0, if (isPhoneLayout) cardH else -1, 1f).apply { leftMargin = dp(6) })
        menuCol.addView(row2, LinearLayout.LayoutParams(-1, if (isPhoneLayout) -2 else 0, rowWeight).apply { bottomMargin = dp(10) })

        val utilH = dp(if (isPhoneLayout) 66 else 74)
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        row3.addView(btnScan, LinearLayout.LayoutParams(0, utilH, 1f).apply { rightMargin = dp(5) })
        row3.addView(btnPanduan, LinearLayout.LayoutParams(0, utilH, 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        row3.addView(btnSettings, LinearLayout.LayoutParams(0, utilH, 1f).apply { leftMargin = dp(5) })
        menuCol.addView(row3, LinearLayout.LayoutParams(-1, -2))

        tiles = menuCol
        return menuCol
    }

    // 1. TAMPILAN SMARTPHONE (PORTRAIT · BENTO 2x2 + 1x3)
    private fun buildPhoneBentoScreen(root: FrameLayout) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(18), dp(18), dp(18), dp(22))
        }

        // Baris atas: Sapaan kiri + Jam kanan
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(buildBrandBadge())
        left.addView(tvGreet)
        left.addView(tvName)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvClock.gravity = Gravity.END
        tvDate.gravity = Gravity.END
        tvHijri.gravity = Gravity.END
        right.addView(tvClock)
        right.addView(tvDate)
        right.addView(tvHijri)

        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(right, LinearLayout.LayoutParams(-2, -2))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        val line = View(this).apply { setBackgroundColor(0xFF26262C.toInt()) }
        col.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) })

        // Bar Cuaca Ringkas 2 Baris Rapi (Tanpa kata terpotong)
        val wxBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val wxRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wxRow1.addView(wxIcon)
        val wxTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        wxTextCol.addView(tvWx1)
        wxTextCol.addView(tvWx2)
        wxRow1.addView(wxTextCol, LinearLayout.LayoutParams(0, -2, 1f))
        wxBlock.addView(wxRow1)

        fc.orientation = LinearLayout.HORIZONTAL
        fc.gravity = Gravity.END
        wxBlock.addView(fc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(wxBlock)

        col.addView(noteBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val bentoMenu = buildBentoMenuColumn()
        col.addView(bentoMenu, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })

        // Dock Jadwal Sholat
        val pray = buildPrayerDock()
        col.addView(pray, LinearLayout.LayoutParams(-1, -2))

        tvCd = tv("", 13f, brassLight, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(tvCd)

        tvNote = tv("Cuaca: Open-Meteo. Kualitas udara: CAMS. Jadwal sholat sesuai Kemenag RI.", 10.5f, 0xFF6F685B.toInt(), Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        col.addView(tvNote)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(col, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    // 2. TAMPILAN TV BENTO (LANDSCAPE · 2 KOLOM MODERN)
    private fun buildTvBentoScreen(root: FrameLayout) {
        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(dp(38), dp(26), dp(38), dp(24))
        }

        // Kolom Kiri: Info & Jadwal Sholat
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(26), 0)
        }
        infoCol.addView(buildBrandBadge())
        infoCol.addView(tvGreet)
        infoCol.addView(tvName)

        tvClock.gravity = Gravity.START
        tvDate.gravity = Gravity.START
        tvHijri.gravity = Gravity.START
        infoCol.addView(tvClock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        infoCol.addView(tvDate)
        infoCol.addView(tvHijri)

        val wxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        wxRow.addView(wxIcon)
        val wt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        wt.addView(tvWx1)
        wt.addView(tvWx2)
        wxRow.addView(wt, LinearLayout.LayoutParams(0, -2, 1f))
        infoCol.addView(wxRow)

        fc.orientation = LinearLayout.HORIZONTAL
        infoCol.addView(fc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        infoCol.addView(noteBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        infoCol.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val pray = buildPrayerDock()
        infoCol.addView(pray, LinearLayout.LayoutParams(-1, -2))

        tvCd = tv("", 13f, brassLight, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        infoCol.addView(tvCd)
        tvNote = tv("Cuaca: Open-Meteo · Kualitas udara: CAMS · Jadwal sholat Kemenag RI.", 10.5f, 0xFF6F685B.toInt(), Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(tvNote)

        // Kolom Kanan: Kartu Bento 2x2 + 1x3
        val menuCol = buildBentoMenuColumn()

        mainRow.addView(infoCol, LinearLayout.LayoutParams(0, -1, 0.88f))
        mainRow.addView(menuCol, LinearLayout.LayoutParams(0, -1, 1.12f))
        root.addView(mainRow, FrameLayout.LayoutParams(-1, -1))
    }

    // 3. TAMPILAN TV KLASIK B-BOX (7 TOMBOL SEJAJAR BAWAAN)
    private fun buildTvClassicScreen(root: FrameLayout) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(44), dp(28), dp(44), dp(18))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(buildBrandBadge())
        left.addView(tvGreet)
        left.addView(tvName)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvClock.gravity = Gravity.END
        tvDate.gravity = Gravity.END
        tvHijri.gravity = Gravity.END
        right.addView(tvClock)
        right.addView(tvDate)
        right.addView(tvHijri)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(right, LinearLayout.LayoutParams(-2, -2))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        val line = View(this).apply { setBackgroundColor(0xFF34302A.toInt()) }
        col.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })

        val wxOuter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wxMainRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        wxMainRow.addView(wxIcon)
        val wt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        wt.addView(tvWx1)
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row2.addView(icon(R.drawable.ic_wind, 16, mute))
        tvWx2.setPadding(dp(6), 0, 0, 0)
        row2.addView(tvWx2)
        wt.addView(row2)
        wxMainRow.addView(wt, LinearLayout.LayoutParams(0, -2, 1f))
        wxOuter.addView(wxMainRow, LinearLayout.LayoutParams(0, -2, 1f))
        wxOuter.addView(fc)
        col.addView(wxOuter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        col.addView(noteBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1.1f))

        val tTv = classicTile("TV Live", R.drawable.ic_tv) { startActivity(Intent(this, MainActivity::class.java)) }
        val tMusic = classicTile("Musik & Radio", 0, customIcon = { c -> musicIconDrawable(c) }) {
            startActivity(Intent(this, RadioMusicActivity::class.java))
        }
        val tAntenna = classicTile("TV Antena", R.drawable.ic_antenna) {
            if (!launchSaved()) chooseAntennaApp()
        }
        tAntenna.setOnLongClickListener {
            openAntennaMenu()
            true
        }
        val tFilm = classicTile("Film", R.drawable.ic_film) {
            if (Lock.isLocked(this, "film")) Lock.ask(this, "Menu Film terkunci", { startActivity(Intent(this, FilmActivity::class.java)) })
            else startActivity(Intent(this, FilmActivity::class.java))
        }
        val tPanduan = classicTile("Panduan", R.drawable.ic_book) { startActivity(Intent(this, PanduanActivity::class.java)) }
        val tScan = classicTile("Pindai Sinyal", R.drawable.ic_scan) { startActivity(Intent(this, ScanActivity::class.java)) }
        val tSettings = classicTile("Pengaturan", R.drawable.ic_phone) { startActivity(Intent(this, SettingsActivity::class.java)) }

        tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(12))
            addView(tTv)
            addView(tMusic)
            addView(tAntenna)
            addView(tFilm)
            addView(tPanduan)
            addView(tScan)
            addView(tSettings)
        }
        col.addView(tiles, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 0.9f))

        val pray = buildPrayerDock()
        col.addView(pray, LinearLayout.LayoutParams(-1, -2))

        tvCd = tv("", 15f, mute, Typeface.SANS_SERIF).apply {
            setPadding(dp(6), dp(8), 0, 0)
        }
        col.addView(tvCd)
        tvNote = tv("Cuaca: Open-Meteo. Kualitas udara: CAMS. Jadwal sholat sesuai Kemenag RI.", 11f, 0xFF6F685B.toInt(), Typeface.SANS_SERIF).apply {
            setPadding(dp(6), dp(4), 0, 0)
        }
        col.addView(tvNote)

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun refreshFamilyNote() {
        val raw = (prefs.getString("note", "") ?: "").trim()
        if (raw.isEmpty()) {
            noteBox.visibility = View.GONE
        } else {
            tvFamilyNote.text = raw.replace('\n', ' ')
            noteBox.visibility = View.VISIBLE
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            ui.postDelayed(this, 15000L)
        }
    }

    private fun hijri(d: Date): String {
        return try {
            val f = android.icu.text.SimpleDateFormat("d MMMM y", android.icu.util.ULocale("id_ID@calendar=islamic-umalqura"))
            f.format(d) + " H"
        } catch (e: Exception) {
            ""
        }
    }

    private fun fmt(m: Int): String = if (m < 0) "--:--" else String.format(Locale.US, "%02d:%02d", (m + 1440) % 1440 / 60, (m + 1440) % 60)

    private fun prayerOffset(): Int =
        (prefs.getString("adzan_offset", "0") ?: "0").toIntOrNull()?.coerceIn(-15, 15) ?: 0

    private fun refreshClock() {
        val now = Date()
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        tvClock.text = String.format(Locale.US, "%02d:%02d", h, m)
        tvGreet.text = if (h < 4) "Selamat Malam," else if (h < 11) "Selamat Pagi," else if (h < 15) "Selamat Siang," else if (h < 18) "Selamat Sore," else "Selamat Malam,"
        tvDate.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(now)
        tvHijri.text = hijri(now)
        tvWelcome.text = welcomeText()
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(now)
        if (today != prayerDay && System.currentTimeMillis() - prayerTryAt > 60000L) loadPrayer()
        updatePrayerUI(h * 60 + m)
    }

    private fun updatePrayerUI(nowMin: Int) {
        if (times[0] < 0) {
            tvCd.text = "Jadwal sholat sedang dimuat..."
            return
        }
        val off = prayerOffset()
        var nx = times.indexOfFirst { (it + off) > nowMin }
        val diff = if (nx < 0) {
            nx = 0
            (times[0] + off) + 1440 - nowMin
        } else {
            (times[nx] + off) - nowMin
        }
        for (i in 0..4) {
            val hi = i == nx
            cells[i].first.setTextColor(if (hi) brass else mute)
            cells[i].second.setTextColor(if (hi) brassLight else ivory)
            cells[i].second.text = fmt(times[i] + off)
        }
        tvCd.text = extraLine(nowMin) + prayNames[nx] + " dalam " + (diff / 60) + " jam " + (diff % 60) + " menit"
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "B-Box")
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPrayer() {
        prayerTryAt = System.currentTimeMillis()
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        if (prefs.getString("pray_date", "") == today) {
            val cached = prefs.getString("pray", null)
            if (cached != null && applyPrayer(cached, today)) return
        }
        Thread {
            val r = httpGet("https://api.aladhan.com/v1/timings/" + today + "?latitude=" + lat + "&longitude=" + lon + "&method=20&timezonestring=" + java.net.URLEncoder.encode(tzName, "UTF-8"))
            if (r != null) {
                ui.post {
                    if (applyPrayer(r, today)) prefs.edit().putString("pray_date", today).putString("pray", r).apply()
                }
            }
        }.start()
    }

    private fun applyPrayer(json: String, day: String): Boolean {
        return try {
            val t = JSONObject(json).getJSONObject("data").getJSONObject("timings")
            val keys = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            val tmp = IntArray(5)
            for (i in 0..4) {
                val s = t.getString(keys[i])
                tmp[i] = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
            }
            for (i in 0..4) times[i] = tmp[i]
            prayerDay = day
            refreshClock()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadWeather() {
        fetchWeather()
        ui.postDelayed({ loadWeather() }, 30 * 60 * 1000L)
    }

    private fun fetchWeather() {
        Thread {
            val w = httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m" +
                "&daily=weather_code,temperature_2m_max&timezone=" + java.net.URLEncoder.encode(tzName, "UTF-8") + "&forecast_days=4")
            val a = httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon +
                "&current=us_aqi&timezone=" + java.net.URLEncoder.encode(tzName, "UTF-8"))
            if (w != null) prefs.edit().putString("wx", w).apply()
            if (a != null) prefs.edit().putString("aq", a).apply()
            ui.post { renderWeather() }
        }.start()
    }

    private fun cond(c: Int): Pair<String, Int> {
        return when (c) {
            0 -> Pair("Cerah", R.drawable.ic_sun)
            1, 2 -> Pair("Berawan", R.drawable.ic_cloud_sun)
            3 -> Pair("Mendung", R.drawable.ic_cloud)
            45, 48 -> Pair("Berkabut", R.drawable.ic_cloud)
            51, 53, 55, 56, 57 -> Pair("Gerimis", R.drawable.ic_rain)
            61, 63, 65, 66, 67, 80, 81, 82 -> Pair("Hujan", R.drawable.ic_rain)
            95, 96, 99 -> Pair("Hujan petir", R.drawable.ic_storm)
            else -> Pair("Berawan", R.drawable.ic_cloud_sun)
        }
    }

    private fun dirName(deg: Double): String {
        val n = arrayOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
        return n[(((deg + 22.5) / 45.0).toInt()) % 8]
    }

    private fun renderWeather() {
        try {
            val w = prefs.getString("wx", null) ?: return
            val o = JSONObject(w)
            val cur = o.getJSONObject("current")
            val temp = Math.round(cur.getDouble("temperature_2m")).toInt()
            val c = cond(cur.getInt("weather_code"))
            val ws = Math.round(cur.getDouble("wind_speed_10m")).toInt()
            val wd = cur.getDouble("wind_direction_10m")
            wxIcon.setImageResource(c.second)

            val head = temp.toString() + "\u00B0"
            val sp = SpannableString(head + "  " + c.first + " \u00B7 " + city)
            sp.setSpan(RelativeSizeSpan(if (isPhoneLayout) 1.55f else if (useBentoLayout) 1.75f else 2.1f), 0, head.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvWx1.text = sp

            var aqText = ""
            var aqColor = mute
            val a = prefs.getString("aq", null)
            if (a != null) {
                val aq = JSONObject(a).getJSONObject("current").getInt("us_aqi")
                val lbl = if (aq <= 50) "Baik" else if (aq <= 100) "Sedang" else if (aq <= 150) "Kurang sehat" else "Tidak sehat"
                aqColor = if (aq <= 50) 0xFF7FB08A.toInt() else if (aq <= 100) brassLight else 0xFFC9706A.toInt()
                aqText = "$aq ($lbl)"
            }

            if (useBentoLayout) {
                val base = if (isPhoneLayout) "Udara " else "Angin $ws km/j · Udara "
                if (aqText.isEmpty()) {
                    tvWx2.text = "Angin $ws km/j ${dirName(wd)}"
                } else {
                    val s2 = SpannableString(base + aqText)
                    s2.setSpan(ForegroundColorSpan(aqColor), base.length, base.length + aqText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tvWx2.text = s2
                }
            } else {
                val base = "Angin " + ws + " km/j " + dirName(wd)
                if (aqText.isEmpty()) {
                    tvWx2.text = base
                } else {
                    val pre = base + "  \u00B7  Udara "
                    val s2 = SpannableString(pre + aqText)
                    s2.setSpan(ForegroundColorSpan(aqColor), pre.length, pre.length + aqText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tvWx2.text = s2
                }
            }

            fc.removeAllViews()
            val d = o.getJSONObject("daily")
            val codes = d.getJSONArray("weather_code")
            val maxs = d.getJSONArray("temperature_2m_max")
            for (i in 1..3) {
                if (i >= codes.length()) break
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, i)
                val item = LinearLayout(this)
                item.orientation = if (useBentoLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                item.gravity = Gravity.CENTER
                item.setPadding(dp(if (useBentoLayout) 6 else 10), dp(2), dp(if (useBentoLayout) 6 else 10), dp(2))
                item.addView(tv(SimpleDateFormat("EEE", Locale("id", "ID")).format(cal.time), 12.5f, mute, Typeface.SANS_SERIF))
                val ic = icon(cond(codes.getInt(i)).second, if (useBentoLayout) 18 else 28, 0xFFCFC7B8.toInt())
                if (useBentoLayout) ic.setPadding(dp(4), 0, dp(4), 0)
                item.addView(ic)
                item.addView(tv(Math.round(maxs.getDouble(i)).toInt().toString() + "\u00B0", 13f, ivory, Typeface.SANS_SERIF))
                if (useBentoLayout && !isPhoneLayout) {
                    fc.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
                } else {
                    fc.addView(item)
                }
            }
        } catch (e: Exception) {
            tvWx1.text = "Cuaca belum tersedia"
        }
    }

    private fun checkWorkflow() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("wf_check", 0L) < 6L * 3600000L) return
        Thread {
            val base = "https://api.github.com/repos/" + BuildConfig.REPO
            val wf = Net.get(base + "/actions/workflows/channels.yml")
            val cm = Net.get(base + "/commits/data")
            var msg: String? = null
            try {
                if (wf != null && JSONObject(wf).optString("state").startsWith("disabled")) {
                    msg = "Pembaruan daftar channel otomatis BERHENTI karena GitHub menonaktifkan jadwalnya.\n\n" +
                        "Cara menyalakan lagi: buka repo di GitHub, tab Actions, pilih \"Perbarui daftar channel\", lalu tekan Enable workflow."
                }
                if (msg == null && cm != null) {
                    val d = JSONObject(cm).getJSONObject("commit").getJSONObject("committer").getString("date")
                    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val t = f.parse(d)?.time ?: 0L
                    val days = (now - t) / 86400000L
                    if (t > 0L && days >= 2L) {
                        msg = "Daftar channel terakhir diperbarui " + days + " hari lalu.\n\n" +
                            "Buka repo di GitHub, tab Actions, pilih \"Perbarui daftar channel\" untuk melihat penyebabnya (tanda merah = gagal), lalu tekan Run workflow."
                    }
                }
            } catch (e: Exception) {
            }
            if (wf != null || cm != null) prefs.edit().putLong("wf_check", now).apply()
            val m = msg ?: return@Thread
            ui.post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this).setTitle("Perlu perhatian").setMessage(m).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun checkAutostartPermission() {
        if (isPhoneLayout) return
        val mode = prefs.getString("autostart", "Mati") ?: "Mati"
        if (mode == "Mati" || Build.VERSION.SDK_INT < 29) return
        if (Settings.canDrawOverlays(this)) return
        if (prefs.getString("overlay_asked", "") == mode) return
        prefs.edit().putString("overlay_asked", mode).apply()
        AlertDialog.Builder(this)
            .setTitle("Buka otomatis saat TV menyala")
            .setMessage("Agar B-Box bisa terbuka sendiri saat TV dinyalakan, izinkan \"Tampilkan di atas aplikasi lain\" untuk B-Box.")
            .setPositiveButton("Buka pengaturan") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                } catch (e: Exception) {
                    AlertDialog.Builder(this)
                        .setMessage("TV ini tidak menyediakan pengaturan izin tersebut, jadi buka otomatis mungkin tidak berjalan.")
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun checkUpdate() {
        try {
            val c = URL(repoApi).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "B-Box")
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
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Ada versi baru (v" + tag + ")")
            .setMessage("Tekan Perbarui untuk mengunduh dan memasang. Aplikasi akan tertutup sebentar saat pemasangan.")
            .setPositiveButton("Perbarui") { _, _ -> startUpdate(url) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            notice.show("Perlu izin", "Izinkan 'Pasang aplikasi tak dikenal' untuk B-Box, lalu buka lagi aplikasinya dan tekan Perbarui.", 9000L)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        notice.show("Pembaruan", "Mengunduh versi baru...")
        Thread {
            try {
                val f = File(cacheDir, "update.apk")
                f.delete()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "B-Box")
                val expect = c.contentLength
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                if (f.length() < 100000L || (expect > 0 && f.length() != expect.toLong())) throw java.io.IOException("unduhan tidak lengkap")
                ui.post { if (!isFinishing) installApk(f) }
            } catch (e: Exception) {
                ui.post { notice.show("Pembaruan gagal", "Tidak bisa mengunduh: " + e.javaClass.simpleName, 6000L) }
            }
        }.start()
    }

    private fun installApk(f: File) {
        notice.hide()
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun dur(m: Int): String = (m / 60).toString() + " jam " + (m % 60) + " menit"

    private fun welcomeText(): String {
        if (skin.id == "ramadan") {
            val h = Themes.hijri()
            return if (h[0] == 8) "RAMADAN KAREEM  \u00B7  HARI KE-" + h[1] else "MARHABAN YA RAMADAN"
        }
        if (skin.id == "newyear") {
            val c = Calendar.getInstance()
            val y = if (c.get(Calendar.MONTH) == Calendar.DECEMBER) c.get(Calendar.YEAR) + 1 else c.get(Calendar.YEAR)
            return "SELAMAT TAHUN BARU " + y
        }
        return "SELAMAT DATANG"
    }

    private fun extraLine(nowMin: Int): String {
        val sep = "   \u00B7   "
        if (skin.id == "ramadan") {
            val off = prayerOffset()
            val imsak = times[0] + off - 10
            val magrib = times[3] + off
            return if (nowMin < imsak) "Imsak dalam " + dur(imsak - nowMin) + sep
            else if (nowMin < magrib) "Berbuka dalam " + dur(magrib - nowMin) + sep
            else "Imsak besok dalam " + dur(imsak + 1440 - nowMin) + sep
        }
        if (skin.id == "newyear") {
            val c = Calendar.getInstance()
            if (c.get(Calendar.MONTH) == Calendar.DECEMBER) {
                val left = 31 - c.get(Calendar.DAY_OF_MONTH)
                val y = c.get(Calendar.YEAR) + 1
                return (if (left == 0) "Malam pergantian tahun menuju " + y else "Menuju " + y + ": " + left + " hari lagi") + sep
            }
            return "Selamat Tahun Baru!" + sep
        }
        return ""
    }

    private fun buildDeco() {
        for (a in decoAnims) a.cancel()
        decoAnims.clear()
        decoLayer.removeAllViews()
        if (skin.id == "ramadan") {
            val cr = ImageView(this)
            cr.setImageResource(R.drawable.ic_crescent)
            cr.setColorFilter(skin.accent, PorterDuff.Mode.SRC_IN)
            cr.alpha = 0.10f
            val clp = FrameLayout.LayoutParams(dp(300), dp(300), Gravity.BOTTOM or Gravity.END)
            clp.setMargins(0, 0, dp(40), dp(30))
            decoLayer.addView(cr, clp)
            val offs = intArrayOf(-110, 0, 110)
            val lens = intArrayOf(34, 58, 42)
            for (i in 0..2) {
                val ll = LinearLayout(this)
                ll.orientation = LinearLayout.VERTICAL
                ll.gravity = Gravity.CENTER_HORIZONTAL
                val str = View(this)
                str.setBackgroundColor((skin.accent and 0x00FFFFFF) or 0x66000000)
                ll.addView(str, LinearLayout.LayoutParams(dp(1), dp(lens[i])))
                val lan = ImageView(this)
                lan.setImageResource(R.drawable.ic_lantern)
                lan.setColorFilter(skin.accentLight, PorterDuff.Mode.SRC_IN)
                ll.addView(lan, LinearLayout.LayoutParams(dp(40), dp(40)))
                ll.translationX = dp(offs[i]).toFloat()
                decoLayer.addView(ll, FrameLayout.LayoutParams(dp(40), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                ll.pivotX = dp(20).toFloat()
                ll.pivotY = 0f
                val a = ObjectAnimator.ofFloat(ll, "rotation", -4f, 4f)
                a.duration = 3400L + i * 500L
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.start()
                decoAnims.add(a)
            }
        } else if (skin.id == "newyear") {
            val dm = resources.displayMetrics
            val rnd = java.util.Random(2027L)
            for (i in 0 until 14) {
                val sz = dp(10 + rnd.nextInt(14))
                val sp = ImageView(this)
                sp.setImageResource(R.drawable.ic_spark)
                sp.setColorFilter(if (i % 3 == 0) 0xFFFFFFFF.toInt() else skin.accentLight, PorterDuff.Mode.SRC_IN)
                sp.alpha = 0.2f
                val lp = FrameLayout.LayoutParams(sz, sz)
                lp.leftMargin = rnd.nextInt((dm.widthPixels - sz).coerceAtLeast(1))
                lp.topMargin = rnd.nextInt((dm.heightPixels - sz).coerceAtLeast(1))
                decoLayer.addView(sp, lp)
                val tw = ObjectAnimator.ofFloat(sp, "alpha", 0.15f, 0.75f)
                tw.duration = 1800L + rnd.nextInt(1600)
                tw.repeatCount = ValueAnimator.INFINITE
                tw.repeatMode = ValueAnimator.REVERSE
                tw.startDelay = rnd.nextInt(2000).toLong()
                tw.start()
                val fl = ObjectAnimator.ofFloat(sp, "translationY", 0f, -dp(40).toFloat())
                fl.duration = 7000L + rnd.nextInt(4000)
                fl.repeatCount = ValueAnimator.INFINITE
                fl.repeatMode = ValueAnimator.REVERSE
                fl.start()
                decoAnims.add(tw)
                decoAnims.add(fl)
            }
        }
    }

    private fun runTunerTest() {
        val sb = StringBuilder()
        try {
            val granted = checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
            sb.append("Izin baca daftar TV: " + (if (granted) "diberikan" else "TIDAK diberikan") + "\n")
            if (!granted) {
                try {
                    requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), 77)
                } catch (e: Exception) {
                }
            }
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
            val inputs = tim.tvInputList
            sb.append("Input TV terlihat: " + inputs.size + "\n")
            for (i in inputs) {
                val type = when (i.type) {
                    TvInputInfo.TYPE_TUNER -> "TUNER"
                    TvInputInfo.TYPE_HDMI -> "HDMI"
                    TvInputInfo.TYPE_OTHER -> "LAINNYA"
                    else -> "tipe " + i.type
                }
                sb.append("- " + type + ": " + i.loadLabel(this) + "\n")
            }
            val tuner = inputs.firstOrNull { it.type == TvInputInfo.TYPE_TUNER }
            if (tuner == null) {
                sb.append("\nTidak ada input bertipe TUNER yang terlihat.\n")
            } else {
                try {
                    val cur = contentResolver.query(
                        TvContract.buildChannelsUriForInput(tuner.id),
                        arrayOf(android.provider.BaseColumns._ID, TvContract.Channels.COLUMN_DISPLAY_NAME),
                        null, null, null
                    )
                    if (cur == null) {
                        sb.append("\nDaftar channel antena: tidak bisa dibaca.\n")
                    } else {
                        sb.append("\nChannel antena terbaca: " + cur.count + "\n")
                        var n = 0
                        while (cur.moveToNext() && n < 8) {
                            sb.append("  " + cur.getString(1) + "\n")
                            n++
                        }
                        cur.close()
                    }
                } catch (e: SecurityException) {
                    sb.append("\nDaftar channel antena DITOLAK sistem (SecurityException).\n")
                } catch (e: Exception) {
                    sb.append("\nGagal membaca channel: " + e.javaClass.simpleName + "\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Gagal: " + e.javaClass.simpleName)
        }
        AlertDialog.Builder(this)
            .setTitle("Hasil uji tuner")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun tryLiveTv(): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchComponent(flat: String): Boolean {
        return try {
            val cn = ComponentName.unflattenFromString(flat) ?: return false
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
            i.component = cn
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchSaved(): Boolean {
        val saved = prefs.getString("antenna", null) ?: return tryLiveTv()
        val ok = if (saved == "system") tryLiveTv() else launchComponent(saved)
        if (!ok) notice.show("Tidak terbuka", "Pilih aplikasi antena lagi (tahan OK pada TV Antena).", 4000L)
        return ok
    }

    private fun savedLabel(): String? {
        val saved = prefs.getString("antenna", null) ?: return null
        if (saved == "system") return "Live TV bawaan sistem"
        return try {
            val cn = ComponentName.unflattenFromString(saved) ?: return null
            packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            "aplikasi antena pilihan"
        }
    }

    private fun openAntennaMenu() {
        val label = savedLabel()
        AlertDialog.Builder(this)
            .setTitle("Pengaturan & Uji TV Antena")
            .setItems(arrayOf(
                if (label != null) "Buka " + label else "Pilih aplikasi antena",
                "Ganti aplikasi antena",
                "Arah & sinyal antena (Kompas MUX)",
                "Antena di dalam aplikasi (uji hybrid)",
                "Uji tuner antena (info)",
                "Uji pintasan RCTI+ / Vidio"
            )) { _, which ->
                when (which) {
                    0 -> if (label != null) launchSaved() else chooseAntennaApp()
                    1 -> chooseAntennaApp()
                    2 -> startActivity(Intent(this, AntennaAimActivity::class.java))
                    3 -> startActivity(Intent(this, AntennaActivity::class.java))
                    4 -> runTunerTest()
                    else -> testAppLinks()
                }
            }
            .show()
    }

    private fun testAppLinks() {
        val res = try {
            AppLinks.diagnose(this)
        } catch (e: Exception) {
            Pair("Gagal memeriksa: " + e.javaClass.simpleName, emptyList<AppLinks.Trial>())
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Uji pintasan RCTI+ / Vidio")
            .setMessage(res.first + "\n\nPilih \"Coba satu per satu\" untuk mencari cara yang langsung membuka siaran.")
            .setNegativeButton("Tutup", null)
        if (res.second.isNotEmpty()) b.setPositiveButton("Coba satu per satu") { _, _ -> chooseTrial(res.second) }
        b.show()
    }

    private fun chooseTrial(trials: List<AppLinks.Trial>) {
        AlertDialog.Builder(this)
            .setTitle("Pilih lalu lihat hasilnya")
            .setItems(trials.map { it.label }.toTypedArray()) { _, w ->
                val t = trials[w]
                prefs.edit().putInt("trial_last", w).apply()
                val ok = if (t.url != null) AppLinks.openUrl(this, t.pkg, t.url) else AppLinks.openActivity(this, t.pkg, t.cls ?: "")
                if (!ok) {
                    notice.show("Tidak bisa dibuka", t.label, 4000L)
                } else if (t.url != null && t.target != null) {
                    pendingTrial = t
                }
            }
            .show()
    }

    private var pendingTrial: AppLinks.Trial? = null

    private fun confirmTrial() {
        val t = pendingTrial ?: return
        pendingTrial = null
        val target = t.target ?: return
        val url = t.url ?: return
        AlertDialog.Builder(this)
            .setTitle("Hasil percobaan")
            .setMessage("Tadi " + target.provider + " langsung menampilkan siaran " + target.name + "?\n\n" + url)
            .setPositiveButton("Ya, pakai cara ini") { _, _ ->
                AppLinks.savePattern(this, target, url)
                notice.show(null, "Disimpan. Channel " + target.provider + " akan langsung membuka siarannya.", 4000L)
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    private fun chooseAntennaApp() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val names = ArrayList<String>()
        names.add("Live TV bawaan sistem")
        names.add("Antena di dalam aplikasi (hybrid)")
        for (a in apps) names.add(a.loadLabel(pm).toString())
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi TV Antena")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.edit().putString("antenna", "system").apply()
                    if (!tryLiveTv()) notice.show("Tidak terbuka", "Live TV bawaan tidak bisa dibuka langsung. Tahan OK pada TV Antena untuk memilih aplikasi.", 6000L)
                } else if (which == 1) {
                    startActivity(Intent(this, AntennaActivity::class.java))
                } else {
                    val ri = apps[which - 2]
                    val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                    prefs.edit().putString("antenna", cn.flattenToString()).apply()
                    launchComponent(cn.flattenToString())
                }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        glowAnim.cancel()
        for (a in decoAnims) a.cancel()
    }

    private fun applyBackground() {
        val mode = prefs.getString("bg", "none")
        bgView.clearColorFilter()
        val wantGlow = mode == "none"
        glow.visibility = if (wantGlow) View.VISIBLE else View.GONE
        if (wantGlow) {
            if (!glowAnim.isStarted) glowAnim.start()
        } else {
            glowAnim.cancel()
        }
        if (mode == "senja") {
            val sky = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF14121A.toInt(), 0xFF4A2F33.toInt(), 0xFF9C6038.toInt()))
            val shade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x90121110.toInt(), 0xE0121110.toInt()))
            bgView.scaleType = ImageView.ScaleType.FIT_XY
            bgView.setImageDrawable(LayerDrawable(arrayOf(sky, shade)))
        } else if (mode == "photo") {
            try {
                val o = BitmapFactory.Options()
                o.inPreferredConfig = Bitmap.Config.RGB_565
                val bmp = BitmapFactory.decodeFile(File(filesDir, "bg.jpg").absolutePath, o)
                if (bmp != null) {
                    bgView.scaleType = ImageView.ScaleType.CENTER_CROP
                    bgView.setImageBitmap(bmp)
                    bgView.setColorFilter(0xB5121110.toInt(), PorterDuff.Mode.SRC_OVER)
                } else {
                    bgView.setImageDrawable(null)
                }
            } catch (e: Exception) {
                bgView.setImageDrawable(null)
            }
        } else {
            bgView.setImageDrawable(null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingTrial != null) ui.postDelayed({ confirmTrial() }, 600L)
        ui.postDelayed({ if (!isFinishing) checkAutostartPermission() }, 1500L)

        val latestTvTheme = prefs.getString("tv_home_theme", "classic") ?: "classic"
        if (Themes.pick(this).id != skin.id || (!isPhoneLayout && latestTvTheme != currentTvTheme)) {
            recreate()
            return
        }

        buildDeco()
        tvName.text = prefs.getString("family", "Keluarga Anda") ?: "Keluarga Anda"
        refreshFamilyNote()
        applyBackground()
        val sig = lat.toString() + lon.toString() + tzName
        if (locSig != "" && sig != locSig) {
            for (i in 0..4) times[i] = -1
            prayerDay = ""
            prayerTryAt = 0L
            fetchWeather()
        }
        locSig = sig
        refreshClock()
        renderWeather()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
