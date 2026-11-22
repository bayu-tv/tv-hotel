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
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
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

        fun syncFromEngine(context: Context) {
            val engine = RadioMusicActivity.MusicEngine
            val hasTrack = engine.currentTrack != null && engine.state != RadioMusicActivity.PlayState.IDLE
            try {
                val i = Intent(context, MusicPlaybackService::class.java).apply {
                    action = if (hasTrack) ACTION_SYNC else ACTION_STOP
                }
                if (hasTrack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val engine = RadioMusicActivity.MusicEngine
                if (engine.state == RadioMusicActivity.PlayState.PLAYING) {
                    engine.togglePause()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = RadioMusicActivity.MusicEngine
        when (intent?.action) {
            ACTION_PREV -> engine.playPrev(applicationContext)
            ACTION_PLAY_PAUSE -> engine.togglePause()
            ACTION_NEXT -> engine.playNext(applicationContext)
            ACTION_STOP -> {
                if (engine.currentTrack != null && engine.state != RadioMusicActivity.PlayState.IDLE) {
                    engine.stopAndReleaseNow(clearTrack = true)
                }
                stopAndRemove()
                return START_NOT_STICKY
            }
        }
        updateNotificationFromEngine()
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
                override fun onPlay() {
                    val engine = RadioMusicActivity.MusicEngine
                    if (engine.state != RadioMusicActivity.PlayState.PLAYING) engine.togglePause()
                }

                override fun onPause() {
                    val engine = RadioMusicActivity.MusicEngine
                    if (engine.state == RadioMusicActivity.PlayState.PLAYING) engine.togglePause()
                }

                override fun onSkipToNext() {
                    RadioMusicActivity.MusicEngine.playNext(applicationContext)
                }

                override fun onSkipToPrevious() {
                    RadioMusicActivity.MusicEngine.playPrev(applicationContext)
                }

                override fun onStop() {
                    RadioMusicActivity.MusicEngine.stopAndReleaseNow(clearTrack = true)
                    stopAndRemove()
                }

                override fun onSeekTo(pos: Long) {
                    val engine = RadioMusicActivity.MusicEngine
                    if (engine.currentTrack?.isLive == false) {
                        try {
                            engine.player?.seekTo(pos)
                            updateNotificationFromEngine()
                        } catch (_: Exception) {
                        }
                    }
                }
            })
            isActive = true
        }
    }

    private fun updateNotificationFromEngine() {
        val engine = RadioMusicActivity.MusicEngine
        val track = engine.currentTrack
        if (track == null || engine.state == RadioMusicActivity.PlayState.IDLE) {
            stopAndRemove()
            return
        }

        val isPlaying = engine.state == RadioMusicActivity.PlayState.PLAYING
        val isBusy = engine.state == RadioMusicActivity.PlayState.RESOLVING ||
            engine.state == RadioMusicActivity.PlayState.BUFFERING

        val session = mediaSession
        if (session != null) {
            val dur = try {
                if (!track.isLive && (engine.player?.duration ?: 0L) > 0L) engine.player!!.duration else -1L
            } catch (_: Exception) {
                -1L
            }
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .build()
            session.setMetadata(meta)

            val pbCode = when {
                isPlaying -> PlaybackState.STATE_PLAYING
                isBusy -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_PAUSED
            }
            val pos = try {
                if (!track.isLive) (engine.player?.currentPosition ?: 0L) else 0L
            } catch (_: Exception) {
                0L
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

        val prevPi = serviceActionPi(ACTION_PREV, 11)
        val playPausePi = serviceActionPi(ACTION_PLAY_PAUSE, 12)
        val nextPi = serviceActionPi(ACTION_NEXT, 13)
        val stopPi = serviceActionPi(ACTION_STOP, 14)

        val playPauseIcon = if (isPlaying || isBusy) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying || isBusy) "Jeda" else "Putar"

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(if (track.isLive) "RADIO LIVE" else if (track.isYtMusic) "YOUTUBE MUSIC" else "MUSIK")
            .setContentIntent(contentPi)
            .setOngoing(isPlaying || isBusy)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", prevPi).build())
            .addAction(Notification.Action.Builder(playPauseIcon, playPauseLabel, playPausePi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", nextPi).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())

        builder.style = Notification.MediaStyle()
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

    private fun serviceActionPi(action: String, reqCode: Int): PendingIntent {
        val i = Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopAndRemove() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        mediaSession?.release()
        super.onDestroy()
    }
}
