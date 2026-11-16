package com.bayutv.hotel

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ScrollView
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

// TV Live hybrid: channel internet (ExoPlayer) dan channel antena (TvView) dalam satu daftar.
class MainActivity : AppCompatActivity() {

    private class Cat(val title: String, val keys: List<String>, val action: String? = null)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var tvView: TvView? = null

    private var all: List<Channel> = emptyList()
    private var byKey: Map<String, Channel> = emptyMap()
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var scan: MutableMap<String, String> = HashMap()
    private var fails: MutableMap<String, LongArray> = HashMap()
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
    private lateinit var colLeft: LinearLayout
    private lateinit var colRight: LinearLayout
    private lateinit var catHead: TextView
    private lateinit var chanHead: TextView
    private lateinit var notice: Notice

    // Komponen Overlay Adzan, Countdown 5 Detik & Dzikir/Doa Ramadan
    private lateinit var countdownBanner: TextView
    private lateinit var adzanOverlay: FrameLayout
    private lateinit var adzanTitleView: TextView
    private lateinit var adzanSubtitleView: TextView
    private lateinit var ramadanBox: LinearLayout
    private lateinit var btnDismissAdzan: TextView

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
            if (k != null) play(k, zapKeys, true) else showBanner("Channel " + n + " tidak ada", 1500L)
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
        buildAdzanAndRamadanOverlays()

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

    // ---------- Overlay Countdown 5 Detik Adzan, Doa Setelah Adzan & Doa/Dzikir Ramadan ----------

