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
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
    private lateinit var statusTitle: TextView
    private lateinit var loadingText: TextView
    private lateinit var actionRow: LinearLayout
    private lateinit var topBar: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private var serverIdx = 0
    private var handedOff = false
    private var manualWebMode = false

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()

    private val adHosts = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "googletagmanager",
        "google-analytics", "popads", "popcash", "adsterra", "propellerads",
        "exoclick", "juicyads", "trafficjunky", "mgid", "taboola", "outbrain",
        "onclickalgo", "acscdn", "bet365", "1xbet", "22bet", "melbet", "parimatch",
        "adnxs", "rubiconproject", "pubmatic", "openx", "criteo", "smartadserver",
        "histats", "yandex.ru/metrika", "mc.yandex", "scorecardresearch", "quantserve",
        "adform", "adroll", "bidswitch", "casalemedia", "contextweb", "dotomi",
        "everesttech", "lijit", "mathtag", "moatads", "rlcdn", "sharethrough",
        "spotxchange", "tapad", "tremorhub", "turn.com", "undertone", "vibrantmedia",
        "yieldmo", "zedo", "adcash", "hilltopads", "monetag", "richpush", "galaksion",
        "evadav", "clickadu", "tsyndicate", "realsrv", "magsrv", "bmcdn", "profitablegate"
    )

    private val ignoreVideoKeywords = listOf(
        "trailer", "preview", "teaser", "blank", "silent", "empty", "dummy",
        "1sec", "pixel", "preroll", "vast", "doubleclick", "/ads/", "ads.",
        "promo", "sponsor", "banner", "sample"
    )

    private fun servers(): List<Pair<String, String>> {
        val id = intent.getIntExtra("tmdb_id", 0)
        val kind = intent.getStringExtra("tmdb_kind") ?: "movie"
        val s = intent.getIntExtra("season", 1)
        val e = intent.getIntExtra("episode", 1)
        return if (kind == "tv") {
            listOf(
                Pair("Server 1 (VidSrc CC)", "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?sub_lang=id&ds_lang=id"),
                Pair("Server 2 (VidSrc ICU)", "https://vidsrc.icu/embed/tv/$id/$s/$e?sub_lang=id"),
                Pair("Server 3 (EmbedSU)", "https://embed.su/embed/tv/$id/$s/$e"),
                Pair("Server 4 (AutoEmbed)", "https://autoembed.co/tv/tmdb/$id-$s-$e"),
                Pair("Server 5 (MultiEmbed)", "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e")
            )
        } else {
            listOf(
                Pair("Server 1 (VidSrc CC)", "https://vidsrc.cc/v2/embed/movie/$id?sub_lang=id&ds_lang=id"),
                Pair("Server 2 (VidSrc ICU)", "https://vidsrc.icu/embed/movie/$id?sub_lang=id"),
                Pair("Server 3 (EmbedSU)", "https://embed.su/embed/movie/$id"),
                Pair("Server 4 (AutoEmbed)", "https://autoembed.co/movie/tmdb/$id"),
                Pair("Server 5 (MultiEmbed)", "https://multiembed.mov/?video_id=$id&tmdb=1")
            )
        }
    }

    private val autoPlayJsLoop = object : Runnable {
        override fun run() {
            if (handedOff || isFinishing) return
            injectCleanAutoPlay()
            ui.postDelayed(this, 900L)
        }
    }

    private val serverTimeout = Runnable {
        if (handedOff || manualWebMode || isFinishing) return@Runnable
        val list = servers()
        if (serverIdx + 1 < list.size) {
            loadServer(serverIdx + 1)
        } else {
            showFallbackOptions()
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

    private fun dp(v: Int): Int = Ui.dp(this, v)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContinueWatch.saveFromIntent(this, intent, 0L)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0A09.toInt())

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
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
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = false

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                result?.cancel()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString()?.lowercase() ?: return true
                if (!u.startsWith("http://") && !u.startsWith("https://")) return true
                if (adHosts.any { u.contains(it) }) return true
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!handedOff) injectCleanAutoPlay()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val url = uri.toString()
                val low = url.lowercase()

                if (adHosts.any { low.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                if (!handedOff && isValidVideoStream(low)) {
                    handedOff = true
                    val reqRef = request.requestHeaders["Referer"] ?: ""
                    val pageUrl = try { view?.url ?: "" } catch (_: Exception) { "" }
                    val finalRef = reqRef.ifEmpty { pageUrl }.ifEmpty { servers().getOrNull(serverIdx)?.second ?: "" }
                    val reqOrigin = request.requestHeaders["Origin"] ?: deriveOrigin(finalRef)
                    ui.post { handoffToExoPlayer(url, finalRef, reqOrigin) }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF80B0A09.toInt())
            setPadding(dp(40), dp(28), dp(40), dp(28))
        }

        loadingBox.addView(buildBBoxBadge(), LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = dp(18)
        })

        val titleTv = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "Memuat Film"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 24f))
            setTextColor(ivory)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusTitle = TextView(this).apply {
            text = "MODE BEBAS IKLAN - EKSTRAKSI STREAM"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 11f))
            setTextColor(brass)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        loadingText = TextView(this).apply {
            text = "Menghubungkan ke server & mencari Subtitle Indonesia..."
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(brassLight)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(22))
        }

        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        loadingBox.addView(titleTv)
        loadingBox.addView(statusTitle)
        loadingBox.addView(loadingText)
        loadingBox.addView(actionRow)
        root.addView(loadingBox, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xDD141311.toInt())
            setPadding(dp(24), dp(12), dp(24), dp(12))
            visibility = View.GONE
        }
        val barTitle = TextView(this).apply {
            text = "B-BOX  |  " + (intent.getStringExtra("title") ?: "") + "  |  Tekan MENU untuk ganti server"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(ivory)
        }
        val switchBtn = makeActionButton("Ganti Server", true) {
            manualWebMode = false
            webView.alpha = 0f
            topBar.visibility = View.GONE
            loadServer(serverIdx + 1)
        }
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(switchBtn)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        setContentView(root)
        renderScanningActions()
        loadServer(0)
    }

    private fun buildBBoxBadge(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22B8975A)
                setStroke(dp(1), brass)
            }
        }
        val iconFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(brassLight, brass)
            ).apply {
                cornerRadius = dp(6).toFloat()
            }
        }
        val iconLetter = TextView(this).apply {
            text = "B"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 14f))
            setTextColor(0xFF0E0C0A.toInt())
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        iconFrame.addView(iconLetter, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        box.addView(iconFrame, LinearLayout.LayoutParams(dp(24), dp(24)))

        val brand = TextView(this).apply {
            text = "B-BOX"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 16f))
            setTextColor(brassLight)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.16f
            setPadding(dp(9), 0, 0, 0)
        }
        box.addView(brand)
        return box
    }

    private fun makeActionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@WebStreamActivity, 13.5f))
            setTextColor(if (primary) Color.BLACK else ivory)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (primary) brass else 0x26FFFFFF)
                if (!primary) setStroke(dp(1), 0x55FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                setTextColor(if (f || primary) Color.BLACK else ivory)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    if (f) {
                        setColor(brassLight)
                        setStroke(dp(2), Color.WHITE)
                    } else if (primary) {
                        setColor(brass)
                    } else {
                        setColor(0x26FFFFFF)
                        setStroke(dp(1), 0x55FFFFFF)
                    }
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun renderScanningActions() {
        actionRow.removeAllViews()
        val nextBtn = makeActionButton("Ganti Server Berikutnya", true) {
            loadServer(serverIdx + 1)
        }
        val cancelBtn = makeActionButton("Batal", false) {
            finish()
        }
        actionRow.addView(nextBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        actionRow.addView(cancelBtn, LinearLayout.LayoutParams(-2, -2))
        nextBtn.requestFocus()
    }

    private fun showFallbackOptions() {
        statusTitle.text = "STREAM LANGSUNG BELUM MERESPONS"
        loadingText.text = "Semua server telah dicoba. Anda dapat mengulang pencarian atau membuka mode web terfilter."
        actionRow.removeAllViews()

        val retryBtn = makeActionButton("Coba Ulang Semua Server", true) {
            renderScanningActions()
            loadServer(0)
        }
        val webBtn = makeActionButton("Putar di Web (Blokir Iklan)", false) {
            manualWebMode = true
            loadingBox.visibility = View.GONE
            webView.alpha = 1f
            showTopBar()
        }
        val backBtn = makeActionButton("Kembali", false) { finish() }

        actionRow.addView(retryBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        actionRow.addView(webBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        actionRow.addView(backBtn, LinearLayout.LayoutParams(-2, -2))
        retryBtn.requestFocus()
    }

    private fun injectCleanAutoPlay() {
        val js = """
            (function() {
                try {
                    window.open = function() { return null; };
                    var divs = document.querySelectorAll('div, section, aside');
                    for (var i = 0; i < divs.length; i++) {
                        var el = divs[i];
                        var st = window.getComputedStyle(el);
                        if ((st.position === 'fixed' || st.position === 'absolute') && parseInt(st.zIndex || '0') > 999) {
                            if (!el.querySelector('video') && el.offsetWidth > window.innerWidth * 0.6) {
                                el.style.display = 'none';
                            }
                        }
                    }
                    var vids = document.querySelectorAll('video');
                    for (var v = 0; v < vids.length; v++) {
                        vids[v].muted = true;
                        if (vids[v].paused) { vids[v].play().catch(function(){}); }
                    }
                    var selectors = [
                        '#pl_but', '.play-btn', '.btn-play', '[aria-label*="Play"]',
                        '[class*="play"]', '[id*="play"]', '.jw-icon-display',
                        '.plyr__control--overlaid', '.vjs-big-play-button', 'button'
                    ];
                    for (var s = 0; s < selectors.length; s++) {
                        var btns = document.querySelectorAll(selectors[s]);
                        for (var b = 0; b < btns.length; b++) {
                            var btn = btns[b];
                            if (btn && btn.offsetParent !== null) {
                                btn.click();
                            }
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    private fun isValidVideoStream(low: String): Boolean {
        if (ignoreVideoKeywords.any { low.contains(it) }) return false
        if (low.endsWith(".js") || low.endsWith(".css") || low.endsWith(".vtt") || low.endsWith(".srt")) return false
        val hasHls = low.contains(".m3u8") || low.contains("format=m3u8") || (low.contains("/hls/") && low.contains("master"))
        val hasMp4 = low.contains(".mp4") && !low.contains(".mp4.js")
        return hasHls || hasMp4
    }

    private fun deriveOrigin(refUrl: String): String {
        return try {
            val u = Uri.parse(refUrl)
            if (!u.scheme.isNullOrEmpty() && !u.host.isNullOrEmpty()) "${u.scheme}://${u.host}" else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadServer(idx: Int) {
        val list = servers()
        if (list.isEmpty()) return
        serverIdx = idx % list.size
        val srv = list[serverIdx]

        loadingBox.visibility = View.VISIBLE
        statusTitle.text = "B-BOX STREAM EXTRACTOR - ${serverIdx + 1}/${list.size}"
        loadingText.text = "Menghubungkan ke ${srv.first} & menyiapkan Subtitle Indonesia..."

        ui.removeCallbacks(serverTimeout)
        ui.postDelayed(serverTimeout, 8500L)

        ui.removeCallbacks(autoPlayJsLoop)
        ui.postDelayed(autoPlayJsLoop, 1200L)

        webView.loadUrl(srv.second)
    }

    private fun handoffToExoPlayer(streamUrl: String, referer: String, origin: String) {
        if (isFinishing) return
        ui.removeCallbacks(serverTimeout)
        ui.removeCallbacks(autoPlayJsLoop)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } catch (_: Exception) {
        }
        val i = Intent(this, VodPlayerActivity::class.java)
        i.putExtra("title", intent.getStringExtra("title") ?: "")
        i.putExtra("url", streamUrl)
        i.putExtra("referer", referer)
        i.putExtra("origin", origin)
        i.putExtra("user_agent", Playlist.DEFAULT_UA)
        i.putExtra("resume_key", intent.getStringExtra("resume_key") ?: "")
        i.putExtra("tmdb_id", intent.getIntExtra("tmdb_id", 0))
        i.putExtra("imdb_id", intent.getStringExtra("imdb_id") ?: "")
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
        if (manualWebMode && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
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
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
