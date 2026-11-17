package com.bayutv.hotel

import org.json.JSONArray
import org.json.JSONObject
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
     * Mencari subtitle gratis berdasarkan TMDB ID & IMDb ID (prioritas: Indonesia -> Melayu -> Inggris).
     */
    fun fetch(
        tmdbId: Int,
        kind: String = "movie",
        season: Int = 0,
        episode: Int = 0,
        imdbId: String = ""
    ): List<Track> {
        val seen = HashSet<String>()
        val indo = ArrayList<Track>()
        val malay = ArrayList<Track>()
        val eng = ArrayList<Track>()

        // 1. Sumber Utama: Wyzie (TMDB ID)
        if (tmdbId > 0) {
            val q1 = if (kind == "tv" && season > 0 && episode > 0) {
                "https://sub.wyzie.ru/search?id=$tmdbId&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$tmdbId"
            }
            parseWyzie(httpGet(q1), seen, indo, malay, eng)
        }

        // 2. Sumber Cadangan Wyzie via IMDb ID jika Subtitle Indonesia belum ditemukan
        if (indo.isEmpty() && imdbId.startsWith("tt")) {
            val q2 = if (kind == "tv" && season > 0 && episode > 0) {
                "https://sub.wyzie.ru/search?id=$imdbId&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$imdbId"
            }
            parseWyzie(httpGet(q2), seen, indo, malay, eng)
        }

        // 3. Sumber Cadangan OpenSubtitles v3 via IMDb ID
        if (indo.isEmpty() && imdbId.startsWith("tt")) {
            val q3 = if (kind == "tv" && season > 0 && episode > 0) {
                "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
            } else {
                "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
            }
            parseOpenSubtitles(httpGet(q3), seen, indo, malay, eng)
        }

        return (indo.take(4) + malay.take(1) + eng.take(2))
    }

    private fun parseWyzie(
        raw: String?,
        seen: HashSet<String>,
        indo: ArrayList<Track>,
        malay: ArrayList<Track>,
        eng: ArrayList<Track>
    ) {
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url", "").trim()
                if (!url.startsWith("http") || !seen.add(url)) continue
                val lang = o.optString("language", "").lowercase().trim()
                val disp = o.optString("display", lang.uppercase()).trim()
                val fmt = o.optString("format", "").lowercase()
                val isVtt = fmt == "vtt" || url.lowercase().contains(".vtt")
                when {
                    lang == "id" || lang == "in" || lang == "ind" || disp.contains("indonesia", true) ->
                        indo.add(Track(url, "id", "Indonesia · $disp", isVtt))
                    lang == "ms" || lang == "may" || lang == "msa" || disp.contains("malay", true) ->
                        malay.add(Track(url, "ms", "Melayu · $disp", isVtt))
                    lang == "en" || lang == "eng" ->
                        eng.add(Track(url, "en", "English · $disp", isVtt))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseOpenSubtitles(
        raw: String?,
        seen: HashSet<String>,
        indo: ArrayList<Track>,
        malay: ArrayList<Track>,
        eng: ArrayList<Track>
    ) {
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONObject(raw).optJSONArray("subtitles") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url", "").trim()
                if (!url.startsWith("http") || !seen.add(url)) continue
                val lang = o.optString("lang", "").lowercase().trim()
                val isVtt = url.lowercase().contains(".vtt")
                when (lang) {
                    "ind", "id", "in" -> indo.add(Track(url, "id", "Indonesia · OpenSubtitles", isVtt))
                    "may", "msa", "ms" -> malay.add(Track(url, "ms", "Melayu · OpenSubtitles", isVtt))
                    "eng", "en" -> eng.add(Track(url, "en", "English · OpenSubtitles", isVtt))
                }
            }
        } catch (_: Exception) {
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
