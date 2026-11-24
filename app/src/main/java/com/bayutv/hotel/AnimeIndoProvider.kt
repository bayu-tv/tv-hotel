package com.bayutv.hotel

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object AnimeIndoProvider {

    private const val PREF_NAME = "bayutv"
    private const val KEY_BASE_URL = "anime_indo_base_url"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val DEFAULT_MIRRORS = listOf(
        "https://otakudesu.cloud",
        "https://otakudesu.best",
        "https://otakudesu.cam",
        "https://otakudesu.lol"
    )

    private val dohIpCache = ConcurrentHashMap<String, String>()

    data class AnimeItem(
        val title: String,
        val url: String,
        val poster: String,
        val badge: String,
        val status: String
    )

    data class EpisodeItem(
        val title: String,
        val url: String,
        val episodeNumber: Int
    )

    data class AnimeDetail(
        val title: String,
        val url: String,
        val poster: String,
        val score: String,
        val status: String,
        val genres: String,
        val synopsis: String,
        val episodes: List<EpisodeItem>
    )

    data class StreamOption(
        val label: String,
        val videoUrl: String,
        val referer: String,
        val isDirectMedia: Boolean
    )

    fun getBaseUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "")?.trim().orEmpty()
        return if (saved.startsWith("http")) saved.trimEnd('/') else DEFAULT_MIRRORS[0]
    }

    fun saveBaseUrl(context: Context, rawUrl: String) {
        val clean = rawUrl.trim().trimEnd('/')
        dohIpCache.clear()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, clean)
            .apply()
    }

    fun showDomainDialog(context: Context, onSaved: (String) -> Unit) {
        val current = getBaseUrl(context)
        val input = EditText(context).apply {
            setText(current)
            hint = "https://otakudesu.cloud"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setPadding(40, 28, 40, 28)
        }
        AlertDialog.Builder(context)
            .setTitle("Domain Sumber Anime Sub-Indo")
            .setMessage("Ubah alamat domain jika situs utama berganti TLD (misal .cloud / .best / .cam):")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val v = input.text.toString().trim().trimEnd('/')
                if (v.startsWith("http")) {
                    saveBaseUrl(context, v)
                    onSaved(v)
                }
            }
            .setNeutralButton("Reset Default") { _, _ ->
                saveBaseUrl(context, "")
                onSaved(getBaseUrl(context))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun isValidAnimeHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val low = html.lowercase(Locale.US)
        if (low.contains("internetpositif") || low.contains("uzone.id") || low.contains("trustpositif") || low.contains("blokir")) {
            return false
        }
        return html.contains("venz") || html.contains("episodelist") || html.contains("chivsrc")
    }

    private fun fetchHtmlWithMirrorFallback(context: Context, relativePath: String): Pair<String, String>? {
        val primary = getBaseUrl(context)
        val candidates = (listOf(primary) + DEFAULT_MIRRORS).distinct()
        for (base in candidates) {
            val fullUrl = if (relativePath.startsWith("http")) relativePath else "$base$relativePath"
            val html = smartHttpGet(fullUrl, base)
            if (isValidAnimeHtml(html)) {
                if (base != primary && !relativePath.startsWith("http")) {
                    saveBaseUrl(context, base)
                }
                return Pair(base, html!!)
            }
        }
        return null
    }

    fun fetchOngoing(context: Context): List<AnimeItem> {
        val res = fetchHtmlWithMirrorFallback(context, "/ongoing-anime/") ?: return emptyList()
        return parseAnimeCards(res.second, res.first, "Ongoing")
    }

    fun fetchComplete(context: Context): List<AnimeItem> {
        val res = fetchHtmlWithMirrorFallback(context, "/complete-anime/") ?: return emptyList()
        return parseAnimeCards(res.second, res.first, "Tamat")
    }

    fun search(context: Context, query: String): List<AnimeItem> {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        val res = fetchHtmlWithMirrorFallback(context, "/?s=$enc&post_type=anime") ?: return emptyList()
        val searchCards = parseSearchCards(res.second, res.first)
        return searchCards.ifEmpty { parseAnimeCards(res.second, res.first, "Sub Indo") }
    }

    private fun parseAnimeCards(html: String, baseUrl: String, defaultStatus: String): List<AnimeItem> {
        val out = ArrayList<AnimeItem>()
        val seen = HashSet<String>()
        val hrefRegex = Regex("<a[^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<h2[^>]*class=[\"'][^\"']*jdlflm[^\"']*[\"'][^>]*>(.*?)</h2>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val imgRegex = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val epRegex = Regex("<div[^>]*class=[\"'][^\"']*epz[^\"']*[\"'][^>]*>(.*?)</div>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val chunks = html.split(Regex("<div[^>]*class=[\"'][^\"']*detpost[^\"']*[\"'][^>]*>", RegexOption.IGNORE_CASE))
        for (i in 1 until chunks.size) {
            val block = chunks[i].substringBefore("</li>")
            val href = normalizeUrl(hrefRegex.find(block)?.groupValues?.getOrNull(1).orEmpty(), baseUrl)
            val rawTitle = stripTags(titleRegex.find(block)?.groupValues?.getOrNull(1).orEmpty())
            val poster = normalizeUrl(imgRegex.find(block)?.groupValues?.getOrNull(1).orEmpty(), baseUrl)
            val badge = stripTags(epRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()).ifEmpty { defaultStatus }

            if (href.isNotEmpty() && rawTitle.isNotEmpty() && seen.add(href)) {
                out.add(AnimeItem(rawTitle, href, poster, badge, defaultStatus))
            }
        }
        return out
    }

    private fun parseSearchCards(html: String, baseUrl: String): List<AnimeItem> {
        val out = ArrayList<AnimeItem>()
        val seen = HashSet<String>()
        val liRegex = Regex("<li[^>]*>\\s*<img[^>]+src=\"([^\"]+)\"[^>]*>.*?<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>\\s*</h2>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val statusRegex = Regex("<b>Status</b>\\s*:\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val ratingRegex = Regex("<b>Rating</b>\\s*:\\s*([^<]+)", RegexOption.IGNORE_CASE)

        for (m in liRegex.findAll(html)) {
            val poster = normalizeUrl(m.groupValues[1], baseUrl)
            val href = normalizeUrl(m.groupValues[2], baseUrl)
            val title = stripTags(m.groupValues[3])
            val meta = m.groupValues[4]
            val status = stripTags(statusRegex.find(meta)?.groupValues?.getOrNull(1).orEmpty()).ifEmpty { "Sub Indo" }
            val rating = stripTags(ratingRegex.find(meta)?.groupValues?.getOrNull(1).orEmpty())
            val badge = if (rating.isNotEmpty() && rating != "N/A") "\u2605 $rating" else status

            if (href.isNotEmpty() && title.isNotEmpty() && seen.add(href)) {
                out.add(AnimeItem(title, href, poster, badge, status))
            }
        }
        return out
    }

    fun fetchDetail(context: Context, item: AnimeItem): AnimeDetail? {
        val baseUrl = getBaseUrl(context)
        val targetUrl = rewriteToActiveMirror(item.url, baseUrl)
        val html = smartHttpGet(targetUrl, baseUrl) ?: return null

        val synopsisRaw = Regex("<div class=\"sinopc\">(.*?)</div>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.getOrNull(1).orEmpty()
        val synopsis = stripTags(synopsisRaw).ifEmpty {
            "Anime Subtitle Indonesia (${item.status}). Pilih episode untuk langsung menonton tanpa iklan."
        }

        val score = stripTags(Regex("<span><b>Skor</b>\\s*:([^<]+)</span>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1).orEmpty())
        val status = stripTags(Regex("<span><b>Status</b>\\s*:([^<]+)</span>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1).orEmpty()).ifEmpty { item.status }
        val genres = stripTags(Regex("<span><b>Genre</b>\\s*:([^<]+)</span>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1).orEmpty())

        val episodes = ArrayList<EpisodeItem>()
        val epListBlocks = Regex("<div class=\"episodelist\">(.*?)</div>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)
        val linkRegex = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val numRegex = Regex("Episode\\s+(\\d+)", RegexOption.IGNORE_CASE)

        for (block in epListBlocks) {
            val content = block.groupValues[1]
            if (content.contains("Batch", ignoreCase = true) && !content.contains("Episode 1", ignoreCase = true)) continue
            for (lm in linkRegex.findAll(content)) {
                val epUrl = normalizeUrl(lm.groupValues[1], baseUrl)
                val epTitle = stripTags(lm.groupValues[2])
                if (epUrl.isEmpty() || epTitle.isEmpty()) continue
                if (epTitle.contains("Batch", ignoreCase = true)) continue
                val epNum = numRegex.find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (episodes.size + 1)
                if (episodes.none { it.url == epUrl }) {
                    episodes.add(EpisodeItem(epTitle, epUrl, epNum))
                }
            }
        }

        val ordered = if (episodes.size >= 2 && episodes.first().episodeNumber > episodes.last().episodeNumber) {
            episodes.reversed()
        } else {
            episodes
        }

        return AnimeDetail(
            title = item.title,
            url = targetUrl,
            poster = item.poster,
            score = score,
            status = status,
            genres = genres,
            synopsis = synopsis,
            episodes = ordered
        )
    }

    fun resolveEpisodeStreams(context: Context, episodeUrl: String): List<StreamOption> {
        val baseUrl = getBaseUrl(context)
        val activeEpUrl = rewriteToActiveMirror(episodeUrl, baseUrl)
        val html = smartHttpGet(activeEpUrl, baseUrl) ?: return emptyList()
        val results = ArrayList<StreamOption>()
        val seenUrls = HashSet<String>()

        val defaultIframe = Regex("<div class=\"responsive-embed-stream\">.*?<iframe[^>]+src=\"([^\"]+)\"", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("<iframe[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)

        if (!defaultIframe.isNullOrBlank()) {
            val cleanEmbed = normalizeUrl(defaultIframe, baseUrl)
            val resolved = extractDirectVideo(cleanEmbed, activeEpUrl)
            if (resolved != null && seenUrls.add(resolved.videoUrl)) {
                results.add(StreamOption("Server Utama (Auto HD)", resolved.videoUrl, resolved.referer, resolved.isDirectMedia))
            }
        }

        val nonce = extractAjaxNonce(html, baseUrl)
        val mirrorItemRegex = Regex("<a[^>]+data-content=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val qualitySectionRegex = Regex("<ul class=\"(m360p|m480p|m720p)\">(.*?)</ul>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val prioritizedTokens = ArrayList<Pair<String, String>>()
        for (qm in qualitySectionRegex.findAll(html)) {
            val qLabel = qm.groupValues[1].removePrefix("m").uppercase(Locale.US)
            for (mm in mirrorItemRegex.findAll(qm.groupValues[2])) {
                val token = mm.groupValues[1].trim()
                val srvName = stripTags(mm.groupValues[2]).ifEmpty { "Mirror" }
                if (token.isNotEmpty()) {
                    prioritizedTokens.add(Pair("$qLabel - $srvName", token))
                }
            }
        }
        val sortedTokens = prioritizedTokens.sortedByDescending {
            when {
                it.first.startsWith("720P") -> 3
                it.first.startsWith("480P") -> 2
                else -> 1
            }
        }.take(6)

        if (nonce.isNotEmpty()) {
            for ((label, token) in sortedTokens) {
                val embedUrl = exchangeMirrorTokenForIframe(baseUrl, activeEpUrl, token, nonce) ?: continue
                val resolved = extractDirectVideo(embedUrl, activeEpUrl) ?: continue
                if (seenUrls.add(resolved.videoUrl)) {
                    results.add(StreamOption(label, resolved.videoUrl, resolved.referer, resolved.isDirectMedia))
                }
            }
        }

        return results.sortedByDescending { if (it.isDirectMedia) 1 else 0 }
    }

    private fun extractAjaxNonce(html: String, baseUrl: String): String {
        val inlineNonce = Regex("nonce\\s*:\\s*[\"']([a-zA-Z0-9]+)[\"']").find(html)?.groupValues?.getOrNull(1)
        if (!inlineNonce.isNullOrEmpty()) return inlineNonce

        val actionMatch = Regex("action\\s*:\\s*[\"']([a-zA-Z0-9_]+)[\"']").find(html)?.groupValues?.getOrNull(1) ?: return ""
        val resp = smartHttpPost("$baseUrl/wp-admin/admin-ajax.php", "action=$actionMatch", baseUrl) ?: return ""
        return try {
            JSONObject(resp).optString("data", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun exchangeMirrorTokenForIframe(baseUrl: String, referer: String, base64Token: String, nonce: String): String? {
        return try {
            val decoded = String(Base64.decode(base64Token, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(decoded)
            val id = json.optString("id", "")
            val i = json.optString("i", "")
            val q = json.optString("q", "")
            if (id.isEmpty()) return null

            val action = "2a3505c93b0035d3f455df82bf976b84"
            val body = "id=${URLEncoder.encode(id, "UTF-8")}&i=${URLEncoder.encode(i, "UTF-8")}&q=${URLEncoder.encode(q, "UTF-8")}&nonce=${URLEncoder.encode(nonce, "UTF-8")}&action=$action"
            val resp = smartHttpPost("$baseUrl/wp-admin/admin-ajax.php", body, referer) ?: return null
            val dataB64 = JSONObject(resp).optString("data", "")
            if (dataB64.isEmpty()) return null
            val iframeHtml = String(Base64.decode(dataB64, Base64.DEFAULT), Charsets.UTF_8)
            val src = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.getOrNull(1)
            if (src.isNullOrBlank()) null else normalizeUrl(src, baseUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDirectVideo(embedUrl: String, pageReferer: String): StreamOption? {
        if (embedUrl.endsWith(".mp4", true) || embedUrl.contains(".m3u8", true)) {
            return StreamOption("Direct", embedUrl, pageReferer, true)
        }

        val embedHtml = smartHttpGet(embedUrl, pageReferer) ?: return null

        val gVideoRegex = Regex("\"play_url\"\\s*:\\s*\"(https:[^\"]+googlevideo\\.com[^\"]+)\"")
        val gMatch = gVideoRegex.findAll(embedHtml).lastOrNull()?.groupValues?.getOrNull(1)
        if (!gMatch.isNullOrEmpty()) {
            val cleanUrl = unescapeJsonUrl(gMatch)
            return StreamOption("Direct", cleanUrl, embedUrl, true)
        }

        val fileRegex = Regex("[\"']?(?:file|src|url)[\"']?\\s*[:=]\\s*[\"'](https?:\\\\?/\\\\?/[^\"'\\s]+\\.(?:mp4|m3u8)[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
        val fMatch = fileRegex.find(embedHtml)?.groupValues?.getOrNull(1)
        if (!fMatch.isNullOrEmpty()) {
            val cleanUrl = unescapeJsonUrl(fMatch)
            return StreamOption("Direct", cleanUrl, embedUrl, true)
        }

        val sourceTag = Regex("<source[^>]+src=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)
        if (!sourceTag.isNullOrEmpty()) {
            return StreamOption("Direct", decodeHtml(sourceTag), embedUrl, true)
        }

        val pdMatch = Regex("(https?://pixeldrain\\.com/api/file/[a-zA-Z0-9_-]+)").find(embedHtml)?.groupValues?.getOrNull(1)
        if (!pdMatch.isNullOrEmpty()) {
            return StreamOption("Direct", pdMatch, embedUrl, true)
        }

        return null
    }

    fun openEpisodeSelector(
        context: Context,
        item: AnimeItem,
        onStatus: (title: String?, message: String, autoHideMs: Long) -> Unit,
        onHideStatus: () -> Unit
    ) {
        val ui = Handler(Looper.getMainLooper())
        onStatus(null, "Memuat daftar episode ${item.title}...", 0L)

        Thread {
            val detail = fetchDetail(context, item)
            ui.post {
                onHideStatus()
                if (detail == null || detail.episodes.isEmpty()) {
                    onStatus("Episode Belum Tersedia", "Gagal memuat daftar episode. Coba cek tombol Domain Anime di atas.", 4000L)
                    return@post
                }

                val epTitles = detail.episodes.map { it.title }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle("${detail.title} (${detail.episodes.size} Episode)")
                    .setItems(epTitles) { _, which ->
                        val chosenEp = detail.episodes[which]
                        resolveAndPlayEpisode(context, detail, chosenEp, onStatus, onHideStatus)
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }.start()
    }

    private fun resolveAndPlayEpisode(
        context: Context,
        detail: AnimeDetail,
        ep: EpisodeItem,
        onStatus: (title: String?, message: String, autoHideMs: Long) -> Unit,
        onHideStatus: () -> Unit
    ) {
        val ui = Handler(Looper.getMainLooper())
        onStatus("Mengekstrak Video...", "Mengambil tautan streaming langsung Sub-Indo...", 0L)

        Thread {
            val streams = resolveEpisodeStreams(context, ep.url)
            ui.post {
                onHideStatus()
                if (streams.isEmpty()) {
                    onStatus("Stream Tidak Ditemukan", "Server episode ini sedang padat. Coba episode atau judul lain.", 4000L)
                    return@post
                }
                if (streams.size == 1) {
                    launchVodPlayer(context, detail, ep, streams[0])
                } else {
                    val labels = streams.map { it.label }.toTypedArray()
                    AlertDialog.Builder(context)
                        .setTitle("Pilih Kualitas - ${ep.title}")
                        .setItems(labels) { _, idx ->
                            launchVodPlayer(context, detail, ep, streams[idx])
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun launchVodPlayer(context: Context, detail: AnimeDetail, ep: EpisodeItem, stream: StreamOption) {
        val fullTitle = "${detail.title} - ${ep.title}"
        val resumeKey = "anime_indo_${detail.url.hashCode()}_${ep.episodeNumber}"
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("anime_ep_$resumeKey", ep.url)
            .apply()

        val intent = Intent(context, VodPlayerActivity::class.java).apply {
            putExtra("title", fullTitle)
            putExtra("url", stream.videoUrl)
            putExtra("referer", stream.referer)
            putExtra("origin", getBaseUrl(context))
            putExtra("user_agent", UA)
            putExtra("resume_key", resumeKey)
            putExtra("resume_ms", 0L)
            putExtra("tmdb_kind", "tv")
            putExtra("season", 1)
            putExtra("episode", ep.episodeNumber)
            putExtra("poster", detail.poster)
            putExtra("backdrop", detail.poster)
            putExtra("overview", detail.synopsis)
            putExtra("anime_ep_url", ep.url)
        }
        ContinueWatch.saveFromIntent(context, intent, 0L)
        context.startActivity(intent)
    }

    private fun rewriteToActiveMirror(rawUrl: String, activeBase: String): String {
        return try {
            val u = URL(rawUrl)
            val host = u.host.lowercase(Locale.US)
            if (host.contains("otakudesu")) {
                val pathAndQuery = u.file.ifEmpty { "/" }
                "$activeBase$pathAndQuery"
            } else {
                rawUrl
            }
        } catch (_: Exception) {
            rawUrl
        }
    }

    private fun smartHttpGet(urlStr: String, referer: String): String? {
        val normal = httpGetInternal(urlStr, referer, null)
        if (!normal.isNullOrBlank() && !looksLikeIspBlock(normal)) {
            return normal
        }
        val host = extractHost(urlStr) ?: return normal
        val dohIp = resolveHostViaDoH(host) ?: return normal
        return httpGetInternal(urlStr, referer, dohIp)
    }

    private fun smartHttpPost(urlStr: String, formBody: String, referer: String): String? {
        val normal = httpPostInternal(urlStr, formBody, referer, null)
        if (!normal.isNullOrBlank() && !looksLikeIspBlock(normal)) {
            return normal
        }
        val host = extractHost(urlStr) ?: return normal
        val dohIp = resolveHostViaDoH(host) ?: return normal
        return httpPostInternal(urlStr, formBody, referer, dohIp)
    }

    private fun looksLikeIspBlock(body: String): Boolean {
        val low = body.lowercase(Locale.US)
        return low.contains("internetpositif") ||
            low.contains("uzone.id") ||
            low.contains("trustpositif") ||
            low.contains("kominfo.go.id")
    }

    private fun extractHost(urlStr: String): String? {
        return try {
            URL(urlStr).host?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveHostViaDoH(host: String): String? {
        dohIpCache[host]?.let { return it }
        val dohEndpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$host&type=A",
            "https://dns.google/resolve?name=$host&type=A"
        )
        for (endpoint in dohEndpoints) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/dns-json")
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                    for (i in 0 until answers.length()) {
                        val ans = answers.getJSONObject(i)
                        if (ans.optInt("type") == 1) {
                            val ip = ans.optString("data", "").trim()
                            if (ip.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
                                dohIpCache[host] = ip
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun openConfiguredConnection(urlStr: String, overrideIp: String?): HttpURLConnection {
        if (overrideIp.isNullOrEmpty()) {
            return URL(urlStr).openConnection() as HttpURLConnection
        }
        val orig = URL(urlStr)
        val origHost = orig.host
        val portPart = if (orig.port != -1) ":${orig.port}" else ""
        val ipUrl = URL("${orig.protocol}://$overrideIp$portPart${orig.file}")
        val conn = ipUrl.openConnection() as HttpURLConnection
        conn.setRequestProperty("Host", origHost)

        if (conn is HttpsURLConnection) {
            try {
                val baseFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
                conn.sslSocketFactory = object : SSLSocketFactory() {
                    override fun getDefaultCipherSuites(): Array<String> = baseFactory.defaultCipherSuites
                    override fun getSupportedCipherSuites(): Array<String> = baseFactory.supportedCipherSuites
                    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
                        val sock = baseFactory.createSocket(s, origHost, port, autoClose)
                        applySni(sock, origHost)
                        return sock
                    }
                    override fun createSocket(host: String?, port: Int): Socket {
                        val sock = baseFactory.createSocket(overrideIp, port)
                        applySni(sock, origHost)
                        return sock
                    }
                    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                        val sock = baseFactory.createSocket(overrideIp, port, localHost, localPort)
                        applySni(sock, origHost)
                        return sock
                    }
                    override fun createSocket(host: InetAddress?, port: Int): Socket {
                        val sock = baseFactory.createSocket(host, port)
                        applySni(sock, origHost)
                        return sock
                    }
                    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                        val sock = baseFactory.createSocket(address, port, localAddress, localPort)
                        applySni(sock, origHost)
                        return sock
                    }
                }
                conn.hostnameVerifier = HostnameVerifier { _, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(origHost, session)
                }
            } catch (_: Throwable) {
            }
        }
        return conn
    }

    private fun applySni(socket: Socket, sniHost: String) {
        if (socket is SSLSocket) {
            try {
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                socket.sslParameters = params
            } catch (_: Throwable) {
            }
        }
    }

    private fun httpGetInternal(urlStr: String, referer: String, overrideIp: String?): String? {
        return try {
            val conn = openConfiguredConnection(urlStr, overrideIp)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", UA)
            if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun httpPostInternal(urlStr: String, formBody: String, referer: String, overrideIp: String?): String? {
        return try {
            val conn = openConfiguredConnection(urlStr, overrideIp)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String {
        val clean = decodeHtml(raw.trim())
        return when {
            clean.isEmpty() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$baseUrl$clean"
            else -> clean
        }
    }

    private fun unescapeJsonUrl(raw: String): String {
        return decodeHtml(
            raw.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
        )
    }

    private fun stripTags(html: String): String {
        return decodeHtml(html.replace(Regex("<[^>]*>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeHtml(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&nbsp;", " ")
    }
}
