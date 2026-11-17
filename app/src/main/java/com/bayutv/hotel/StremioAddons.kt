package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object StremioAddons {

    data class StreamSource(
        val addonName: String,
        val title: String,
        val url: String,
        val referer: String = "",
        val origin: String = "",
        val userAgent: String = ""
    ) {
        fun displayLabel(): String {
            val cleanTitle = title.replace("\n", " | ").trim()
            return if (cleanTitle.isNotEmpty()) {
                "[$addonName] $cleanTitle"
            } else {
                "[$addonName] Stream Direct"
            }
        }
    }

    val DEFAULT_ADDONS = listOf(
        "https://nuviostreams.hayd.uk",
        "https://webstreamr.hayd.uk"
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
            Callable {
                querySingleAddon(base, streamPath)
            }
        }

        val results = ArrayList<StreamSource>()
        val seenUrls = HashSet<String>()
        try {
            val futures = pool.invokeAll(tasks, 9L, TimeUnit.SECONDS)
            for (f in futures) {
                if (f.isDone && !f.isCancelled) {
                    val items = try {
                        f.get()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (item in items) {
                        if (seenUrls.add(item.url)) {
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
                val url = s.optString("url", "").trim()
                val low = url.lowercase()

                // Hanya ambil stream HTTP/HTTPS langsung yang bisa diputar ExoPlayer (abaikan torrent/magnet)
                if (!low.startsWith("http://") && !low.startsWith("https://")) continue
                if (low.endsWith(".torrent")) continue

                val name = s.optString("name", hostLabel).trim().ifEmpty { hostLabel }
                val title = s.optString("title", "").ifEmpty { s.optString("description", "") }.trim()

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
                        addonName = name,
                        title = title,
                        url = url,
                        referer = ref,
                        origin = orig,
                        userAgent = ua
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", Playlist.DEFAULT_UA)
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
