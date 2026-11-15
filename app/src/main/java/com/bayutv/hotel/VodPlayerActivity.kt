package com.bayutv.hotel

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

// Pemutar film/episode (Koleksi Saya) dengan subtitle; utamakan bahasa Indonesia.
class VodPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var notice: Notice
    private var usedFallback = false
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }
    private val resumeKey: String get() = "pos_" + (intent.getStringExtra("resume_key") ?: "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        view = PlayerView(this)
        view.useController = true
        view.setShowSubtitleButton(true)
        view.controllerShowTimeoutMs = 3500
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        notice = Notice(this, root)
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("id", "ms", "en")
            .setPreferredAudioLanguages("ja", "id", "en")
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val fb = intent.getStringExtra("fallback") ?: ""
                if (!usedFallback && fb.isNotEmpty()) {
                    // Format video tidak didukung TV: minta server mengubahnya (transcode)
                    usedFallback = true
                    val pos = p.currentPosition
                    notice.show(null, "Mengonversi video di server...", 3000L)
                    p.setMediaItem(item(fb), pos)
                    p.prepare()
                    p.playWhenReady = true
                    return
                }
                notice.show("Tidak bisa diputar", error.errorCodeName)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefs.edit().remove(resumeKey).apply()
                    finish()
                }
            }
        })
        view.player = p
        player = p

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }
        val resume = prefs.getLong(resumeKey, 0L)
        p.setMediaItem(item(url), resume)
        p.prepare()
        p.playWhenReady = true
        val title = intent.getStringExtra("title") ?: ""
        if (resume > 60000L) notice.show(title, "Melanjutkan dari menit " + (resume / 60000L), 3000L)
        else if (title.isNotEmpty()) notice.show(null, title, 2500L)
    }

    private fun item(url: String): MediaItem {
        val urls = intent.getStringArrayExtra("sub_urls") ?: emptyArray()
        val langs = intent.getStringArrayExtra("sub_langs") ?: emptyArray()
        val labels = intent.getStringArrayExtra("sub_labels") ?: emptyArray()
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        for (i in urls.indices) {
            val lang = langs.getOrNull(i) ?: "und"
            val indo = lang.startsWith("id") || lang.startsWith("in") || lang == "ind"
            subs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(urls[i]))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(lang)
                    .setLabel(labels.getOrNull(i) ?: lang)
                    .setSelectionFlags(if (indo) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }
        return MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (pos > 60000L && (dur <= 0L || pos < dur - 180000L)) prefs.edit().putLong(resumeKey, pos).apply()
        p.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
