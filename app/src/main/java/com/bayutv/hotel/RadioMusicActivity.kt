package com.bayutv.hotel

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

class RadioMusicActivity : AppCompatActivity() {

    data class AudioItem(
        val title: String,
        val subtitle: String,
        val uri: String,
        val isRadio: Boolean
    )

    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var voiceInput: VoiceInput

    private var brass = 0xFFB8975A.toInt()
    private var brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    private var musicService: MusicPlaybackService? = null
    private var serviceBound = false

    private var tabRadio = true
    private val radioList = ArrayList<AudioItem>()
    private val localList = ArrayList<AudioItem>()
    private val visibleList = ArrayList<AudioItem>()
    private var currentList: List<AudioItem> = emptyList()

    // Auto Dark Screen (OLED / TV Screensaver after 20s of inactivity while playing)
    private var lastUserActivityMs = 0L
    private val autoDarkDelayMs = 20_000L
    private var darkScreenActive = false
    private var adzanDialogShownFor: String? = null

    // UI Views
    private lateinit var tvTabRadio: TextView
    private lateinit var tvTabMusic: TextView
    private lateinit var tvAdzanStatus: TextView
    private lateinit var tvNowTitle: TextView
    private lateinit var tvNowSub: TextView
    private lateinit var tvSignalStatus: TextView
    private lateinit var tvTimeCur: TextView
    private lateinit var tvTimeDur: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: TextView
    private lateinit var btnListDrawer: TextView
    private lateinit var etSearch: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var drawerContainer: LinearLayout

