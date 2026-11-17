package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object StremioAddons {

    data class StreamSource(
        val addonName: String,
        val title: String,
        val url: String,
        val isTorrent: Boolean = false,
        val fileIdx: Int = -1,
        val referer: String = "",
        val origin: String = "",
        val userAgent: String = ""
    ) {
        fun displayLabel(): String {
            val cleanTitle = title.replace("\n", " | ").trim()
            val prefix = if (isTorrent) "P2P" else "HTTP"
            return if (cleanTitle.isNotEmpty()) {
                "[$prefix - $addonName] $cleanTitle"
            } else {
                "[$prefix - $addonName] Stream"
            }
        }
    }

    val DEFAULT_ADDONS = listOf(
        "https://torrentio.strem.fun/sort=seeders|qualityfilter=4k,scr,cam"
    )

    private val FALLBACK_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "http://tracker.opentrackr.org:1337/announce"
    )

    fun getAddonUrls(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val raw = (prefs.getString("stremio_addons", "") ?: "").trim()
        if (raw.isEmpty()) return DEFAULT_ADDONS
        val list = raw.split("\n", ",")
            .map { normalizeBaseUrl(it) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        return if (list.isEmpty()) DEFAULT_ADDONS else list
    }

    fun saveAddonUrls(ctx: Context, rawInput: String) {
        val cleaned = rawInput.split("\n", ",")
            .map { normalizeBaseUrl(it) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .joinToString("\n")
        ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
            .edit()
            .putString("stremio_addons", cleaned)
            .apply()
    }

    fun normalizeBaseUrl(input: String): String {
        var u = input.trim()
        if (u.endsWith("/manifest.json", ignoreCase = true)) {
            u = u.substring(0, u.length - "/manifest.json".length)
        }
        while (u.endsWith("/")) {
            u = u.dropLast(1)
        }
        return u
    }

    fun fetchStreams(
        ctx: Context,
        imdbId: String,
        kind: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ): List<StreamSource> {
        if (!imdbId.startsWith("tt")) return emptyList()
        val addons = getAddonUrls(ctx)
        if (addons.isEmpty()) return emptyList()

        val streamPath = if (kind == "tv") {
            "/stream/series/$imdbId:$season:$episode.json"
        } else {
            "/stream/movie/$imdbId.json"
        }

        val pool = Executors.newFixedThreadPool(addons.size.coerceAtMost(4))
        val tasks = addons.map { base ->
            Callable { querySingleAddon(base, streamPath) }
        }

        val results = ArrayList<StreamSource>()
        val seenKeys = HashSet<String>()
        try {
            val futures = pool.invokeAll(tasks, 11L, TimeUnit.SECONDS)
            for (f in futures) {
                if (f.isDone && !f.isCancelled) {
                    val items = try {
                        f.get()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (item in items) {
                        val dedupKey = "${item.url}#${item.fileIdx}"
                        if (seenKeys.add(dedupKey)) {
                            results.add(item)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }
        return results
    }

    private fun querySingleAddon(baseUrl: String, streamPath: String): List<StreamSource> {
        val endpoint = baseUrl + streamPath
        val body = httpGet(endpoint) ?: return emptyList()
        val out = ArrayList<StreamSource>()
        val hostLabel = try {
            URL(baseUrl).host.substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Addon"
        }

        try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("streams") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val rawName = s.optString("name", hostLabel).replace("\n", " ").trim().ifEmpty { hostLabel }
                val rawTitle = s.optString("title", "").ifEmpty { s.optString("description", "") }.trim()

                val directUrl = s.optString("url", "").trim()
                val infoHash = s.optString("infoHash", "").trim().lowercase()

                if (directUrl.startsWith("http://", true) || directUrl.startsWith("https://", true)) {
                    if (directUrl.lowercase().endsWith(".torrent")) continue
                    var ref = ""
                    var orig = ""
                    var ua = ""
                    val hints = s.optJSONObject("behaviorHints")
                    val reqHeaders = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                    if (reqHeaders != null) {
                        ref = reqHeaders.optString("Referer", "").ifEmpty { reqHeaders.optString("referer", "") }
                        orig = reqHeaders.optString("Origin", "").ifEmpty { reqHeaders.optString("origin", "") }
                        ua = reqHeaders.optString("User-Agent", "").ifEmpty { reqHeaders.optString("user-agent", "") }
                    }
                    out.add(
                        StreamSource(
                            addonName = rawName,
                            title = rawTitle,
                            url = directUrl,
                            isTorrent = false,
                            referer = ref,
                            origin = orig,
                            userAgent = ua
                        )
                    )
                } else if (infoHash.length == 40 || infoHash.length == 32) {
                    val fileIdx = s.optInt("fileIdx", -1)
                    val trackers = ArrayList<String>()
                    val sourcesArr = s.optJSONArray("sources")
                    if (sourcesArr != null) {
                        for (j in 0 until sourcesArr.length()) {
                            val src = sourcesArr.optString(j, "").trim()
                            if (src.startsWith("tracker:")) {
                                trackers.add(src.removePrefix("tracker:"))
                            }
                        }
                    }
                    for (fb in FALLBACK_TRACKERS) {
                        if (!trackers.contains(fb)) trackers.add(fb)
                    }

                    val dn = URLEncoder.encode(
                        rawTitle.substringBefore("\n").ifEmpty { infoHash },
                        "UTF-8"
                    )
                    val trParams = trackers.take(14).joinToString("") {
                        "&tr=" + URLEncoder.encode(it, "UTF-8")
                    }
                    val magnetUri = "magnet:?xt=urn:btih:$infoHash&dn=$dn$trParams"

                    out.add(
                        StreamSource(
                            addonName = rawName,
                            title = rawTitle,
                            url = magnetUri,
                            isTorrent = true,
                            fileIdx = fileIdx
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 9000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
// END OF StremioAddons.kt
