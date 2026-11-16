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

    /**
     * Mencari subtitle gratis berdasarkan TMDB ID (prioritas: Indonesia -> Melayu -> Inggris).
     */
    fun fetch(tmdbId: Int, kind: String = "movie", season: Int = 0, episode: Int = 0): List<Track> {
        if (tmdbId <= 0) return emptyList()
        val query = if (kind == "tv" && season > 0 && episode > 0) {
            "https://sub.wyzie.ru/search?id=$tmdbId&season=$season&episode=$episode"
        } else {
            "https://sub.wyzie.ru/search?id=$tmdbId"
        }
        val raw = httpGet(query) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val indo = ArrayList<Track>()
            val malay = ArrayList<Track>()
            val eng = ArrayList<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url", "").trim()
                if (!url.startsWith("http")) continue
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
            (indo.take(3) + malay.take(1) + eng.take(2))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ0)")
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
