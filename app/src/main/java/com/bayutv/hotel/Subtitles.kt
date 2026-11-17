package com.bayutv.hotel

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object Subtitles {

    data class Track(
        val url: String,
        val lang: String,
        val label: String,
        val isVtt: Boolean
    )

    private val subCache = HashMap<String, List<Track>>()

    /**
     * Mencari subtitle gratis berdasarkan TMDB ID & IMDb ID (prioritas: Indonesia -> Melayu -> Inggris).
     */
    fun fetch(
        tmdbId: Int,
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        imdbId: String? = null
    ): List<Track> {
        if (tmdbId <= 0 && imdbId.isNullOrBlank()) return emptyList()
        val cacheKey = "$tmdbId:$imdbId:$kind:$season:$episode"
        synchronized(subCache) {
            subCache[cacheKey]?.let { return it }
        }

        val indo = ArrayList<Track>()
        val malay = ArrayList<Track>()
        val eng = ArrayList<Track>()
        val seenUrls = HashSet<String>()

        fun parseEndpoint(idParam: String) {
            val query = if (kind == "tv" && season > 0 && episode > 0) {
                "https://sub.wyzie.ru/search?id=$idParam&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$idParam"
            }
            val raw = httpGet(query) ?: return
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url", "").trim()
                    if (!url.startsWith("http") || !seenUrls.add(url)) continue
                    val lang = o.optString("language", "").lowercase().trim()
                    val disp = o.optString("display", lang.uppercase()).trim()
                    val fmt = o.optString("format", "").lowercase()
                    val isVtt = fmt == "vtt" || url.lowercase().contains(".vtt")
                    when {
                        lang == "id" || lang == "ind" || disp.contains("indonesia", true) ->
                            indo.add(Track(url, "id", "Indonesia · $disp", isVtt))
                        lang == "ms" || lang == "may" || disp.contains("malay", true) ->
                            malay.add(Track(url, "ms", "Melayu · $disp", isVtt))
                        lang == "en" || lang == "eng" ->
                            eng.add(Track(url, "en", "English · $disp", isVtt))
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (tmdbId > 0) {
            parseEndpoint(tmdbId.toString())
        }
        if (indo.isEmpty() && !imdbId.isNullOrBlank()) {
            parseEndpoint(imdbId)
        }

        val result = (indo.take(3) + malay.take(1) + eng.take(2))
        if (result.isNotEmpty()) {
            synchronized(subCache) {
                subCache[cacheKey] = result
            }
        }
        return result
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 7000
            c.readTimeout = 9000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ0)")
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