    private fun buildAdzanAndRamadanOverlays() {
        countdownBanner = tv("", 18f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE6141210.toInt())
                setStroke(dp(2), brassLight)
            }
            visibility = View.GONE
        }
        val cdlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        cdlp.setMargins(dp(24), dp(28), dp(32), 0)
        root.addView(countdownBanner, cdlp)

        adzanOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC0A0908.toInt())
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF5161411.toInt())
                setStroke(dp(2), brass)
            }
        }

        adzanTitleView = tv("WAKTU ADZAN", 22f, brassLight, Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        adzanSubtitleView = tv("Suara TV dikecilkan sejenak untuk menghormati adzan", 13.5f, mute, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(adzanTitleView)
        card.addView(adzanSubtitleView)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val contentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Blok Khusus Ramadan (Doa Berbuka Puasa saat Magrib)
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
            tv("SELAMAT BERBUKA PUASA · DOA BERBUKA", 13f, brassLight, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                letterSpacing = 0.08f
            }
        )
        ramadanBox.addView(
            tv(
                "ذَهَبَ الظَّمَأُ وَابْتَلَّتِ الْعُرُوقُ، وَثَبَتَ الْأَجْرُ إِنْ شَاءَ اللَّهُ",
                22f, ivory, Typeface.SERIF
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

        // Blok Menjawab Adzan & Doa Setelah Adzan
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
                21f, ivory, Typeface.SERIF
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

        btnDismissAdzan = tv("Kembali Menonton (Tekan OK)", 14.5f, 0xFF100E0C.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
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

        val cardLp = FrameLayout.LayoutParams(dp(760), dp(470), Gravity.CENTER)
        adzanOverlay.addView(card, cardLp)
        root.addView(adzanOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun isRamadanNow(): Boolean {
        if (Themes.pick(this).id == "ramadan") return true
        return try {
            Themes.hijri()[0] == 8
        } catch (_: Exception) {
            false
        }
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
        left.setPadding(dp(6), dp(8), dp(6), dp(6))
        colLeft = left
        val head = tv("KATEGORI", 14f, brass, Typeface.SANS_SERIF)
        head.letterSpacing = 0.15f
        head.setPadding(dp(10), 0, 0, dp(8))
        catHead = head
        left.addView(head)
        catList = ListView(this)
        catList.divider = null
        catList.selector = selectorBg()
        catList.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> styleFocus() }
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
                v.text = if (c.action != null) c.title else c.title + "  (" + c.keys.size + ")"
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
            if (cats.getOrNull(pos)?.action == "sleep") {
                cycleSleep()
                catList.setSelection(pos)
            } else {
                selectCat(pos, false)
                chanList.requestFocus()
            }
        }
        left.addView(catList, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(left, LinearLayout.LayoutParams(dp(290), -1))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.setPadding(dp(12), dp(8), dp(6), dp(6))
        colRight = right
        chanHead = tv("CHANNEL", 14f, brass, Typeface.SANS_SERIF)
        chanHead.letterSpacing = 0.15f
        chanHead.maxLines = 1
        chanHead.ellipsize = TextUtils.TruncateAt.END
        chanHead.setPadding(dp(8), 0, 0, dp(4))
        right.addView(chanHead)
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
        chanList.selector = selectorBg()
        chanList.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> styleFocus() }
        search.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> styleFocus() }
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
                    val mid = LinearLayout(this@MainActivity)
                    mid.orientation = LinearLayout.VERTICAL
                    val name = tv("", 18f, ivory, Typeface.SANS_SERIF)
                    name.maxLines = 1
                    name.ellipsize = TextUtils.TruncateAt.END
                    mid.addView(name)
                    val nowT = tv("", 12f, mute, Typeface.SANS_SERIF)
                    nowT.maxLines = 1
                    nowT.ellipsize = TextUtils.TruncateAt.END
                    mid.addView(nowT)
                    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
                    val star = tv("", 16f, brass, Typeface.SANS_SERIF)
                    row.addView(star, LinearLayout.LayoutParams(-2, -2))
                }
                val ch = shown[position]
                (row.getChildAt(0) as TextView).text = (numberOf[ch.key] ?: 0).toString()
                Images.loadUrl(ch.logo, row.getChildAt(1) as ImageView, 128, true)
                val mid = row.getChildAt(2) as LinearLayout
                val nm = mid.getChildAt(0) as TextView
                val nowT = mid.getChildAt(1) as TextView
                val (cur, _) = Epg.nowNext(ch.name)
                nowT.text = cur?.title ?: ""
                nowT.visibility = if (cur != null) View.VISIBLE else View.GONE
                val srcs = groups[ch.key] ?: emptyList()
                val hasInet = srcs.any { !it.isAntenna && !it.isApp }
                val extra = when {
                    ch.isAntenna && hasInet -> "  · antena+internet"
                    ch.isAntenna -> "  · antena " + ch.number
                    Lock.channelLocked(this@MainActivity, ch) -> "  · terkunci"
                    ch.isApp -> "  · ↗ " + (ch.group ?: "")
                    ch.status == "geo" -> "  · belum dicek"
                    else -> ""
                }
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

    private fun selectorBg(): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(8).toFloat()
        g.setColor(0x66B8975A)
        g.setStroke(dp(2), brassLight)
        return g
    }

    private fun styleFocus() {
        if (!::colLeft.isInitialized) return
        val inCat = catList.hasFocus()
        val active = GradientDrawable()
        active.cornerRadius = dp(12).toFloat()
        active.setColor(0x22FFFFFF)
        active.setStroke(dp(1), 0x66E6C88F)
        colLeft.background = if (inCat) active else null
        colRight.background = if (inCat) null else active
        colLeft.alpha = if (inCat) 1f else 0.5f
        colRight.alpha = if (inCat) 0.5f else 1f
        catHead.text = if (inCat) "\u25B6 KATEGORI" else "KATEGORI"
        catHead.setTextColor(if (inCat) brassLight else mute)
        val t = cats.getOrNull(catIdx)?.title ?: ""
        chanHead.text = (if (inCat) "CHANNEL" else "\u25B6 CHANNEL") + (if (t.isNotEmpty()) "  \u00B7  " + t else "")
        chanHead.setTextColor(if (inCat) mute else brassLight)
    }

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
        Epg.load(this)
        ui.post { onLoaded(list, ant, false) }
        val pt = Prayer.fetch(this)
        ui.post {
            prayerTimes = pt
            prayerDay = Prayer.todayStr()
        }
        Epg.refresh(this)
        Epg.load(this)
        ui.post { if (!isFinishing) chanAdapter.notifyDataSetChanged() }
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
        else vis.firstOrNull {
            val c = byKey[it]
            it != trying && c != null && !c.isAntenna && !c.isApp && it != Antenna.OPEN_KEY && !Lock.channelLocked(this, c)
        }
            ?: vis.firstOrNull()
        if (start != null) play(start, vis, true)
        if (trying != null) {
            val bad = byKey[trying]
            if (bad != null) notice.show("Dilewati", "Channel \"" + Playlist.display(bad.name) + "\" tadi membuat aplikasi tertutup, jadi tidak diputar otomatis.", 7000L)
        }
    }

    private fun appChannel(t: AppLinks.Target): Channel =
        Channel(t.name, "app:" + t.name, null, t.provider, null, null, "Indonesia", "Nasional", "app")

    private fun setData(list: List<Channel>) {
        scan = Playlist.loadScan(this)
        if (!prefs.getBoolean("fails_v1", false)) {
            val cleaned = HashMap(scan.filterValues { it.isNotEmpty() })
            Playlist.saveScan(this, cleaned)
            scan = cleaned
            prefs.edit().putBoolean("fails_v1", true).apply()
        }
        fails = Playlist.loadFails(this)
        val g = Playlist.group(list)
        val inetByMk = HashMap<String, String>()
        for (k in g.keys) {
            val mk = AppLinks.mergeKey(k)
            if (!inetByMk.containsKey(mk)) inetByMk[mk] = k
        }
        val gm = HashMap<String, List<Channel>>()
        val consumed = HashSet<String>()
        val appUsed = HashSet<String>()
        val antReps = ArrayList<Channel>()
        for (a in antenna) {
            antReps.add(a)
            if (a.key == Antenna.OPEN_KEY) {
                gm[a.key] = listOf(a)
                continue
            }
            val c = ArrayList<Channel>()
            c.add(a)
            val ik = inetByMk[AppLinks.mergeKey(a.name)]
            val members = if (ik != null) g[ik] else null
            if (ik != null && members != null) {
                c.addAll(members)
                consumed.add(ik)
            }
            val t = AppLinks.targetFor(a.name)
            if (t != null && !appUsed.contains(t.name)) {
                c.add(appChannel(t))
                appUsed.add(t.name)
            }
            gm[a.key] = c
        }
        val inetReps = ArrayList<Channel>()
        for ((k, members) in g) {
            if (consumed.contains(k)) continue
            val want = scan[k]
            inetReps.add(if (!want.isNullOrEmpty()) (members.firstOrNull { it.url == want } ?: members[0]) else members[0])
            val c = ArrayList<Channel>(members)
            val t = AppLinks.targetFor(k)
            if (t != null && !appUsed.contains(t.name)) {
                c.add(appChannel(t))
                appUsed.add(t.name)
            }
            gm[k] = c
        }
        val appReps = ArrayList<Channel>()
        for (t in AppLinks.TARGETS) {
            if (appUsed.contains(t.name)) continue
            val c = appChannel(t)
            appReps.add(c)
            gm[c.key] = listOf(c)
        }
        groups = gm
        all = antReps + appReps + inetReps
        byKey = all.associateBy { it.key }
        hidden.clear()
        for (k in byKey.keys) {
            if (groups[k]?.any { it.isAntenna || it.isApp } == true) continue
            if (scan[k] == "" || Playlist.hiddenByFails(fails[k])) hidden.add(k)
        }
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
        out.add(Cat(sleepTitle(), emptyList(), "sleep"))
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
        val arr = JSONArray()
        for (c in vis) {
            val num = nums[c.key] ?: continue
            val o = JSONObject()
            o.put("k", c.key)
            o.put("n", num)
            o.put("name", Playlist.display(c.name))
            o.put("cat", c.region + " / " + c.category)
            arr.put(o)
        }
        remoteList = arr.toString()
    }

    private fun selectCat(i: Int, keepSel: Boolean) {
        if (cats.isEmpty()) return
        catIdx = i.coerceIn(0, cats.size - 1)
        catAdapter.notifyDataSetChanged()
        applyFilter()
        if (!keepSel) chanList.setSelection(0)
        styleFocus()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val keys = if (q.isNotEmpty()) visibleKeys() else (cats.getOrNull(catIdx)?.keys ?: emptyList())
        shown = keys.mapNotNull { byKey[it] }.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        chanAdapter.notifyDataSetChanged()
    }

    // ---------- Pemutar & Kualitas Video ----------

    private fun applyVideoQuality(maxHeight: Int) {
        val ts = trackSelector ?: return
        val b = ts.buildUponParameters()
        if (maxHeight > 0) {
            b.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        } else {
            b.clearVideoSizeConstraints()
        }
        ts.setParameters(b.build())
    }

    private fun setupPlayer() {
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12000, 30000, 1500, 3000)
            .build()
        val ts = DefaultTrackSelector(this)
        trackSelector = ts
        applyVideoQuality(prefs.getInt("max_height", 0))
        val p = ExoPlayer.Builder(this)
            .setTrackSelector(ts)
            .setLoadControl(lc)
            .build()
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
        var failsChanged = false
        if (pendingDead.isNotEmpty()) {
            for (d in pendingDead) Playlist.recordFail(fails, d)
            pendingDead.clear()
            failsChanged = true
        }
        if (fails.remove(k) != null) failsChanged = true
        if (failsChanged) {
            val fcopy = HashMap(fails)
            Thread { Playlist.saveFails(this, fcopy) }.start()
        }
        if (scan[k] != ch.url) {
            scan[k] = ch.url
            val copy = HashMap(scan)
            Thread { Playlist.saveScan(this, copy) }.start()
        }
    }

    private fun onChannelFailed(why: String) {
        prefs.edit().remove("trying").apply()
        status.text = ""
        val k = curKey ?: return
        val ch = byKey[k] ?: return
        if (groups[k]?.any { it.isAntenna || it.isApp } == true) {
            status.text = "Semua sumber " + Playlist.display(ch.name) + " gagal: " + why
            return
        }
        failStreak++
        if (failStreak >= 3) {
            pendingDead.clear()
            notice.show("Sinyal hilang", "Beberapa channel gagal berturut-turut. Periksa internet TV.\n\nAtas/bawah: ganti channel  ·  OK: daftar channel")
            return
        }
        hidden.add(k)
        pendingDead.add(k)
        rebuild()
        if (panel.visibility == View.VISIBLE) applyFilter()
        notice.show("Siaran tidak tersedia", Playlist.display(ch.name) + ": " + why + ".\nDisembunyikan sementara dan akan dicoba lagi otomatis.", 3500L)
        ui.postDelayed({ if (panel.visibility != View.VISIBLE && curKey == k) zap(lastDir) }, 2600L)
    }

    private val antTimeout = Runnable { nextOrStatus("Sinyal antena lemah.") }

    private fun nextOrStatus(msg: String) {
        ui.removeCallbacks(antTimeout)
        if (candPos + 1 < cands.size) {
            candPos++
            status.text = msg + " Mencoba sumber lain..."
            startCandidate()
        } else {
            status.text = msg
        }
    }

    private fun ensureTvView(): TvView? {
        tvView?.let { return it }
        return try {
            val t = TvView(this)
            t.isFocusable = false
            root.addView(t, 1, FrameLayout.LayoutParams(-1, -1))
            t.setCallback(object : TvView.TvInputCallback() {
                override fun onConnectionFailed(inputId: String?) {
                    nextOrStatus("Gagal tersambung ke tuner antena.")
                }

                override fun onVideoAvailable(inputId: String?) {
                    ui.removeCallbacks(antTimeout)
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

    private fun stopTv() {
        ui.removeCallbacks(antTimeout)
        tvView?.let {
            try {
                it.reset()
            } catch (e: Exception) {
            }
            it.visibility = View.GONE
        }
    }

    private var viaZap = false
    private var pendingApp: String? = null
    private var returnFromApp = false

    private fun play(key: String, list: List<String>, zapped: Boolean = false) {
        val ch = byKey[key] ?: return
        if (key == Antenna.OPEN_KEY) {
            if (!Antenna.openSaved(this)) {
                notice.show("TV Antena", "Aplikasi antena belum bisa dibuka. Pilih aplikasinya di menu Pengaturan." +
                    (if (antennaInfo.isNotEmpty()) "\n\n" + antennaInfo else ""), 7000L)
            }
            return
        }
        if (Lock.channelLocked(this, ch)) {
            Lock.ask(this, Playlist.display(ch.name) + " terkunci", { play(key, list, zapped) })
            return
        }
        curKey = key
        pendingApp = null
        viaZap = zapped
        if (list.isNotEmpty()) zapKeys = list
        val num = numberOf[key]
        val epgLine = Epg.line(ch.name)
        showBanner((if (num != null) num.toString() + "   " else "") + Playlist.display(ch.name) +
            (if (epgLine.isNotEmpty()) "\n" + epgLine else ""), if (epgLine.isNotEmpty()) 5000L else 3000L)
        remoteCur = (if (num != null) num.toString() + "  " else "") + Playlist.display(ch.name)
        remoteNow = epgLine
        notice.hide()
        chanAdapter.notifyDataSetChanged()
        if (!ch.isApp) prefs.edit().putString("trying", key).commit()

        val members = groups[key] ?: listOf(ch)
        val inet = members.filter { !it.isAntenna && !it.isApp }
        val pref = scan[key]
        val first = if (!pref.isNullOrEmpty()) inet.firstOrNull { it.url == pref } else null
        val maxInet = if (members.any { it.isApp }) 2 else 5
        val inetOrdered = (if (first != null) listOf(first) + inet.filter { it !== first } else inet).take(maxInet)
        cands = members.filter { it.isAntenna } + inetOrdered + members.filter { it.isApp }
        candPos = 0
        startCandidate()
    }

    private fun startCandidate() {
        ui.removeCallbacks(antTimeout)
        val c = cands.getOrNull(candPos) ?: return
        when {
            c.isAntenna -> playAntenna(c)
            c.isApp -> openApp(c)
            else -> playInternet(c)
        }
    }

    private fun playAntenna(c: Channel) {
        player?.stop()
        playerView.visibility = View.INVISIBLE
        val t = ensureTvView()
        val input = c.tunerInput
        if (t == null || input == null) {
            nextOrStatus("Tuner tidak bisa dipakai di dalam aplikasi.")
            return
        }
        t.visibility = View.VISIBLE
        status.text = "Menyetel antena..."
        try {
            t.tune(input, TvContract.buildChannelUri(c.tunerId))
        } catch (e: Exception) {
            nextOrStatus("Gagal menyetel antena.")
            return
        }
        if (candPos + 1 < cands.size) ui.postDelayed(antTimeout, 10000L)
    }

    private fun openApp(c: Channel) {
        player?.stop()
        stopTv()
        status.text = ""
        val t = AppLinks.targetFor(c.name) ?: return
        if (viaZap) {
            pendingApp = curKey
            notice.show(Playlist.display(c.name), "Tekan OK untuk menonton di " + t.provider + ".\nAtas/bawah: lanjut ganti channel", 6000L)
            return
        }
        launchApp(t)
    }

    private fun launchApp(t: AppLinks.Target) {
        pendingApp = null
        prefs.edit().remove("trying").apply()
        returnFromApp = true
        when (AppLinks.open(this, t)) {
            0 -> {
                returnFromApp = false
                notice.show(t.name, "Aplikasi " + t.provider + " belum terpasang di TV. Pasang dari Play Store, lalu coba lagi.", 6000L)
            }
            1 -> android.widget.Toast.makeText(this, "Pilih " + t.name + " di menu Live " + t.provider, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun playInternet(c: Channel) {
        stopTv()
        playerView.visibility = View.VISIBLE
        status.text = "Memuat..."
        val p = player ?: return
        try {
            val h = Playlist.headers(c)
            val dsf = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setUserAgent(h["User-Agent"])
            val extra = h.filterKeys { it != "User-Agent" }
            if (extra.isNotEmpty()) dsf.setDefaultRequestProperties(extra)
            p.setMediaSource(DefaultMediaSourceFactory(dsf).createMediaSource(MediaItem.fromUri(c.url)))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            if (candPos + 1 < cands.size) nextOrStatus("Sumber rusak.") else onChannelFailed(e.javaClass.simpleName)
        }
    }

    private fun zap(d: Int) {
        lastDir = d
        var list = zapKeys.filter { k -> k !in hidden && k != Antenna.OPEN_KEY && byKey[k]?.let { Lock.channelLocked(this, it) } != true }
        if (list.isEmpty()) list = visibleKeys().filter { it != Antenna.OPEN_KEY }
        if (list.isEmpty()) return
        val i = list.indexOf(curKey)
        val next = if (i < 0) 0 else ((i + d) % list.size + list.size) % list.size
        play(list[next], zapKeys, true)
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
        var ci = cats.indexOfFirst { it.action == null && it.keys == zapKeys }
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
        if (adzanOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK) {
                endAdzan()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (ducked) {
                    endAdzan()
                    return true
                }
                if (sleepWarned) {
                    setSleep(0)
                    notice.hide()
                    return true
                }
                val pa = pendingApp
                val t = if (pa != null && pa == curKey) AppLinks.targetFor(byKey[pa]?.name ?: "") else null
                if (t != null) {
                    notice.hide()
                    launchApp(t)
                } else {
                    showPanel()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                val ch = curKey?.let { byKey[it] }
                if (ch != null) {
                    val ep = Epg.line(ch.name)
                    val pray = nextPrayerText()
                    val ip = Net.localIp()
                    val rs = remoteServer
                    val qVal = prefs.getInt("max_height", 0)
                    val qTag = if (qVal > 0) " · ${qVal}p" else ""
                    showBanner(
                        (numberOf[ch.key]?.toString() ?: "") + "   " + Playlist.display(ch.name) +
                            "\n" + ch.region + " · " + ch.category + qTag +
                            (if (ep.isNotEmpty()) "\n" + ep else "") +
                            (if (pray.isNotEmpty()) "\n" + pray else "") +
                            (if (sleepAt > 0L) "\nTimer tidur: " + ((sleepAt - System.currentTimeMillis()) / 60000L + 1) + " menit lagi" else "") +
                            (if (ip != null && rs != null) "\nRemote HP: http://" + ip + ":" + rs.port + "  ·  PIN " + remotePin() else ""),
                        8000L
                    )
                }
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

    // ================= Waktu sholat, batas tonton, timer tidur, remote HP =================

    private var prayerTimes: IntArray? = null
    private var prayerDay = ""
    private var ducked = false
    private var adzanPlayer: ExoPlayer? = null
    private var adzanCountdownRunnable: Runnable? = null
    private var sleepAt = 0L
    private var sleepWarned = false
    private var limitAsking = false
    private var remoteServer: SettingsServer? = null

    @Volatile
    private var remoteList = "[]"

    @Volatile
    private var remoteCur = ""

    @Volatile
    private var remoteNow = ""

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
            }
            ui.postDelayed(this, 30000L)
        }
    }

    private fun isWatching(): Boolean {
        val c = cands.getOrNull(candPos) ?: return false
        if (c.isApp) return false
        if (c.isAntenna) return tvView?.visibility == View.VISIBLE
        return player?.isPlaying == true
    }

    private fun prayerOffset(): Int =
        (prefs.getString("adzan_offset", "0") ?: "0").toIntOrNull()?.coerceIn(-15, 15) ?: 0

    private fun nextPrayerText(): String {
        val t = prayerTimes ?: return ""
        if (t.size < 5) return ""
        val off = prayerOffset()
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        for (i in 0..4) {
            val pt = t[i] + off
            if (pt > now) {
                val diff = pt - now
                val jam = diff / 60
                val mnt = diff % 60
                val sisa = if (jam > 0) "${jam}j ${mnt}m lagi" else "${mnt} menit lagi"
                return "Berikutnya: ${Prayer.NAMES[i]} ${Prayer.fmt(pt)} ($sisa)"
            }
        }
        return "Berikutnya: ${Prayer.NAMES[0]} ${Prayer.fmt(t[0] + off)} (besok)"
    }

    private fun onTick() {
        val today = Prayer.todayStr()
        if (prayerDay != today) {
            prayerDay = today
            Thread {
                val pt = Prayer.fetch(this)
                ui.post { prayerTimes = pt }
            }.start()
        }
        checkPrayer()
        if (isWatching() && Lock.addWatch(this, 30) && !limitAsking) onLimit()
        if (sleepAt > 0L) {
            val left = sleepAt - System.currentTimeMillis()
            if (left <= 0L) {
                doSleep()
            } else if (left <= 70000L && !sleepWarned) {
                sleepWarned = true
                notice.show("Timer tidur", "TV Live berhenti dalam 1 menit. Tekan OK untuk membatalkan.", 60000L)
            }
            if (panel.visibility == View.VISIBLE) rebuildKeepCat()
        }
        if (panel.visibility == View.VISIBLE && Epg.has()) chanAdapter.notifyDataSetChanged()
    }

    private fun checkPrayer() {
        if (prefs.getString("adzan", "Ya") == "Tidak") return
        val t = prayerTimes ?: return
        if (t.size < 5) return
        val off = prayerOffset()
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val day = Prayer.todayStr()

        // Pengingat Dzikir Menjelang Berbuka Puasa (15 menit sebelum Magrib saat Ramadan)
        if (isRamadanNow()) {
            val magrib = t[3] + off
            if (now >= magrib - 15 && now < magrib - 1 && prefs.getString("ramadan_dzikir_done", "") != day) {
                prefs.edit().putString("ramadan_dzikir_done", day).apply()
                if (isWatching()) {
                    val sisa = magrib - now
                    notice.show(
                        "Menjelang Berbuka Puasa ($sisa menit lagi) · Dzikir Dianjurkan",
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
        if (!isWatching()) return
        adzanCountdownRunnable?.let { ui.removeCallbacks(it) }
        var secLeft = 5
        val r = object : Runnable {
            override fun run() {
                if (isFinishing || !isWatching()) {
                    countdownBanner.visibility = View.GONE
                    return
                }
                if (secLeft > 0) {
                    countdownBanner.text = "⏳ Adzan ${Prayer.NAMES[i]} dalam $secLeft detik..."
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
        if (!isWatching()) return
        ducked = true
        player?.volume = 0.12f
        try {
            tvView?.setStreamVolume(0.12f)
        } catch (e: Exception) {
        }
        val mins = (prefs.getString("adzan_min", "4") ?: "4").toIntOrNull()?.coerceIn(1, 30) ?: 4

        val isMagribRamadan = (i == 3 && isRamadanNow())
        adzanTitleView.text = if (isMagribRamadan) {
            "WAKTU MAGRIB · SELAMAT BERBUKA PUASA (${Prayer.fmt(minute)})"
        } else {
            "WAKTU ADZAN ${Prayer.NAMES[i].uppercase()} · ${Prayer.fmt(minute)}"
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
            } catch (e: Exception) {
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
        if (!ducked) return
        ducked = false
        player?.volume = 1f
        try {
            tvView?.setStreamVolume(1f)
        } catch (e: Exception) {
        }
        notice.hide()
    }

    private fun onLimit() {
        limitAsking = true
        player?.pause()
        stopTv()
        val lim = Lock.limitMinutes(this)
        Lock.ask(this, "Waktu menonton hari ini habis (" + lim + " menit)", {
            limitAsking = false
            Lock.allowRestOfDay(this)
            startCandidate()
        }, {
            limitAsking = false
            finish()
        })
    }

    private fun sleepTitle(): String {
        if (sleepAt <= 0L) return "Timer tidur: mati"
        val m = (sleepAt - System.currentTimeMillis()) / 60000L + 1
        return "Timer tidur: " + m + " menit lagi"
    }

    private fun setSleep(minutes: Int) {
        sleepWarned = false
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60000L else 0L
        if (::catAdapter.isInitialized) rebuildKeepCat()
        showBanner(if (minutes > 0) "Timer tidur: " + minutes + " menit" else "Timer tidur dimatikan", 2500L)
    }

    private fun cycleSleep() {
        val left = if (sleepAt > 0L) ((sleepAt - System.currentTimeMillis()) / 60000L + 1).toInt() else 0
        val next = when {
            sleepAt <= 0L -> 30
            left <= 30 -> 60
            left <= 60 -> 90
            left <= 90 -> 120
            else -> 0
        }
        setSleep(next)
    }

    private fun rebuildKeepCat() {
        val keep = cats.getOrNull(catIdx)?.title
        val sel = catList.selectedItemPosition
        rebuild()
        val i = if (keep != null && !keep.startsWith("Timer tidur")) cats.indexOfFirst { it.title == keep } else 0
        if (i >= 0) catIdx = i
        catAdapter.notifyDataSetChanged()
        if (sel >= 0 && catList.hasFocus()) catList.setSelection(sel)
    }

    private fun doSleep() {
        sleepAt = 0L
        sleepWarned = false
        player?.stop()
        stopTv()
        if (ducked) endAdzan()
        notice.show("Selamat beristirahat", "TV Live dihentikan oleh timer tidur.", 4000L)
        ui.postDelayed({
            try {
                val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
                home.addCategory(android.content.Intent.CATEGORY_HOME)
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(home)
            } catch (e: Exception) {
            }
            finish()
        }, 3000L)
    }

    private fun remotePin(): String {
        var p = prefs.getString("remote_pin", null)
        if (p == null || p.length != 4) {
            p = (java.security.SecureRandom().nextInt(9000) + 1000).toString()
            prefs.edit().putString("remote_pin", p).apply()
        }
        return p
    }

    private fun startRemote() {
        if (remoteServer != null) return
        val r = object : Remote {
            override fun channelsJson(): String =
                "{\"cur\":" + JSONObject.quote(remoteCur) +
                    ",\"now\":" + JSONObject.quote(remoteNow) +
                    ",\"q\":" + prefs.getInt("max_height", 0) +
                    ",\"list\":" + remoteList + "}"

            override fun command(cmd: String, arg: String) {
                ui.post {
                    if (isFinishing) return@post
                    when (cmd) {
                        "play" -> if (byKey.containsKey(arg)) {
                            hidePanel()
                            play(arg, zapKeys)
                        }
                        "zap" -> zap(if (arg == "-1") -1 else 1)
                        "panel" -> if (panel.visibility == View.VISIBLE) hidePanel() else showPanel()
                        "ok" -> onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                        "sleep" -> setSleep(arg.toIntOrNull()?.coerceIn(0, 240) ?: 0)
                        "quality" -> {
                            val h = when (arg) {
                                "720" -> 720
                                "480" -> 480
                                else -> 0
                            }
                            prefs.edit().putInt("max_height", h).apply()
                            applyVideoQuality(h)
                            showBanner(
                                "Kualitas video: " + (if (h > 0) "${h}p (Hemat Kuota & RAM)" else "Otomatis (HD)"),
                                2500L
                            )
                        }
                        else -> {}
                    }
                    Unit
                }
            }
        }
        try {
            val s = SettingsServer(this, remotePin(), r) {}
            if (s.start()) remoteServer = s
        } catch (e: Exception) {
        }
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
        startRemote()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 30000L)
        val c = cands.getOrNull(candPos)
        if (all.isNotEmpty() && c != null && !c.isAntenna && !c.isApp && !returnFromApp) player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        remoteServer?.stop()
        remoteServer = null
        if (ducked) endAdzan()
        player?.pause()
        try {
            tvView?.reset()
        } catch (e: Exception) {
        }
    }

    override fun onRestart() {
        super.onRestart()
        if (returnFromApp) {
            returnFromApp = false
            ui.post { showPanel() }
            return
        }
        val c = cands.getOrNull(candPos)
        if (c != null && c.isAntenna) startCandidate()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        remoteServer?.stop()
        adzanPlayer?.release()
        adzanPlayer = null
        player?.release()
        player = null
        try {
            tvView?.reset()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
