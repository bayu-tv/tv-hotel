package com.bayutv.hotel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "bbox_music_playback"
        const val NOTIF_ID = 7071

        const val ACTION_PREV = "com.bayutv.hotel.ACTION_MUSIC_PREV"
        const val ACTION_PLAY_PAUSE = "com.bayutv.hotel.ACTION_MUSIC_PLAY_PAUSE"
        const val ACTION_NEXT = "com.bayutv.hotel.ACTION_MUSIC_NEXT"
        const val ACTION_STOP = "com.bayutv.hotel.ACTION_MUSIC_STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private var player: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null

    var playlist: List<RadioMusicActivity.AudioItem> = emptyList()
    var currentIndex: Int = -1
    val currentItem: RadioMusicActivity.AudioItem?
        get() = playlist.getOrNull(currentIndex)

    var isPlaying = false
        private set
    var isPreparing = false
        private set
    var signalText = "● SIAP"
        private set
    var signalColor = 0xFF4CAF7D.toInt()
        private set
    var adzanCountdownText = "Adzan: Sinkron..."
        private set
    var activeAdzanPrayer: String? = null
        private set

    private var pausedByCallOrFocus = false
    private var pausedByAdzan = false
    private var retryCount = 0
    private val maxRetries = 10
    private var lastPlaybackPos = -1
    private var stallTicks = 0

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val prayerTimes = IntArray(5) { -1 }
    private val prayerNames = arrayOf("Subuh", "Zuhur", "Asar", "Magrib", "Isya")
    private var lastTriggeredAdzanKey = ""

    var onStateChanged: (() -> Unit)? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying) {
                pausePlayback(byExternalInterruption = false)
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
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

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        registerMonitors()
        loadPrayerSchedule()
        ui.post(watchdogRunnable)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> playPrev()
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_STOP -> stopAndDismissService()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Pemutar Musik & Radio B-BOX",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kontrol pemutaran musik dan radio di bilah notifikasi & layar kunci"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "BBoxMusicSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { pausePlayback(byExternalInterruption = false) }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrev() }
                override fun onStop() { stopAndDismissService() }
                override fun onSeekTo(pos: Long) {
                    if (currentItem?.isRadio == false) {
                        player?.seekTo(pos.toInt())
                        updateMediaSessionState()
                    }
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val item = currentItem

        if (item != null) {
            val dur = if (!item.isRadio && (player?.duration ?: 0) > 0) player!!.duration.toLong() else -1L
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .build()
            session.setMetadata(meta)
        }

        val stateCode = when {
            isPlaying -> PlaybackState.STATE_PLAYING
            isPreparing -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        val pos = try { if (item?.isRadio == false) (player?.currentPosition ?: 0).toLong() else 0L } catch (_: Exception) { 0L }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(stateCode, pos, 1.0f)
            .build()
        session.setPlaybackState(pbState)
    }

    private fun updateNotification() {
        val item = currentItem ?: return
        updateMediaSessionState()

        val openAppIntent = Intent(this, RadioMusicActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 10, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPi = serviceActionPi(ACTION_PREV, 11)
        val playPausePi = serviceActionPi(ACTION_PLAY_PAUSE, 12)
        val nextPi = serviceActionPi(ACTION_NEXT, 13)
        val stopPi = serviceActionPi(ACTION_STOP, 14)

        val playPauseIcon = if (isPlaying || isPreparing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying || isPreparing) "Jeda" else "Putar"

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(item.title)
            .setContentText(item.subtitle)
            .setSubText(if (item.isRadio) "RADIO LIVE" else "MUSIK")
            .setContentIntent(contentPi)
            .setOngoing(isPlaying || isPreparing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", prevPi).build())
            .addAction(Notification.Action.Builder(playPauseIcon, playPauseLabel, playPausePi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", nextPi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        builder.style = mediaStyle

        val notif = builder.build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
        }
    }

    private fun serviceActionPi(action: String, reqCode: Int): PendingIntent {
        val i = Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun requestAudioFocus(): Boolean {
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

    fun playFromList(list: List<RadioMusicActivity.AudioItem>, index: Int) {
        if (list.isEmpty() || index !in list.indices) return
        playlist = list
        currentIndex = index
        pausedByCallOrFocus = false
        pausedByAdzan = false
        activeAdzanPrayer = null
        retryCount = 0
        stallTicks = 0
        lastPlaybackPos = -1

        if (!requestAudioFocus()) {
            signalText = "● MENUNGGU AUDIO"
            signalColor = 0xFFE6C88F.toInt()
            notifyUi()
            return
        }
        startMediaPlayer(list[index])
    }

    private fun startMediaPlayer(item: RadioMusicActivity.AudioItem) {
        releasePlayerOnly()
        isPreparing = true
        isPlaying = false
        signalText = if (item.isRadio) "● MENYAMBUNG..." else "● MEMUAT..."
        signalColor = 0xFFE6C88F.toInt()
        updateNotification()
        notifyUi()

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
                        updateNotification()
                        notifyUi()
                        return@setOnPreparedListener
                    }
                    p.start()
                    isPlaying = true
                    signalText = if (item.isRadio) "● SINYAL STABIL" else "● MEMUTAR"
                    signalColor = 0xFF4CAF7D.toInt()
                    updateNotification()
                    notifyUi()
                }
                setOnCompletionListener {
                    isPlaying = false
                    if (item.isRadio) {
                        scheduleRadioReconnect()
                    } else {
                        playNext()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isPreparing = false
                    isPlaying = false
                    if (item.isRadio) {
                        scheduleRadioReconnect()
                    } else {
                        signalText = "● FILE ERROR"
                        signalColor = 0xFFE05252.toInt()
                        updateNotification()
                        notifyUi()
                    }
                    true
                }
                prepareAsync()
            }
            player = mp
        } catch (_: Exception) {
            isPreparing = false
            if (item.isRadio) scheduleRadioReconnect()
        }
    }

    private fun scheduleRadioReconnect() {
        val item = currentItem ?: return
        if (!item.isRadio || pausedByCallOrFocus || pausedByAdzan) return
        if (retryCount >= maxRetries) {
            signalText = "● SINYAL HILANG"
            signalColor = 0xFFE05252.toInt()
            updateNotification()
            notifyUi()
            return
        }
        retryCount++
        signalText = "● RECONNECT ($retryCount/$maxRetries)"
        signalColor = 0xFFFF9800.toInt()
        notifyUi()
        ui.removeCallbacks(reconnectRunnable)
        ui.postDelayed(reconnectRunnable, 2500L)
    }

    private val reconnectRunnable = Runnable {
        val item = currentItem ?: return@Runnable
        if (item.isRadio && !pausedByCallOrFocus && !pausedByAdzan) {
            startMediaPlayer(item)
        }
    }

    fun togglePlayPause() {
        val item = currentItem
        if (item == null) {
            if (playlist.isNotEmpty()) playFromList(playlist, 0)
            return
        }
        if (isPlaying) {
            pausedByCallOrFocus = false
            pausePlayback(byExternalInterruption = false)
        } else {
            if (player != null && !isPreparing) {
                if (requestAudioFocus()) resumePlayback()
            } else {
                retryCount = 0
                startMediaPlayer(item)
            }
        }
    }

    fun pausePlayback(byExternalInterruption: Boolean) {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
        isPlaying = false
        signalText = when {
            pausedByAdzan -> "● JEDA ADZAN"
            byExternalInterruption -> "● JEDA TELEPON"
            else -> "● DIJEDA"
        }
        signalColor = if (byExternalInterruption) 0xFFE6C88F.toInt() else 0xFFA79F90.toInt()
        updateNotification()
        notifyUi()
    }

    fun resumePlayback() {
        pausedByAdzan = false
        activeAdzanPrayer = null
        try {
            player?.start()
            isPlaying = true
            signalText = if (currentItem?.isRadio == true) "● SINYAL STABIL" else "● MEMUTAR"
            signalColor = 0xFF4CAF7D.toInt()
            updateNotification()
            notifyUi()
        } catch (_: Exception) {
            currentItem?.let { startMediaPlayer(it) }
        }
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val next = (currentIndex + 1) % playlist.size
        playFromList(playlist, next)
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        val prev = if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1
        playFromList(playlist, prev)
    }

    fun seekTo(ms: Int) {
        if (currentItem?.isRadio == false) {
            try {
                player?.seekTo(ms)
                updateMediaSessionState()
            } catch (_: Exception) {
            }
        }
    }

    fun getPositionMs(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    fun getDurationMs(): Int = try { player?.duration ?: 0 } catch (_: Exception) { 0 }

    fun dismissAdzanBanner(resumeMusic: Boolean) {
        activeAdzanPrayer = null
        pausedByAdzan = false
        if (resumeMusic && !pausedByCallOrFocus && requestAudioFocus()) {
            resumePlayback()
        } else {
            notifyUi()
        }
    }

    fun stopAndDismissService() {
        releasePlayerOnly()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifyUi()
        stopSelf()
    }

    private fun releasePlayerOnly() {
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

    private fun notifyUi() {
        ui.post { onStateChanged?.invoke() }
    }

    // =========================================================================
    // MONITORS: TELEPHONY CALL STATE, NETWORK RECOVERY & ADZAN SCHEDULE
    // =========================================================================
    private fun registerMonitors() {
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) { handleCallState(state) }
                    }
                    telephonyCallback = cb
                    telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { handleCallState(state) }
                }
                legacyPhoneListener = listener
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: Exception) {
        }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
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

    private fun handleCallState(callState: Int) {
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
                        if (requestAudioFocus()) resumePlayback()
                    }
                }
            }
        }
    }

    private fun loadPrayerSchedule() {
        val today = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        val cachedDate = prefs.getString("pray_date", "")
        val cachedJson = prefs.getString("pray", null)
        if (cachedDate == today && cachedJson != null && parsePrayerJson(cachedJson)) return

        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: -1.8943
        val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: 116.1953
        val tz = prefs.getString("tz", null) ?: "Asia/Makassar"
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

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkAdzan()
            val item = currentItem
            if (item != null && item.isRadio && isPlaying) {
                val pos = getPositionMs()
                if (pos == lastPlaybackPos) {
                    stallTicks++
                    if (stallTicks >= 6) {
                        stallTicks = 0
                        scheduleRadioReconnect()
                    }
                } else {
                    stallTicks = 0
                    lastPlaybackPos = pos
                }
            }
            notifyUi()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun checkAdzan() {
        if (prayerTimes[0] < 0) return
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val offset = (prefs.getString("adzan_offset", "0") ?: "0").toIntOrNull()?.coerceIn(-15, 15) ?: 0
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

        for (i in 0..4) {
            val pMin = prayerTimes[i] + offset
            val key = "${today}_$i"
            if (nowMin == pMin && lastTriggeredAdzanKey != key) {
                lastTriggeredAdzanKey = key
                if (isPlaying || isPreparing) {
                    pausedByAdzan = true
                    pausePlayback(byExternalInterruption = true)
                }
                activeAdzanPrayer = prayerNames[i]
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    RingtoneManager.getRingtone(applicationContext, uri)?.play()
                } catch (_: Exception) {
                }
                notifyUi()
                break
            }
        }

        var nx = prayerTimes.indexOfFirst { (it + offset) > nowMin }
        val diff = if (nx < 0) {
            nx = 0
            (prayerTimes[0] + offset) + 1440 - nowMin
        } else {
            (prayerTimes[nx] + offset) - nowMin
        }
        adzanCountdownText = "${prayerNames[nx]} -${diff / 60}j ${diff % 60}m"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(legacyPhoneListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {}
        try {
            networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        releasePlayerOnly()
        mediaSession?.release()
        super.onDestroy()
    }
}