    // Dark Screen Overlay Views
    private lateinit var darkOverlay: FrameLayout
    private lateinit var darkFloatingBox: LinearLayout
    private lateinit var tvDarkClock: TextView
    private lateinit var tvDarkTrack: TextView
    private lateinit var tvDarkSub: TextView
    private var userSeeking = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as MusicPlaybackService.LocalBinder).getService()
            musicService = svc
            serviceBound = true
            svc.onStateChanged = { syncUiFromService() }
            syncUiFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService?.onStateChanged = null
            musicService = null
            serviceBound = false
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voiceInput = VoiceInput(this)
        lastUserActivityMs = System.currentTimeMillis()

        val skin = Themes.pick(this)
        brass = skin.accent
        brassLight = skin.accentLight

        requestNotificationPermissionIfNeeded()
        buildUi()
        loadRadioStations()
        switchTab(toRadio = true)

        val svcIntent = Intent(this, MusicPlaybackService::class.java)
        startService(svcIntent)
        bindService(svcIntent, serviceConn, Context.BIND_AUTO_CREATE)

        ui.post(screenAndSeekUpdater)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    darkScreenActive -> exitDarkScreen()
                    drawerOverlay.visibility == View.VISIBLE -> drawerOverlay.visibility = View.GONE
                    else -> finish()
                }
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 303)
            }
        }
    }

    // Wake up immediately from Dark Screen on any touch or remote button
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastUserActivityMs = System.currentTimeMillis()
        if (darkScreenActive) {
            if (ev.action == MotionEvent.ACTION_UP) exitDarkScreen()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastUserActivityMs = System.currentTimeMillis()
        if (darkScreenActive) {
            if (event.action == KeyEvent.ACTION_UP) exitDarkScreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterDarkScreen() {
        if (darkScreenActive) return
        darkScreenActive = true
        updateDarkScreenText()
        darkOverlay.visibility = View.VISIBLE
    }

    private fun exitDarkScreen() {
        darkScreenActive = false
        lastUserActivityMs = System.currentTimeMillis()
        darkOverlay.visibility = View.GONE
    }

    private fun updateDarkScreenText() {
        val nowStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvDarkClock.text = nowStr
        val item = musicService?.currentItem
        tvDarkTrack.text = item?.title ?: "Pemutar Musik & Radio"
        tvDarkSub.text = "${item?.subtitle ?: "Siap"}  ·  ${musicService?.adzanCountdownText ?: ""}"

        // Subtle pixel-shift every update to prevent burn-in on OLED / TV
        val lp = darkFloatingBox.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.leftMargin = dp(Random.nextInt(-18, 19))
            lp.topMargin = dp(Random.nextInt(-18, 19))
            darkFloatingBox.layoutParams = lp
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0A0A0D.toInt()) }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        // Top Bar: Back + Title + Manual Dark Screen Button + Adzan Badge
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = tv("← KEMBALI", 12.5f, brassLight, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(11), dp(7), dp(11), dp(7))
            isFocusable = true
            background = pillBg(false)
            setOnClickListener { finish() }
            setOnFocusChangeListener { _, f -> background = pillBg(f) }
        }
        topBar.addView(btnBack)

        val titleTv = tv("Musik & Radio", 19f, ivory, Typeface.SERIF).apply {
            setPadding(dp(12), 0, dp(8), 0)
        }
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        val btnDark = tv("🌙 GELAP", 11.5f, ivory, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isFocusable = true
            background = pillBg(false)
            setOnClickListener { enterDarkScreen() }
            setOnFocusChangeListener { _, f -> background = pillBg(f) }
        }
        topBar.addView(btnDark, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

        tvAdzanStatus = tv("Adzan: ...", 11.5f, brassLight, Typeface.SANS_SERIF).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF16161C.toInt())
                setStroke(dp(1), 0x33E6C88F)
            }
        }
        topBar.addView(tvAdzanStatus)
        mainCol.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // Now Playing Card
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B1915.toInt(), 0xFF121216.toInt())
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x38E6C88F)
            }
        }

        val infoHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvNowTitle = tv("Pilih Radio atau Lagu", 17f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvNowSub = tv("Notifikasi aktif · Layar gelap otomatis 20 detik", 12f, mute).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(tvNowTitle)
        titleCol.addView(tvNowSub)
        infoHeader.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        tvSignalStatus = tv("● SIAP", 11f, 0xFF4CAF7D.toInt(), Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x1E4CAF7D)
            }
        }
        infoHeader.addView(tvSignalStatus)
        playerCard.addView(infoHeader)

        // Seekbar Row
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        tvTimeCur = tv("00:00", 11f, mute)
        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && musicService?.currentItem?.isRadio == false) {
                        val dur = musicService?.getDurationMs() ?: 0
                        if (dur > 0) tvTimeCur.text = formatMs((dur.toLong() * progress / 1000L).toInt())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    val svc = musicService ?: return
                    if (svc.currentItem?.isRadio == false && svc.getDurationMs() > 0) {
                        svc.seekTo((svc.getDurationMs().toLong() * (sb?.progress ?: 0) / 1000L).toInt())
                    }
                }
            })
        }
        tvTimeDur = tv("LIVE", 11f, brassLight)
        seekRow.addView(tvTimeCur)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        seekRow.addView(tvTimeDur)
        playerCard.addView(seekRow)

        // Transport Controls + Quick Playlist Drawer Button
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val btnPrev = controlButton("⏮", false) { musicService?.playPrev() }
        btnPlayPause = controlButton("▶ PUTAR", true) {
            val svc = musicService ?: return@controlButton
            if (svc.currentItem == null && visibleList.isNotEmpty()) {
                svc.playFromList(visibleList, 0)
            } else {
                svc.togglePlayPause()
            }
        }
        val btnNext = controlButton("⏭", false) { musicService?.playNext() }
        btnListDrawer = controlButton("☰ DAFTAR LAGU", false) { openQuickListDrawer() }

        ctrlRow.addView(btnPrev, LinearLayout.LayoutParams(dp(54), dp(42)).apply { rightMargin = dp(8) })
        ctrlRow.addView(btnPlayPause, LinearLayout.LayoutParams(dp(120), dp(42)).apply { rightMargin = dp(8) })
        ctrlRow.addView(btnNext, LinearLayout.LayoutParams(dp(54), dp(42)).apply { rightMargin = dp(10) })
        ctrlRow.addView(btnListDrawer, LinearLayout.LayoutParams(0, dp(42), 1f))
        playerCard.addView(ctrlRow)

        mainCol.addView(playerCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Tabs + Search Bar with Voice Input
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        tvTabRadio = tabButton("Radio Online") { switchTab(true) }
        tvTabMusic = tabButton("Musik HP / TV") { switchTab(false) }
        filterRow.addView(tvTabRadio, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(8) })
        filterRow.addView(tvTabMusic, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(10) })

        etSearch = EditText(this).apply {
            hint = "Cari lagu atau radio..."
            setHintTextColor(0x77FFFFFF)
            setTextColor(Color.WHITE)
            textSize = 13.5f
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF16161B.toInt())
                setStroke(dp(1), 0x2AFFFFFF)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        filterRow.addView(etSearch, LinearLayout.LayoutParams(0, dp(42), 1f))
        filterRow.addView(voiceInput.createMicButton(etSearch, "Sebutkan nama lagu atau radio...", brassLight))
        mainCol.addView(filterRow)

        // Scrollable List
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer, LinearLayout.LayoutParams(-1, -2))
        }
        mainCol.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(mainCol, FrameLayout.LayoutParams(-1, -1))

        // Quick Playlist Drawer Overlay
        drawerOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC050507.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { visibility = View.GONE }
        }
        val drawerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF141419.toInt())
                setStroke(dp(2), brassLight)
            }
        }
        val dHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dHead.addView(tv("Daftar Cepat (Lompat Lagu / Radio)", 16f, brassLight, Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(0, -2, 1f))
        val dClose = tv("✕ TUTUP", 12f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true
            background = pillBg(false)
            setOnClickListener { drawerOverlay.visibility = View.GONE }
        }
        dHead.addView(dClose)
        drawerBox.addView(dHead, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        drawerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dScroll = ScrollView(this).apply { addView(drawerContainer, LinearLayout.LayoutParams(-1, -2)) }
        drawerBox.addView(dScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val dLp = FrameLayout.LayoutParams(dp(440).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)), -1, Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
        drawerOverlay.addView(drawerBox, dLp)
        root.addView(drawerOverlay, FrameLayout.LayoutParams(-1, -1))

        // Pure Pitch-Black OLED/TV Dark Screen Overlay
        darkOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        darkFloatingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        tvDarkClock = tv("00:00", 54f, 0xCCEFE8DA.toInt(), Typeface.SERIF).apply { gravity = Gravity.CENTER }
        tvDarkTrack = tv("Pemutar Musik", 18f, 0xCCE6C88F.toInt(), Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        tvDarkSub = tv("", 13f, 0x88A79F90.toInt()).apply { gravity = Gravity.CENTER }
        val tvWakeHint = tv("Sentuh layar atau tekan tombol remote untuk menyalakan tampilan", 11.5f, 0x55FFFFFF).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        darkFloatingBox.addView(tvDarkClock)
        darkFloatingBox.addView(tvDarkTrack)
        darkFloatingBox.addView(tvDarkSub)
        darkFloatingBox.addView(tvWakeHint)
        darkOverlay.addView(darkFloatingBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(darkOverlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    private fun pillBg(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) 0xFF2B2316.toInt() else 0xFF18181E.toInt())
            setStroke(dp(if (focused) 2 else 1), if (focused) brassLight else 0x2EFFFFFF)
        }
    }

    private fun controlButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return tv(label, 12.5f, if (primary) Color.BLACK else Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (primary) brassLight else 0xFF202028.toInt())
                setStroke(dp(1), if (primary) Color.WHITE else 0x33FFFFFF)
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (primary) brassLight else if (f) 0xFF2F2618.toInt() else 0xFF202028.toInt())
                    setStroke(dp(if (f) 2 else 1), if (f) Color.WHITE else 0x33FFFFFF)
                }
            }
        }
    }

    private fun tabButton(label: String, onClick: () -> Unit): TextView {
        return tv(label, 12.5f, ivory, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun updateTabVisuals() {
        tvTabRadio.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (tabRadio) brass else 0xFF17171D.toInt())
        }
        tvTabRadio.setTextColor(if (tabRadio) Color.BLACK else ivory)

        tvTabMusic.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (!tabRadio) brass else 0xFF17171D.toInt())
        }
        tvTabMusic.setTextColor(if (!tabRadio) Color.BLACK else ivory)
        btnListDrawer.text = if (tabRadio) "☰ DAFTAR RADIO" else "☰ DAFTAR LAGU"
    }

    private fun switchTab(toRadio: Boolean) {
        tabRadio = toRadio
        updateTabVisuals()
        if (toRadio) {
            currentList = radioList
            applyFilter(etSearch.text?.toString().orEmpty())
        } else {
            ensureLocalAudioPermissionAndScan()
        }
    }

    private fun loadRadioStations() {
        radioList.clear()
        radioList.add(AudioItem("RRI Pro 3 Nasional", "Berita & Informasi Nasional", "https://stream-node1.rri.co.id/streaming/14/9014/rripro3.mp3", true))
        radioList.add(AudioItem("RRI Pro 1 Samarinda", "Kalimantan Timur · Regional", "https://stream-node1.rri.co.id/streaming/12/9012/rripro1samarinda.mp3", true))
        radioList.add(AudioItem("RRI Pro 2 Samarinda", "Musik & Kreativitas Muda", "https://stream-node1.rri.co.id/streaming/12/9012/rripro2samarinda.mp3", true))
        radioList.add(AudioItem("Prambors FM Jakarta", "Hits Pop Indonesia & Mancanegara", "https://22283.live.streamtheworld.com/PRAMBORS_FMAAC.aac", true))
        radioList.add(AudioItem("Delta FM", "Lagu Enak Sepanjang Hari", "https://22253.live.streamtheworld.com/DELTA_FMAAC.aac", true))
        radioList.add(AudioItem("Suara Surabaya FM", "Berita & Interaktif Jawa Timur", "https://c1.siar.us:8000/stream", true))
        radioList.add(AudioItem("Radio Rodja 756 AM", "Kajian Islam & Murottal Al-Qur'an", "https://radioislamindonesia.com/rodja.mp3", true))

        try {
            val raw = prefs.getString("custom_radios", null)
            if (!raw.isNullOrEmpty()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    radioList.add(AudioItem(o.getString("name"), o.optString("desc", "Radio Pilihan"), o.getString("url"), true))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureLocalAudioPermissionAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 202)
            currentList = emptyList()
            applyFilter("")
            return
        }
        scanLocalMusic()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanLocalMusic()
        }
    }

    private fun scanLocalMusic() {
        localList.clear()
        try {
            val projection = arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cur ->
                val idxTitle = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val idxArtist = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val idxData = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cur.moveToNext()) {
                    val title = cur.getString(idxTitle) ?: "Lagu Tanpa Judul"
                    val artist = cur.getString(idxArtist)?.takeIf { it != "<unknown>" } ?: "Musik Lokal"
                    val path = cur.getString(idxData) ?: continue
                    localList.add(AudioItem(title, artist, path, false))
                }
            }
        } catch (_: Exception) {
        }
        currentList = localList
        applyFilter(etSearch.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        visibleList.clear()
        for (item in currentList) {
            if (q.isEmpty() || item.title.lowercase().contains(q) || item.subtitle.lowercase().contains(q)) {
                visibleList.add(item)
            }
        }
        renderList(listContainer, visibleList, compact = false)
    }

    private fun openQuickListDrawer() {
        val fullSource = if (tabRadio) radioList else localList
        renderList(drawerContainer, fullSource, compact = true)
        drawerOverlay.visibility = View.VISIBLE
    }

    private fun renderList(target: LinearLayout, items: List<AudioItem>, compact: Boolean) {
        target.removeAllViews()
        if (items.isEmpty()) {
            val msg = if (tabRadio) "Tidak ada stasiun radio yang cocok." else "Belum ada file MP3/musik ditemukan di penyimpanan."
            target.addView(tv(msg, 13.5f, mute).apply { setPadding(dp(12), dp(20), dp(12), dp(20)) })
            return
        }

        val activeUri = musicService?.currentItem?.uri
        items.forEachIndexed { idx, item ->
            val active = (activeUri == item.uri)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(if (compact) 10 else 12), dp(14), dp(if (compact) 10 else 12))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (active) 0xFF282115.toInt() else 0xFF141419.toInt())
                    setStroke(dp(if (active) 2 else 1), if (active) brassLight else 0x1FFFFFFF)
                }
            }

            row.addView(tv("${idx + 1}.", 13f, if (active) brassLight else mute, Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(dp(32), -2))

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(item.title, if (compact) 13.5f else 14.5f, if (active) brassLight else Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(tv(item.subtitle, 11.5f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv(if (active) "▶ DIPUTAR" else if (item.isRadio) "LIVE" else "MP3", 10.5f, if (active) brassLight else mute, Typeface.DEFAULT_BOLD))

            row.setOnClickListener {
                musicService?.playFromList(items, idx)
                if (compact) drawerOverlay.visibility = View.GONE
            }
            row.setOnFocusChangeListener { _, focused ->
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (focused) 0xFF2B2316.toInt() else if (active) 0xFF282115.toInt() else 0xFF141419.toInt())
                    setStroke(dp(if (focused || active) 2 else 1), if (focused) Color.WHITE else if (active) brassLight else 0x1FFFFFFF)
                }
            }

            target.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun syncUiFromService() {
        val svc = musicService ?: return
        val item = svc.currentItem

        tvAdzanStatus.text = svc.adzanCountdownText
        tvSignalStatus.text = svc.signalText
        tvSignalStatus.setTextColor(svc.signalColor)
        tvSignalStatus.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor((svc.signalColor and 0x00FFFFFF) or 0x22000000)
        }

        if (item != null) {
            tvNowTitle.text = item.title
            tvNowSub.text = item.subtitle
            seekBar.isEnabled = !item.isRadio
            if (item.isRadio) {
                tvTimeCur.text = "LIVE"
                tvTimeDur.text = "RADIO"
                seekBar.progress = 1000
            } else {
                val dur = svc.getDurationMs()
                val pos = svc.getPositionMs()
                if (dur > 0) {
                    tvTimeDur.text = formatMs(dur)
                    if (!userSeeking) {
                        tvTimeCur.text = formatMs(pos)
                        seekBar.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }
                }
            }
        }

        btnPlayPause.text = when {
            svc.isPreparing -> "⏳ MEMUAT"
            svc.isPlaying -> "❚❚ JEDA"
            else -> "▶ PUTAR"
        }

        val prayer = svc.activeAdzanPrayer
        if (prayer != null && adzanDialogShownFor != prayer && !isFinishing) {
            adzanDialogShownFor = prayer
            AlertDialog.Builder(this)
                .setTitle("Waktu Sholat $prayer")
                .setMessage("Telah masuk waktu sholat $prayer. Pemutaran musik/radio dijeda sementara.")
                .setPositiveButton("Lanjutkan Musik") { _, _ ->
                    adzanDialogShownFor = null
                    svc.dismissAdzanBanner(resumeMusic = true)
                }
                .setNegativeButton("Tutup") { _, _ ->
                    adzanDialogShownFor = null
                    svc.dismissAdzanBanner(resumeMusic = false)
                }
                .show()
        }
    }

    private val screenAndSeekUpdater = object : Runnable {
        override fun run() {
            syncUiFromService()

            // Auto Dark Screen after 20 seconds of inactivity while playing
            val svc = musicService
            if (svc != null && svc.isPlaying && !darkScreenActive && drawerOverlay.visibility != View.VISIBLE) {
                if (System.currentTimeMillis() - lastUserActivityMs >= autoDarkDelayMs) {
                    enterDarkScreen()
                }
            }
            if (darkScreenActive) {
                updateDarkScreenText()
            }
            ui.postDelayed(this, 1000L)
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = max(0, ms / 1000)
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (serviceBound) {
            musicService?.onStateChanged = null
            unbindService(serviceConn)
            serviceBound = false
        }
        super.onDestroy()
    }
}
