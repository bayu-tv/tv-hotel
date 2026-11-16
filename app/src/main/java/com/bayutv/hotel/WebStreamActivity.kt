package com.bayutv.hotel

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

class WebStreamActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var topBar: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var serverIdx = 0
    private var handedOff = false

    private val adHosts = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "popads", "popcash",
        "adsterra", "propellerads", "exoclick", "juicyads", "trafficjunky",
        "mgid", "taboola", "outbrain", "onclickalgo", "acscdn", "bet365", "1xbet"
    )

    private fun servers(): List<Pair<String, String>> {
        val id = intent.getIntExtra("tmdb_id", 0)
        val kind = intent.getStringExtra("tmdb_kind") ?: "movie"
        val s = intent.getIntExtra("season", 1)
        val e = intent.getIntExtra("episode", 1)
        return if (kind == "tv") {
            listOf(
                Pair("Server 1 (Sub Indo)", "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?sub_lang=id&ds_lang=id"),
                Pair("Server 2", "https://vidsrc.icu/embed/tv/$id/$s/$e?sub_lang=id"),
                Pair("Server 3", "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e")
            )
        } else {
            listOf(
                Pair("Server 1 (Sub Indo)", "https://vidsrc.cc/v2/embed/movie/$id?sub_lang=id&ds_lang=id"),
                Pair("Server 2", "https://vidsrc.icu/embed/movie/$id?sub_lang=id"),
                Pair("Server 3", "https://multiembed.mov/?video_id=$id&tmdb=1")
            )
        }
    }

    private val revealWeb = Runnable {
        if (!handedOff && !isFinishing) {
            loadingBox.visibility = View.GONE
            showTopBar()
        }
    }

    private val hideTopBar = Runnable {
        if (::topBar.isInitialized) topBar.visibility = View.GONE
    }

    private fun showTopBar() {
        topBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideTopBar)
        ui.postDelayed(hideTopBar, 5000L)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Catat ke riwayat Lanjutkan Menonton
        ContinueWatch.saveFromIntent(this, intent, 0L)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            userAgentString = Playlist.DEFAULT_UA
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString()?.lowercase() ?: return true
                val allowed = u.contains("vidsrc") || u.contains("multiembed") || u.contains("embed") || u.contains("cloudflare")
                return !allowed
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val url = uri.toString()
                val low = url.lowercase()

                if (adHosts.any { low.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                if (!handedOff && isVideoStream(low)) {
                    handedOff = true
                    val ref = request.requestHeaders["Referer"] ?: webView.url ?: ""
                    ui.post { handoffToExoPlayer(url, ref) }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE0D0C0B.toInt())
            setPadding(Ui.dp(this@WebStreamActivity, 32), Ui.dp(this@WebStreamActivity, 24), Ui.dp(this@WebStreamActivity, 32), Ui.dp(this@WebStreamActivity, 24))
        }
        val titleTv = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "Memuat Film"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 22f))
            setTextColor(0xFFEFE8DA.toInt())
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
        }
        loadingText = TextView(this).apply {
            text = "Menghubungkan ke server & mencari Sub Indo..."
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(0xFFE6C88F.toInt())
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@WebStreamActivity, 10), 0, 0)
        }
        loadingBox.addView(titleTv)
        loadingBox.addView(loadingText)
        root.addView(loadingBox, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC141311.toInt())
            setPadding(Ui.dp(this@WebStreamActivity, 24), Ui.dp(this@WebStreamActivity, 12), Ui.dp(this@WebStreamActivity, 24), Ui.dp(this@WebStreamActivity, 12))
            visibility = View.GONE
        }
        val barTitle = TextView(this).apply {
            text = (intent.getStringExtra("title") ?: "") + "  ·  Tekan MENU untuk ganti server"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(0xFFEFE8DA.toInt())
        }
        val switchBtn = TextView(this).apply {
            text = "Ganti Server"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(0xFFE6C88F.toInt())
            setPadding(Ui.dp(this@WebStreamActivity, 16), Ui.dp(this@WebStreamActivity, 8), Ui.dp(this@WebStreamActivity, 16), Ui.dp(this@WebStreamActivity, 8))
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@WebStreamActivity, 8).toFloat()
                setColor(0x33B8975A)
                setStroke(Ui.dp(this@WebStreamActivity, 1), 0xFFB8975A.toInt())
            }
            isFocusable = true
            setOnClickListener { nextServer() }
        }
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(switchBtn)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        setContentView(root)
        loadServer(0)
    }

    private fun isVideoStream(low: String): Boolean {
        if (low.contains("trailer") || low.contains("preview") || low.contains("ads")) return false
        return low.contains(".m3u8") || (low.contains(".mp4") && !low.contains(".js"))
    }

    private fun loadServer(idx: Int) {
        val list = servers()
        if (list.isEmpty()) return
        serverIdx = idx % list.size
        val srv = list[serverIdx]
        loadingBox.visibility = View.VISIBLE
        loadingText.text = "Menghubungkan ke ${srv.first}..."
        ui.removeCallbacks(revealWeb)
        ui.postDelayed(revealWeb, 9000L)
        webView.loadUrl(srv.second)
    }

    private fun nextServer() {
        loadServer(serverIdx + 1)
    }

    private fun handoffToExoPlayer(streamUrl: String, referer: String) {
        if (isFinishing) return
        ui.removeCallbacks(revealWeb)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } catch (e: Exception) {
        }
        val i = Intent(this, VodPlayerActivity::class.java)
        i.putExtra("title", intent.getStringExtra("title") ?: "")
        i.putExtra("url", streamUrl)
        i.putExtra("referer", referer)
        i.putExtra("user_agent", Playlist.DEFAULT_UA)
        i.putExtra("resume_key", intent.getStringExtra("resume_key") ?: "")
        i.putExtra("tmdb_id", intent.getIntExtra("tmdb_id", 0))
        i.putExtra("tmdb_kind", intent.getStringExtra("tmdb_kind") ?: "movie")
        i.putExtra("season", intent.getIntExtra("season", 0))
        i.putExtra("episode", intent.getIntExtra("episode", 0))
        i.putExtra("year", intent.getStringExtra("year") ?: "")
        i.putExtra("poster", intent.getStringExtra("poster") ?: "")
        i.putExtra("backdrop", intent.getStringExtra("backdrop") ?: "")
        i.putExtra("rating", intent.getDoubleExtra("rating", 0.0))
        i.putExtra("overview", intent.getStringExtra("overview") ?: "")
        startActivity(i)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            showTopBar()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
