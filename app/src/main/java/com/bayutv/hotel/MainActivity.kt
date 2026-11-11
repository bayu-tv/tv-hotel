package com.bayutv.hotel

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var status: TextView

    // Stream uji publik. Nanti diganti oleh daftar channel.
    private val testUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        val view = PlayerView(this)
        view.useController = false
        status = TextView(this)
        status.setTextColor(0xFFEFE8DA.toInt())
        status.textSize = 22f
        status.setPadding(48, 32, 48, 32)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        view.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Memuat..."
                if (state == Player.STATE_READY) status.text = ""
            }

            override fun onPlayerError(error: PlaybackException) {
                status.text = "Sinyal hilang: " + error.errorCodeName
            }
        })
        p.setMediaItem(MediaItem.fromUri(testUrl))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            player?.let { it.playWhenReady = !it.playWhenReady }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
