package com.bayutv.hotel

import android.annotation.SuppressLint
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
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
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
    private lateinit var loadingSubText: TextView
    private lateinit var topBar: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var serverIdx = 0
    private var autoSwitches = 0
    private var handedOff = false

    private val adHosts = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "googletagmanager", "google-analytics",
        "popads", "popcash", "adsterra", "propellerads", "exoclick", "juicyads", "trafficjunky",
        "mgid", "taboola", "outbrain", "onclickalgo", "acscdn", "bet365", "1xbet", "histats",
        "mc.yandex", "adnxs", "rubiconproject", "pubmatic", "openx", "criteo", "smartadserver",
        "adskeeper", "bidvertiser", "hilltopads", "monetag", "richads", "evadav", "galaksion",
        "tsyndicate", "realsrv", "magsrv", "bidgear", "adform", "zedo", "revcontent"
    )

    private val allowedMainHosts = listOf(
        "vidlink", "vidsrc", "embed.su", "autoembed", "multiembed", "2embed",
        "smashystream", "moviesapi", "cloudflare", "rabbitstream", "megacloud",
        "upcloud", "dokicloud", "filemoon", "streamwish", "vidplay"
    )

    private fun servers(): List<Pair<String, String>> {
        val id = intent.getIntExtra("tmdb_id", 0)
        val kind = intent.getStringExtra("tmdb_kind") ?: "movie"
        val s = intent.getIntExtra("season", 1)
        val e = intent.getIntExtra("episode", 1)
        return if (kind == "tv") {
            listOf(
                Pair("Server 1 · VidLink (Sub Indo)", "https://vidlink.pro/tv/$id/$s/$e?primaryColor=B8975A&autoplay=true"),
                Pair("Server 2 · VidSrc CC (Sub Indo)", "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?sub_lang=id&ds_lang=id"),
                Pair("Server 3 · EmbedSu", "https://embed.su/embed/tv/$id/$s/$e"),
                Pair("Server 4 · AutoEmbed", "https://autoembed.cc/tv/tmdb/$id-$s-$e"),
                Pair("Server 5 · VidSrc ICU", "https://vidsrc.icu/embed/tv/$id/$s/$e?sub_lang=id"),
                Pair("Server 6 · MultiEmbed", "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e")
            )
        } else {
            listOf(
                Pair("Server 1 · VidLink (Sub Indo)", "https://vidlink.pro/movie/$id?primaryColor=B8975A&autoplay=true"),
                Pair("Server 2 · VidSrc CC (Sub Indo)", "https://vidsrc.cc/v2/embed/movie/$id?sub_lang=id&ds_lang=id"),
                Pair("Server 3 · EmbedSu", "https://embed.su/embed/movie/$id"),
                Pair("Server 4 · AutoEmbed", "https://autoembed.cc/movie/tmdb/$id"),
                Pair("Server 5 · VidSrc ICU", "https://vidsrc.icu/embed/movie/$id?sub_lang=id"),
                Pair("Server 6 · MultiEmbed", "https://multiembed.mov/?video_id=$id&tmdb=1")
            )
        }
    }

    private val autoSwitchOrReveal = Runnable {
        if (handedOff || isFinishing) return@Runnable
        val list = servers()
        if (autoSwitches < list.size - 1) {
            autoSwitches++
            loadServer(serverIdx + 1)
        } else {
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
        ui.postDelayed(hideTopBar, 6000L)
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
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
                if (adHosts.any { u.contains(it) }) return true
                if (request.isForMainFrame) {
                    val allowed = allowedMainHosts.any { u.contains(it) }
                    return !allowed
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (handedOff || isFinishing) return
                injectAdShieldAndAutoPlay(view)
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
                    val ua = request.requestHeaders["User-Agent"] ?: Playlist.DEFAULT_UA
                    val cookie = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (_: Exception) { "" }
                    ui.post { handoffToExoPlayer(url, ref, ua, cookie) }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF20B0A09.toInt())
            setPadding(Ui.dp(this@WebStreamActivity, 32), Ui.dp(this@WebStreamActivity, 24), Ui.dp(this@WebStreamActivity, 32), Ui.dp(this@WebStreamActivity, 24))
        }
        val brandTag = TextView(this).apply {
            text = "B-BOX STREAM ENGINE"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 11f))
            setTextColor(0xFFB8975A.toInt())
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Ui.dp(this@WebStreamActivity, 6))
        }
        val titleTv = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "Memuat Film"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 22f))
            setTextColor(0xFFEFE8DA.toInt())
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
        }
        loadingText = TextView(this).apply {
            text = "Menghubungkan ke server bebas iklan..."
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(0xFFE6C88F.toInt())
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@WebStreamActivity, 10), 0, 0)
        }
        loadingSubText = TextView(this).apply {
            text = "Mengekstrak video bersih ke pemutar native & mencari Subtitle Indonesia"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 12f))
            setTextColor(0xFFA79F90.toInt())
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@WebStreamActivity, 6), 0, 0)
        }
        loadingBox.addView(brandTag)
        loadingBox.addView(titleTv)
        loadingBox.addView(loadingText)
        loadingBox.addView(loadingSubText)
        root.addView(loadingBox, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xDD141311.toInt())
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
            setOnClickListener {
                autoSwitches = 0
                nextServer()
            }
        }
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(switchBtn)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        setContentView(root)
        loadServer(0)
    }

    private fun injectAdShieldAndAutoPlay(view: WebView?) {
        val js = """
            (function() {
                try {
                    window.open = function() { return null; };
                    var style = document.createElement('style');
                    style.innerHTML = 'div[style*="z-index: 2147483647"], div[style*="z-index:999999"], a[target="_blank"], ins, .ads, .ad-overlay { display:none !important; pointer-events:none !important; }';
                    if (document.head) document.head.appendChild(style);
                    function tryPlay() {
                        var selectors = [
                            'button[aria-label*="Play" i]',
                            '.jw-icon-display',
                            '.plyr__control--overlaid',
                            '.vjs-big-play-button',
                            '[class*="play-btn" i]',
                            '[id*="play" i]',
                            'video'
                        ];
                        for (var i = 0; i < selectors.length; i++) {
                            var el = document.querySelector(selectors[i]);
                            if (el) {
                                if (el.tagName === 'VIDEO' && el.paused) {
                                    el.muted = false;
                                    el.play().catch(function(){});
                                } else {
                                    el.click();
                                }
                            }
                        }
                    }
                    setTimeout(tryPlay, 600);
                    setTimeout(tryPlay, 1800);
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun isVideoStream(low: String): Boolean {
        val clean = low.substringBefore("?")
        if (clean.endsWith(".ts") || clean.endsWith(".m4s") || clean.endsWith(".vtt") ||
            clean.endsWith(".srt") || clean.endsWith(".js") || clean.endsWith(".css") ||
            clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".webp") ||
            clean.endsWith(".woff2")
        ) return false
        if (low.contains("trailer") || low.contains("preview") || low.contains("vast") ||
            low.contains("preroll") || low.contains("/ads/") || low.contains("analytics")
        ) return false
        return clean.endsWith(".m3u8") || low.contains(".m3u8?") || low.contains("/m3u8/") ||
            clean.endsWith("master.txt") || clean.endsWith("playlist.txt") ||
            clean.endsWith(".mp4") || low.contains(".mp4?") ||
            clean.endsWith(".mkv") || clean.endsWith(".webm")
    }

    private fun loadServer(idx: Int) {
        val list = servers()
        if (list.isEmpty()) return
        serverIdx = idx % list.size
        val srv = list[serverIdx]
        loadingBox.visibility = View.VISIBLE
        loadingText.text = "Menghubungkan ke ${srv.first} (${serverIdx + 1}/${list.size})..."
        ui.removeCallbacks(autoSwitchOrReveal)
        ui.postDelayed(autoSwitchOrReveal, 11000L)
        webView.loadUrl(srv.second)
    }

    private fun nextServer() {
        loadServer(serverIdx + 1)
    }

    private fun handoffToExoPlayer(streamUrl: String, referer: String, userAgent: String, cookie: String) {
        if (isFinishing) return
        ui.removeCallbacks(autoSwitchOrReveal)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } catch (e: Exception) {
        }
        val origin = try {
            val u = Uri.parse(referer)
            if (!u.scheme.isNullOrEmpty() && !u.host.isNullOrEmpty()) "${u.scheme}://${u.host}" else ""
        } catch (_: Exception) {
            ""
        }
        val i = Intent(this, VodPlayerActivity::class.java)
        i.putExtra("title", intent.getStringExtra("title") ?: "")
        i.putExtra("url", streamUrl)
        i.putExtra("referer", referer)
        i.putExtra("origin", origin)
        i.putExtra("cookie", cookie)
        i.putExtra("user_agent", userAgent)
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
