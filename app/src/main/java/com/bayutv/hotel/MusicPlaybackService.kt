package com.bayutv.hotel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "bbox_music_playback"
        const val NOTIF_ID = 7071

        const val ACTION_SYNC = "com.bayutv.hotel.ACTION_MUSIC_SYNC"
        const val ACTION_PREV = "com.bayutv.hotel.ACTION_MUSIC_PREV"
        const val ACTION_PLAY_PAUSE = "com.bayutv.hotel.ACTION_MUSIC_PLAY_PAUSE"
        const val ACTION_NEXT = "com.bayutv.hotel.ACTION_MUSIC_NEXT"
        const val ACTION_STOP = "com.bayutv.hotel.ACTION_MUSIC_STOP"

        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private var pausedByFocus: Boolean = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (RadioMusicActivity.MusicEngine.state == RadioMusicActivity.PlayState.PLAYING) {
                    RadioMusicActivity.MusicEngine.togglePause()
                }
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val engine = RadioMusicActivity.MusicEngine
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (engine.state == RadioMusicActivity.PlayState.PLAYING) {
                    pausedByFocus = true
                    engine.togglePause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    engine.player?.volume = 0.2f
                } catch (_: Exception) {
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    engine.player?.volume = 1.0f
                } catch (_: Exception) {
                }
                if (pausedByFocus && engine.state == RadioMusicActivity.PlayState.PAUSED) {
                    pausedByFocus = false
                    engine.togglePause()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        requestAudioFocus()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = RadioMusicActivity.MusicEngine
        when (intent?.action) {
            ACTION_PREV -> engine.playPrev(this)
            ACTION_PLAY_PAUSE -> {
                pausedByFocus = false
                if (engine.state != RadioMusicActivity.PlayState.PLAYING) {
                    requestAudioFocus()
                }
                engine.togglePause()
            }
            ACTION_NEXT -> engine.playNext(this)
            ACTION_STOP -> engine.stopAndReleaseNow(clearTrack = true)
            ACTION_SYNC -> {
                if (engine.state == RadioMusicActivity.PlayState.PLAYING ||
                    engine.state == RadioMusicActivity.PlayState.BUFFERING
                ) {
                    requestAudioFocus()
                }
            }
        }

        val track = engine.currentTrack
        val st = engine.state
        if (track == null || st == RadioMusicActivity.PlayState.IDLE) {
            stopServiceCleanly()
        } else {
            updateNotification(track, st, engine.statusDetail)
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Pemutar Musik & Radio B-BOX",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kontrol pemutaran musik online, karaoke, dan radio di layar kunci"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "BBoxMusicSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (RadioMusicActivity.MusicEngine.state != RadioMusicActivity.PlayState.PLAYING) {
                        requestAudioFocus()
                        RadioMusicActivity.MusicEngine.togglePause()
                    }
                }

                override fun onPause() {
                    if (RadioMusicActivity.MusicEngine.state == RadioMusicActivity.PlayState.PLAYING) {
                        RadioMusicActivity.MusicEngine.togglePause()
                    }
                }

                override fun onSkipToNext() {
                    RadioMusicActivity.MusicEngine.playNext(this@MusicPlaybackService)
                }

                override fun onSkipToPrevious() {
                    RadioMusicActivity.MusicEngine.playPrev(this@MusicPlaybackService)
                }

                override fun onStop() {
                    RadioMusicActivity.MusicEngine.stopAndReleaseNow(clearTrack = true)
                }

                override fun onSeekTo(pos: Long) {
                    val exo = RadioMusicActivity.MusicEngine.player ?: return
                    if (RadioMusicActivity.MusicEngine.currentTrack?.isLive != true) {
                        try {
                            exo.seekTo(pos)
                        } catch (_: Exception) {
                        }
                    }
                }
            })
            isActive = true
        }
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build().also { focusRequest = it }
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun updateNotification(
        track: RadioMusicActivity.Track,
        st: RadioMusicActivity.PlayState,
        detail: String
    ) {
        val isPlayingOrLoading = (
            st == RadioMusicActivity.PlayState.PLAYING ||
                st == RadioMusicActivity.PlayState.BUFFERING ||
                st == RadioMusicActivity.PlayState.RESOLVING
            )

        mediaSession?.let { session ->
            val exo = RadioMusicActivity.MusicEngine.player
            val dur = try {
                if (!track.isLive && (exo?.duration ?: 0L) > 0L) exo!!.duration else -1L
            } catch (_: Exception) {
                -1L
            }
            val pos = try {
                if (!track.isLive) (exo?.currentPosition ?: 0L) else 0L
            } catch (_: Exception) {
                0L
            }

            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                    .build()
            )

            val pbCode = when (st) {
                RadioMusicActivity.PlayState.PLAYING -> PlaybackState.STATE_PLAYING
                RadioMusicActivity.PlayState.BUFFERING,
                RadioMusicActivity.PlayState.RESOLVING -> PlaybackState.STATE_BUFFERING
                RadioMusicActivity.PlayState.ERROR -> PlaybackState.STATE_ERROR
                else -> PlaybackState.STATE_PAUSED
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO

            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(pbCode, pos, 1.0f)
                    .build()
            )
        }

        val openIntent = Intent(this, RadioMusicActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPi = actionPi(ACTION_PREV, 11)
        val playPausePi = actionPi(ACTION_PLAY_PAUSE, 12)
        val nextPi = actionPi(ACTION_NEXT, 13)
        val stopPi = actionPi(ACTION_STOP, 14)

        val playIcon = if (isPlayingOrLoading) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playLabel = if (isPlayingOrLoading) "Jeda" else "Putar"
        val subBadge = when {
            track.isLive -> "RADIO LIVE"
            track.isYtMusic -> "B-BOX MUSIC"
            else -> "MUSIK LOKAL"
        }

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText("${track.artist} ·$detail")
            .setSubText(subBadge)
            .setContentIntent(contentPi)
            .setOngoing(isPlayingOrLoading)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", prevPi).build())
            .addAction(Notification.Action.Builder(playIcon, playLabel, playPausePi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", nextPi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
            .style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

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

    private fun stopServiceCleanly() {
        try {
            @Suppress("DEPRECATION")
            val placeholder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("B-BOX Music")
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, placeholder)
            }
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun actionPi(action: String, code: Int): PendingIntent {
        val i = Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            code,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        mediaSession?.release()
        super.onDestroy()
    }
}
