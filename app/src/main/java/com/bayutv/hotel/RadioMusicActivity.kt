package com.bayutv.hotel

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    // MediaPlayer & Audio Focus
    private var player: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var isPlaying = false
    private var isPreparing = false
    private var pausedByCallOrFocus = false
    private var pausedByAdzan = false

    // Playlist & Mode
    private var tabRadio = true
    private val radioList = ArrayList<AudioItem>()
    private val localList = ArrayList<AudioItem>()
    private val visibleList = ArrayList<AudioItem>()
    private var currentList: List<AudioItem> = emptyList()
    private var currentIndex = -1
    private var currentItem: AudioItem? = null

    // Radio Signal Watchdog & Reconnect
    private var retryCount = 0
    private val maxRetries = 10
    private var lastPlaybackPos = -1
    private var stallTicks = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Phone Call State Listener
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null

    // Adzan Schedule Monitor
    private val prayerTimes = IntArray(5) { -1 }
    private val prayerNames = arrayOf("Subuh", "Zuhur", "Asar", "Magrib", "Isya")
    private var lastTriggeredAdzanKey = ""
    private var adzanAlertDialog: AlertDialog? = null

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
    private var userSeeking = false

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface = Typeface.SANS_SERIF): TextView {
        return TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@RadioMusicActivity, sp))
            setTextColor(color)
            typeface = face
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying) {
                pausePlayback(byExternalInterruption = false)
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying || isPreparing) {
                    pausedByCallOrFocus = true
                    pausePlayback(byExternalInterruption = true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1.0f, 1.0f)
                if (pausedByCallOrFocus && !pausedByAdzan) {
                    pausedByCallOrFocus = false
                    resumePlayback()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voiceInput = VoiceInput(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val skin = Themes.pick(this)
        brass = skin.accent
        brassLight = skin.accentLight

        buildUi()
        loadRadioStations()
        loadPrayerSchedule()
        registerCallAndNetworkMonitors()
        switchTab(toRadio = true)

        ui.post(monitorRunnable)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerOverlay.visibility == View.VISIBLE) {
                    drawerOverlay.visibility = View.GONE
                } else {
                    finish()
                }
            }
        })
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0A0D.toInt())
        }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        // Top Bar: Back + Title + Adzan Status Badge
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = tv("← KEMBALI", 13f, brassLight, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            background = pillBg(false)
            setOnClickListener { finish() }
            setOnFocusChangeListener { _, f -> background = pillBg(f) }
        }
        topBar.addView(btnBack)

        val titleTv = tv("Musik & Radio", 20f, ivory, Typeface.SERIF).apply {
            setPadding(dp(14), 0, dp(8), 0)
        }
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        tvAdzanStatus = tv("Adzan: Memuat...", 11.5f, brassLight, Typeface.SANS_SERIF).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
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
        tvNowSub = tv("Siap diputar · Auto-Jeda saat Telepon & Adzan", 12.5f, mute).apply {
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

        // Seekbar Row (for Local Music)
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
                    if (fromUser && player != null && currentItem?.isRadio == false) {
                        val dur = player?.duration ?: 0
                        if (dur > 0) tvTimeCur.text = formatMs((dur.toLong() * progress / 1000L).toInt())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    val p = player ?: return
                    if (currentItem?.isRadio == false && p.duration > 0) {
                        p.seekTo((p.duration.toLong() * (sb?.progress ?: 0) / 1000L).toInt())
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
        val btnPrev = controlButton("⏮", false) { playPrev() }
        btnPlayPause = controlButton("▶ PUTAR", true) { togglePlayPause() }
        val btnNext = controlButton("⏭", false) { playNext() }
        btnListDrawer = controlButton("☰ DAFTAR LAGU", false) { openQuickListDrawer() }

        ctrlRow.addView(btnPrev, LinearLayout.LayoutParams(dp(54), dp(42)).apply { rightMargin = dp(8) })
        ctrlRow.addView(btnPlayPause, LinearLayout.LayoutParams(dp(120), dp(42)).apply { rightMargin = dp(8) })
        ctrlRow.addView(btnNext, LinearLayout.LayoutParams(dp(54), dp(42)).apply { rightMargin = dp(10) })
        ctrlRow.addView(btnListDrawer, LinearLayout.LayoutParams(0, dp(42), 1f))
        playerCard.addView(ctrlRow)

        mainCol.addView(playerCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Tabs (Radio Online vs Musik Lokal) + Search Bar with Voice Input
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

        // Voice-to-Text Mic Button
        val micBtn = voiceInput.createMicButton(etSearch, "Sebutkan nama lagu atau stasiun radio...", brassLight)
        filterRow.addView(micBtn)

        mainCol.addView(filterRow)

        // Scrollable List of Stations / Songs
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer, LinearLayout.LayoutParams(-1, -2))
        }
        mainCol.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(mainCol, FrameLayout.LayoutParams(-1, -1))

        // Quick Playlist Modal Overlay (like TV channel list for instant song jumping)
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
        val dScroll = ScrollView(this).apply {
            addView(drawerContainer, LinearLayout.LayoutParams(-1, -2))
        }
        drawerBox.addView(dScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val dLp = FrameLayout.LayoutParams(dp(440).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)), -1, Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
        drawerOverlay.addView(drawerBox, dLp)
        root.addView(drawerOverlay, FrameLayout.LayoutParams(-1, -1))

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
        // Built-in reliable Indonesian Radio Streams
        radioList.add(AudioItem("RRI Pro 3 Nasional", "Berita & Informasi Nasional", "https://stream-node1.rri.co.id/streaming/14/9014/rripro3.mp3", true))
        radioList.add(AudioItem("RRI Pro 1 Samarinda", "Kalimantan Timur · Regional", "https://stream-node1.rri.co.id/streaming/12/9012/rripro1samarinda.mp3", true))
        radioList.add(AudioItem("RRI Pro 2 Samarinda", "Musik & Kreativitas Muda", "https://stream-node1.rri.co.id/streaming/12/9012/rripro2samarinda.mp3", true))
        radioList.add(AudioItem("Prambors FM Jakarta", "Hits Pop Indonesia & Mancanegara", "https://22283.live.streamtheworld.com/PRAMBORS_FMAAC.aac", true))
        radioList.add(AudioItem("Delta FM", "Lagu Enak Sepanjang Hari", "https://22253.live.streamtheworld.com/DELTA_FMAAC.aac", true))
        radioList.add(AudioItem("Suara Surabaya FM", "Berita & Interaktif Jawa Timur", "https://c1.siar.us:8000/stream", true))
        radioList.add(AudioItem("Radio Rodja 756 AM", "Kajian Islam & Murottal Al-Qur'an", "https://radioislamindonesia.com/rodja.mp3", true))

        // Load any custom user-saved radios from SharedPreferences
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
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
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
            val projection = arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
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

        items.forEachIndexed { idx, item ->
            val active = (currentItem?.uri == item.uri)
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

            val numTv = tv("${idx + 1}.", 13f, if (active) brassLight else mute, Typeface.DEFAULT_BOLD)
            row.addView(numTv, LinearLayout.LayoutParams(dp(32), -2))

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tTv = tv(item.title, if (compact) 13.5f else 14.5f, if (active) brassLight else Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val sTv = tv(item.subtitle, 11.5f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            col.addView(tTv)
            col.addView(sTv)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val badge = tv(if (active) "▶ DIPUTAR" else if (item.isRadio) "LIVE" else "MP3", 10.5f, if (active) brassLight else mute, Typeface.DEFAULT_BOLD)
            row.addView(badge)

            row.setOnClickListener {
                playItem(item, items)
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

    // =========================================================================
    // AUDIO PLAYBACK, FOCUS, CALL HANDLING & RADIO AUTO-RECONNECT
    // =========================================================================
    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun playItem(item: AudioItem, sourceList: List<AudioItem> = visibleList) {
        if (!requestFocus()) {
            setSignalBadge("● MENUNGGU AUDIO", 0xFFE6C88F.toInt())
            return
        }
        pausedByCallOrFocus = false
        pausedByAdzan = false
        currentItem = item
        currentIndex = sourceList.indexOfFirst { it.uri == item.uri }
        retryCount = 0
        stallTicks = 0
        lastPlaybackPos = -1

        tvNowTitle.text = item.title
        tvNowSub.text = item.subtitle
        tvTimeDur.text = if (item.isRadio) "LIVE" else "--:--"
        seekBar.isEnabled = !item.isRadio
        if (item.isRadio) seekBar.progress = 1000

        renderList(listContainer, visibleList, compact = false)
        startMediaPlayer(item)
    }

    private fun startMediaPlayer(item: AudioItem) {
        releasePlayer()
        isPreparing = true
        isPlaying = false
        btnPlayPause.text = "⏳ MEMUAT"
        setSignalBadge(if (item.isRadio) "● MENYAMBUNG..." else "● MEMUAT...", brassLight)

        try {
            val mp = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(item.uri)
                setOnPreparedListener { p ->
                    isPreparing = false
                    retryCount = 0
                    stallTicks = 0
                    if (pausedByCallOrFocus || pausedByAdzan) {
                        btnPlayPause.text = "▶ LANJUT"
                        return@setOnPreparedListener
                    }
                    p.start()
                    isPlaying = true
                    btnPlayPause.text = "❚❚ JEDA"
                    setSignalBadge(if (item.isRadio) "● SINYAL STABIL" else "● MEMUTAR", 0xFF4CAF7D.toInt())
                    if (!item.isRadio && p.duration > 0) {
                        tvTimeDur.text = formatMs(p.duration)
                    }
                }
                setOnCompletionListener {
                    isPlaying = false
                    if (item.isRadio) {
                        scheduleRadioReconnect("Sinyal terputus, menyambung ulang...")
                    } else {
                        playNext()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isPreparing = false
                    isPlaying = false
                    if (item.isRadio) {
                        scheduleRadioReconnect("Jaringan melemah, mencari sinyal...")
                    } else {
                        setSignalBadge("● FILE ERROR", 0xFFE05252.toInt())
                        btnPlayPause.text = "▶ PUTAR"
                    }
                    true
                }
                prepareAsync()
            }
            player = mp
        } catch (_: Exception) {
            isPreparing = false
            if (item.isRadio) {
                scheduleRadioReconnect("Gagal terhubung, mencoba ulang...")
            }
        }
    }

    private fun scheduleRadioReconnect(reason: String) {
        val item = currentItem ?: return
        if (!item.isRadio || pausedByCallOrFocus || pausedByAdzan) return
        if (retryCount >= maxRetries) {
            setSignalBadge("● SINYAL HILANG", 0xFFE05252.toInt())
            btnPlayPause.text = "↻ COBA LAGI"
            return
        }
        retryCount++
        setSignalBadge("● RECONNECT ($retryCount/$maxRetries)", 0xFFFF9800.toInt())
        tvNowSub.text = reason
        ui.removeCallbacks(reconnectRunnable)
        ui.postDelayed(reconnectRunnable, 2500L)
    }

    private val reconnectRunnable = Runnable {
        val item = currentItem ?: return@Runnable
        if (item.isRadio && !pausedByCallOrFocus && !pausedByAdzan) {
            startMediaPlayer(item)
        }
    }

    private fun togglePlayPause() {
        val item = currentItem
        if (item == null) {
            if (visibleList.isNotEmpty()) playItem(visibleList[0])
            return
        }
        if (isPlaying) {
            pausedByCallOrFocus = false
            pausePlayback(byExternalInterruption = false)
        } else {
            if (player != null && !isPreparing) {
                if (requestFocus()) resumePlayback()
            } else {
                retryCount = 0
                playItem(item)
            }
        }
    }

    private fun pausePlayback(byExternalInterruption: Boolean) {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
        isPlaying = false
        btnPlayPause.text = "▶ PUTAR"
        if (byExternalInterruption) {
            setSignalBadge(if (pausedByAdzan) "● JEDA ADZAN" else "● JEDA TELEPON", brassLight)
        } else {
            setSignalBadge("● DIJEDA", mute)
        }
    }

    private fun resumePlayback() {
        try {
            player?.start()
            isPlaying = true
            btnPlayPause.text = "❚❚ JEDA"
            setSignalBadge(if (currentItem?.isRadio == true) "● SINYAL STABIL" else "● MEMUTAR", 0xFF4CAF7D.toInt())
        } catch (_: Exception) {
            currentItem?.let { startMediaPlayer(it) }
        }
    }

    private fun playNext() {
        val list = if (visibleList.isNotEmpty()) visibleList else currentList
        if (list.isEmpty()) return
        val nextIdx = (currentIndex + 1) % list.size
        playItem(list[nextIdx], list)
    }

    private fun playPrev() {
        val list = if (visibleList.isNotEmpty()) visibleList else currentList
        if (list.isEmpty()) return
        val prevIdx = if (currentIndex <= 0) list.size - 1 else currentIndex - 1
        playItem(list[prevIdx], list)
    }

    private fun setSignalBadge(text: String, color: Int) {
        tvSignalStatus.text = text
        tvSignalStatus.setTextColor(color)
        tvSignalStatus.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor((color and 0x00FFFFFF) or 0x22000000)
        }
    }

    private fun releasePlayer() {
        ui.removeCallbacks(reconnectRunnable)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        isPlaying = false
        isPreparing = false
    }

    // =========================================================================
    // PHONE CALL STATE + NETWORK RECOVERY MONITORS
    // =========================================================================
    private fun registerCallAndNetworkMonitors() {
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        // 1. Phone Call State Listener (Android 12+ TelephonyCallback & Legacy PhoneStateListener)
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handlePhoneCallState(state)
                        }
                    }
                    telephonyCallback = cb
                    telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handlePhoneCallState(state)
                    }
                }
                legacyPhoneListener = listener
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: Exception) {
        }

        // 2. Network Recovery Callback for Radio Auto-Resume
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ui.post {
                        val item = currentItem
                        if (item != null && item.isRadio && !isPlaying && !isPreparing && !pausedByCallOrFocus && !pausedByAdzan) {
                            retryCount = 0
                            startMediaPlayer(item)
                        }
                    }
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(req, cb)
        } catch (_: Exception) {
        }
    }

    private fun handlePhoneCallState(callState: Int) {
        ui.post {
            when (callState) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (isPlaying || isPreparing) {
                        pausedByCallOrFocus = true
                        pausePlayback(byExternalInterruption = true)
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (pausedByCallOrFocus && !pausedByAdzan) {
                        pausedByCallOrFocus = false
                        if (requestFocus()) resumePlayback()
                    }
                }
            }
        }
    }

    // =========================================================================
    // ADZAN SCHEDULE CHECKER (SYNCHRONIZED WITH HOMEACTIVITY PREFS)
    // =========================================================================
    private fun loadPrayerSchedule() {
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        val cachedDate = prefs.getString("pray_date", "")
        val cachedJson = prefs.getString("pray", null)
        if (cachedDate == today && cachedJson != null && parsePrayerJson(cachedJson)) {
            return
        }
        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: -0.0263
        val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: 109.3425
        val tz = prefs.getString("tz", null) ?: "Asia/Pontianak"
        Thread {
            try {
                val u = "https://api.aladhan.com/v1/timings/$today?latitude=$lat&longitude=$lon&method=20&timezonestring=${java.net.URLEncoder.encode(tz, "UTF-8")}"
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                val res = c.inputStream.bufferedReader().use { it.readText() }
                ui.post {
                    if (parsePrayerJson(res)) {
                        prefs.edit().putString("pray_date", today).putString("pray", res).apply()
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun parsePrayerJson(json: String): Boolean {
        return try {
            val t = JSONObject(json).getJSONObject("data").getJSONObject("timings")
            val keys = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            for (i in 0..4) {
                val s = t.getString(keys[i])
                prayerTimes[i] = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun checkAdzanAndUpdateCountdown() {
        if (prayerTimes[0] < 0) {
            tvAdzanStatus.text = "Adzan: Sinkron..."
            return
        }
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val offset = (prefs.getString("adzan_offset", "0") ?: "0").toIntOrNull()?.coerceIn(-15, 15) ?: 0
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

        // Check if current minute matches any prayer time
        for (i in 0..4) {
            val pMin = prayerTimes[i] + offset
            val key = "${today}_$i"
            if (nowMin == pMin && lastTriggeredAdzanKey != key) {
                lastTriggeredAdzanKey = key
                triggerAdzanInterruption(prayerNames[i])
                break
            }
        }

        // Update next prayer badge
        var nx = prayerTimes.indexOfFirst { (it + offset) > nowMin }
        val diff = if (nx < 0) {
            nx = 0
            (prayerTimes[0] + offset) + 1440 - nowMin
        } else {
            (prayerTimes[nx] + offset) - nowMin
        }
        val h = diff / 60
        val m = diff % 60
        tvAdzanStatus.text = "${prayerNames[nx]} -${h}j ${m}m"
    }

    private fun triggerAdzanInterruption(prayerName: String) {
        val wasPlaying = isPlaying || isPreparing
        if (wasPlaying) {
            pausedByAdzan = true
            pausePlayback(byExternalInterruption = true)
        }

        try {
            val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, notifUri)?.play()
        } catch (_: Exception) {
        }

        if (isFinishing || isDestroyed) return
        adzanAlertDialog?.dismiss()
        adzanAlertDialog = AlertDialog.Builder(this)
            .setTitle("Waktu Sholat $prayerName")
            .setMessage("Telah masuk waktu sholat $prayerName. Pemutaran musik/radio dijeda sementara.")
            .setPositiveButton("Lanjutkan Musik") { _, _ ->
                pausedByAdzan = false
                if (wasPlaying && !pausedByCallOrFocus && requestFocus()) {
                    resumePlayback()
                }
            }
            .setNegativeButton("Tutup") { _, _ ->
                pausedByAdzan = false
            }
            .show()
    }

    // =========================================================================
    // PERIODIC WATCHDOG (SEEKBAR + RADIO STALL DETECTION + ADZAN)
    // =========================================================================
    private val monitorRunnable = object : Runnable {
        override fun run() {
            checkAdzanAndUpdateCountdown()

            val p = player
            val item = currentItem
            if (p != null && item != null && isPlaying) {
                try {
                    val pos = p.currentPosition
                    if (!item.isRadio) {
                        val dur = p.duration
                        if (dur > 0 && !userSeeking) {
                            tvTimeCur.text = formatMs(pos)
                            seekBar.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                        }
                    } else {
                        // Watchdog: if radio stream position freezes for 6 seconds, reconnect automatically
                        if (pos == lastPlaybackPos) {
                            stallTicks++
                            if (stallTicks >= 6) {
                                stallTicks = 0
                                scheduleRadioReconnect("Buffer terhenti, menyegarkan aliran radio...")
                            }
                        } else {
                            stallTicks = 0
                            lastPlaybackPos = pos
                        }
                    }
                } catch (_: Exception) {
                }
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
        adzanAlertDialog?.dismiss()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(legacyPhoneListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
        try {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }
        releasePlayer()
        super.onDestroy()
    }
}
