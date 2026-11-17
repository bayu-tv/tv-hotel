package com.bayutv.hotel

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.se_bastiaan.torrentstream.StreamStatus
import com.github.se_bastiaan.torrentstream.Torrent
import com.github.se_bastiaan.torrentstream.TorrentOptions
import com.github.se_bastiaan.torrentstream.TorrentStream
import com.github.se_bastiaan.torrentstream.listeners.TorrentListener
import java.io.File
import java.util.Locale

class TorrentBufferActivity : AppCompatActivity(), TorrentListener {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()
    private val mute = 0xFFB5AEA0.toInt()
    private val bgDark = 0xFF0B0A09.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private var torrentStream: TorrentStream? = null
    private var playerLaunched = false
    private var cancelled = false

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        cleanupAndFinish()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val movieTitle = intent.getStringExtra("title") ?: "Memuat Film"
        val magnetUrl = intent.getStringExtra("magnet_url") ?: ""

        if (magnetUrl.isEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(bgDark)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(30), dp(36), dp(30))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF161412.toInt())
                setStroke(dp(1), brass)
            }
        }

        val badge = TextView(this).apply {
            text = "B-BOX P2P STREAMING - BEBAS IKLAN"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@TorrentBufferActivity, 11f))
            setTextColor(brassLight)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
        }
        card.addView(badge)

        titleText = TextView(this).apply {
            text = movieTitle
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@TorrentBufferActivity, 22f))
            setTextColor(ivory)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, dp(8), 0, dp(16))
        }
        card.addView(titleText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 5
            isIndeterminate = true
        }
        card.addView(progressBar, LinearLayout.LayoutParams(dp(440), dp(14)))

        statusText = TextView(this).apply {
            text = "Menghubungkan ke jaringan seeder..."
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@TorrentBufferActivity, 15f))
            setTextColor(ivory)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        card.addView(statusText)

        detailText = TextView(this).apply {
            text = "Proses awal biasanya 5–20 detik tergantung jumlah seeder"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@TorrentBufferActivity, 12f))
            setTextColor(mute)
            gravity = Gravity.CENTER
        }
        card.addView(detailText)

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@TorrentBufferActivity, 13f))
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(24), dp(9), dp(24), dp(9))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(brassLight)
            }
            setOnClickListener {
                cancelled = true
                cleanupAndFinish()
            }
        }
        val blp = LinearLayout.LayoutParams(-2, -2)
        blp.topMargin = dp(20)
        card.addView(cancelBtn, blp)

        root.addView(card, FrameLayout.LayoutParams(dp(540), -2, Gravity.CENTER))
        setContentView(root)
        cancelBtn.requestFocus()

        startTorrentEngine(magnetUrl)
    }

    private fun startTorrentEngine(magnetUrl: String) {
        Thread {
            val saveDir = File(cacheDir, "torrent_cache")
            deleteDirRecursive(saveDir)
            saveDir.mkdirs()

            val options = TorrentOptions.Builder()
                .saveLocation(saveDir)
                .removeFilesAfterStop(true)
                .prepareSize(15L * 1024L * 1024L)
                .build()

            ui.post {
                if (cancelled || isFinishing) return@post
                try {
                    val ts = TorrentStream.init(options)
                    torrentStream = ts
                    ts.addListener(this)
                    ts.startStream(magnetUrl)
                } catch (e: Exception) {
                    statusText.text = "Gagal memulai engine P2P"
                    detailText.text = e.message ?: "Error tidak diketahui"
                }
            }
        }.start()
    }

    override fun onStreamPrepared(torrent: Torrent?) {
        if (torrent == null) return
        val fileIdx = intent.getIntExtra("file_idx", -1)
        if (fileIdx >= 0) {
            try {
                torrent.setSelectedFileIndex(fileIdx)
            } catch (_: Exception) {
            }
        }
        ui.post {
            if (!cancelled && !isFinishing) {
                statusText.text = "Metadata torrent ditemukan, mulai buffering..."
            }
        }
    }

    override fun onStreamStarted(torrent: Torrent?) {
        ui.post {
            if (!cancelled && !isFinishing) {
                progressBar.isIndeterminate = false
                statusText.text = "Mengunduh buffer awal (0%)..."
            }
        }
    }

    override fun onStreamProgress(torrent: Torrent?, status: StreamStatus?) {
        if (status == null || cancelled || isFinishing) return
        val buf = status.bufferProgress.coerceIn(0, 100)
        val seeds = status.seeds
        val speedKb = status.downloadSpeed / 1024f
        val speedStr = if (speedKb >= 1024f) {
            String.format(Locale.US, "%.2f MB/s", speedKb / 1024f)
        } else {
            String.format(Locale.US, "%.0f KB/s", speedKb)
        }
        ui.post {
            if (!playerLaunched && !cancelled && !isFinishing) {
                progressBar.isIndeterminate = false
                progressBar.progress = buf
                statusText.text = "Buffering Awal: $buf%"
                detailText.text = "Seeder aktif: $seeds  |  Kecepatan: $speedStr"
            }
        }
    }

    override fun onStreamReady(torrent: Torrent?) {
        val videoFile = torrent?.videoFile ?: return
        if (playerLaunched || cancelled || isFinishing) return
        playerLaunched = true

        ui.post {
            val fileUri = Uri.fromFile(videoFile).toString()
            val playIntent = Intent(this, VodPlayerActivity::class.java).apply {
                putExtras(intent)
                putExtra("url", fileUri)
            }
            ContinueWatch.saveFromIntent(this, playIntent, 0L)
            playerLauncher.launch(playIntent)
        }
    }

    override fun onStreamError(torrent: Torrent?, e: Exception?) {
        ui.post {
            if (!cancelled && !isFinishing) {
                progressBar.isIndeterminate = false
                statusText.text = "Stream Torrent Gagal"
                detailText.text = e?.message ?: "Tidak ada seeder aktif. Coba pilih sumber lain di daftar."
            }
        }
    }

    override fun onStreamStopped() {
        // Ditangani oleh cleanupAndFinish
    }

    private fun cleanupAndFinish() {
        try {
            torrentStream?.removeListener(this)
            if (torrentStream?.isStreaming == true) {
                torrentStream?.stopStream()
            }
        } catch (_: Exception) {
        }
        Thread {
            deleteDirRecursive(File(cacheDir, "torrent_cache"))
        }.start()
        if (!isFinishing) {
            finish()
        }
    }

    private fun deleteDirRecursive(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteDirRecursive(child) else child.delete()
        }
        dir.delete()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelled = true
            cleanupAndFinish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            torrentStream?.removeListener(this)
            if (torrentStream?.isStreaming == true) {
                torrentStream?.stopStream()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
// END OF TorrentBufferActivity.kt
