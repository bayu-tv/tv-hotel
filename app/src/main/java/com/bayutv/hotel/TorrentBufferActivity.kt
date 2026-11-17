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
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.Locale

class TorrentBufferActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()
    private val mute = 0xFFB5AEA0.toInt()
    private val bgDark = 0xFF0B0A09.toInt()

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var sessionManager: SessionManager? = null
    @Volatile private var metadataReady = false
    @Volatile private var playerLaunched = false
    @Volatile private var cancelled = false
    private var waitSeconds = 0

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        cleanupAndFinish()
    }

    private val waitTicker = object : Runnable {
        override fun run() {
            if (cancelled || isFinishing || metadataReady || playerLaunched) return
            waitSeconds++
            val nodes = try {
                sessionManager?.dhtNodes() ?: 0L
            } catch (_: Throwable) {
                0L
            }
            statusText.text = "Menghubungkan ke jaringan seeder... (${waitSeconds}d)"
            detailText.text = "Node DHT aktif: $nodes  |  Mengambil metadata magnet..."
            ui.postDelayed(this, 1000L)
        }
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
            text = "B-BOX P2P ENGINE - LIBTORRENT4J 2.1"
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
            text = "Menginisialisasi DHT & tracker modern..."
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

        ui.postDelayed(waitTicker, 1000L)
        startModernTorrentStream(magnetUrl)
    }

    private fun startModernTorrentStream(magnetUrl: String) {
        Thread {
            try {
                val saveDir = File(cacheDir, "torrent_cache")
                deleteDirRecursive(saveDir)
                saveDir.mkdirs()

                val sp = SettingsPack().apply {
                    setString(
                        settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                        "router.bittorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401,router.silotis.us:6881"
                    )
                    setString(
                        settings_pack.string_types.listen_interfaces.swigValue(),
                        "0.0.0.0:0,[::]:0"
                    )
                    setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                    setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
                    setInt(settings_pack.int_types.connections_limit.swigValue(), 120)
                }

                val sm = SessionManager()
                sm.start(SessionParams(sp))
                sessionManager = sm

                val metadataBytes = sm.fetchMagnet(magnetUrl, 45, saveDir)
                if (cancelled || isFinishing) return@Thread

                if (metadataBytes == null || metadataBytes.isEmpty()) {
                    ui.post {
                        if (!cancelled && !isFinishing) {
                            progressBar.isIndeterminate = false
                            statusText.text = "Metadata Tidak Ditemukan (Timeout)"
                            detailText.text = "Seeder tidak merespons dalam 45 detik. Coba pilih sumber urutan lain."
                        }
                    }
                    return@Thread
                }

                metadataReady = true
                val ti = TorrentInfo(metadataBytes)
                val selectedIdx = pickVideoFileIndex(ti, intent.getIntExtra("file_idx", -1))

                ui.post {
                    if (!cancelled && !isFinishing) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = 2
                        statusText.text = "Metadata ditemukan, menyiapkan buffer video..."
                    }
                }

                sm.download(ti, saveDir)
                var handle: TorrentHandle? = null
                for (attempt in 0 until 20) {
                    if (cancelled || isFinishing) return@Thread
                    handle = sm.find(ti.infoHash())
                    if (handle != null && handle.isValid) break
                    Thread.sleep(200L)
                }

                val th = handle ?: return@Thread
                th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

                for (i in 0 until ti.numFiles()) {
                    th.filePriority(i, if (i == selectedIdx) Priority.DEFAULT else Priority.IGNORE)
                }

                val files = ti.files()
                val fileSize = files.fileSize(selectedIdx).coerceAtLeast(1L)
                val firstPiece = ti.mapFile(selectedIdx, 0L, 1).piece()
                val lastPiece = ti.mapFile(selectedIdx, (fileSize - 1L).coerceAtLeast(0L), 1).piece()
                val pieceLen = ti.pieceLength().coerceAtLeast(16384)
                val headPieces = ((14L * 1024L * 1024L) / pieceLen.toLong()).toInt().coerceIn(2, 40)
                val headEnd = (firstPiece + headPieces).coerceAtMost(lastPiece)

                val criticalPieces = LinkedHashSet<Int>()
                for (p in firstPiece..headEnd) {
                    criticalPieces.add(p)
                }
                if (lastPiece > firstPiece) {
                    criticalPieces.add((lastPiece - 1).coerceAtLeast(firstPiece))
                    criticalPieces.add(lastPiece)
                }

                for (p in criticalPieces) {
                    th.piecePriority(p, Priority.TOP_PRIORITY)
                    th.setPieceDeadline(p, 1000)
                }
                th.resume()

                while (!cancelled && !isFinishing && !playerLaunched) {
                    var readyCount = 0
                    for (p in criticalPieces) {
                        if (th.havePiece(p)) {
                            readyCount++
                        } else {
                            th.piecePriority(p, Priority.TOP_PRIORITY)
                            th.setPieceDeadline(p, 1000)
                        }
                    }

                    val bufPct = ((readyCount * 100) / criticalPieces.size.coerceAtLeast(1)).coerceIn(0, 100)
                    val st = th.status()
                    val seeds = st.numSeeds()
                    val peers = st.numPeers()
                    val speedKb = st.downloadPayloadRate() / 1024f
                    val speedStr = if (speedKb >= 1024f) {
                        String.format(Locale.US, "%.2f MB/s", speedKb / 1024f)
                    } else {
                        String.format(Locale.US, "%.0f KB/s", speedKb)
                    }

                    ui.post {
                        if (!cancelled && !isFinishing && !playerLaunched) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = bufPct
                            statusText.text = "Buffering Awal: $bufPct%"
                            detailText.text = "Seeder aktif: $seeds ($peers peer)  |  Kecepatan: $speedStr"
                        }
                    }

                    if (readyCount >= criticalPieces.size) {
                        val videoFile = File(saveDir, files.filePath(selectedIdx))
                        if (videoFile.exists() && videoFile.length() > 0L) {
                            playerLaunched = true
                            ui.post {
                                if (!cancelled && !isFinishing) {
                                    val fileUri = Uri.fromFile(videoFile).toString()
                                    val playIntent = Intent(this, VodPlayerActivity::class.java).apply {
                                        putExtras(intent)
                                        putExtra("url", fileUri)
                                    }
                                    ContinueWatch.saveFromIntent(this, playIntent, 0L)
                                    playerLauncher.launch(playIntent)
                                }
                            }
                            break
                        }
                    }
                    Thread.sleep(500L)
                }
            } catch (e: Throwable) {
                ui.post {
                    if (!cancelled && !isFinishing) {
                        progressBar.isIndeterminate = false
                        statusText.text = "Gagal Memulai Stream P2P"
                        detailText.text = e.message ?: "Error tidak diketahui"
                    }
                }
            }
        }.start()
    }

    private fun pickVideoFileIndex(ti: TorrentInfo, requestedIdx: Int): Int {
        val total = ti.numFiles()
        if (requestedIdx in 0 until total) return requestedIdx
        val files = ti.files()
        var bestVideoIdx = -1
        var bestVideoSize = -1L
        var largestIdx = 0
        var largestSize = -1L

        for (i in 0 until total) {
            val sz = files.fileSize(i)
            val name = files.filePath(i).lowercase(Locale.US)
            if (sz > largestSize) {
                largestSize = sz
                largestIdx = i
            }
            val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") ||
                name.endsWith(".avi") || name.endsWith(".webm") || name.endsWith(".mov")
            if (isVideo && sz > bestVideoSize) {
                bestVideoSize = sz
                bestVideoIdx = i
            }
        }
        return if (bestVideoIdx >= 0) bestVideoIdx else largestIdx
    }

    private fun cleanupAndFinish() {
        ui.removeCallbacks(waitTicker)
        val sm = sessionManager
        sessionManager = null
        Thread {
            try {
                sm?.stop()
            } catch (_: Throwable) {
            }
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
        val sm = sessionManager
        sessionManager = null
        Thread {
            try {
                sm?.stop()
            } catch (_: Throwable) {
            }
        }.start()
        super.onDestroy()
    }
}
// END OF TorrentBufferActivity.kt
