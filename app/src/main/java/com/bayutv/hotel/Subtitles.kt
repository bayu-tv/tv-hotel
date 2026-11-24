package com.bayutv.hotel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object Subtitles {

    data class Track(
        val url: String,
        val lang: String,
        val label: String,
        val isVtt: Boolean
    )

    data class CueEntry(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

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

        if (tmdbId > 0) {
            val q1 = if (kind == "tv" && season > 0 && episode > 0) {
                "https://sub.wyzie.ru/search?id=$tmdbId&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$tmdbId"
            }
            parseWyzie(httpGet(q1), seen, indo, malay, eng)
        }

        if (indo.isEmpty() && imdbId.startsWith("tt")) {
            val q2 = if (kind == "tv" && season > 0 && episode > 0) {
                "https://sub.wyzie.ru/search?id=$imdbId&season=$season&episode=$episode"
            } else {
                "https://sub.wyzie.ru/search?id=$imdbId"
            }
            parseWyzie(httpGet(q2), seen, indo, malay, eng)
        }

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

    fun downloadAndParse(urlStr: String): List<CueEntry> {
        val raw = httpGet(urlStr, 12000) ?: return emptyList()
        val clean = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = clean.split(Regex("\n\\s*\n"))
        val cues = ArrayList<CueEntry>(blocks.size)
        val timeRegex = Regex(
            "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})"
        )

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty()) continue
            var timeLineIdx = -1
            var match: MatchResult? = null
            for (idx in lines.indices) {
                val m = timeRegex.find(lines[idx])
                if (m != null) {
                    timeLineIdx = idx
                    match = m
                    break
                }
            }
            if (timeLineIdx < 0 || match == null) continue

            val startMs = parseTimestampMs(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3],
                match.groupValues[4]
            )
            val endMs = parseTimestampMs(
                match.groupValues[5],
                match.groupValues[6],
                match.groupValues[7],
                match.groupValues[8]
            )
            if (endMs <= startMs) continue

            val bodyLines = lines.drop(timeLineIdx + 1)
                .map { cleanCueText(it) }
                .filter { it.isNotEmpty() }
            if (bodyLines.isEmpty()) continue

            cues.add(CueEntry(startMs, endMs, bodyLines.joinToString("\n")))
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestampMs(hStr: String, mStr: String, sStr: String, msStr: String): Long {
        val h = hStr.toLongOrNull() ?: 0L
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val ms = msStr.toLongOrNull() ?: 0L
        return (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
    }

    private fun cleanCueText(line: String): String {
        return line
            .replace(Regex("\\{[^}]*\\}"), "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
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
                val lang = o.optString("language", "").lowercase(Locale.US).trim()
                val disp = o.optString("display", lang.uppercase(Locale.US)).trim()
                val fmt = o.optString("format", "").lowercase(Locale.US)
                val isVtt = fmt == "vtt" || url.lowercase(Locale.US).contains(".vtt")
                when {
                    lang == "id" || lang == "in" || lang == "ind" || disp.contains("indonesia", true) ->
                        indo.add(Track(url, "id", "Indonesia - $disp", isVtt))
                    lang == "ms" || lang == "may" || lang == "msa" || disp.contains("malay", true) ->
                        malay.add(Track(url, "ms", "Melayu - $disp", isVtt))
                    lang == "en" || lang == "eng" ->
                        eng.add(Track(url, "en", "English - $disp", isVtt))
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
                val lang = o.optString("lang", "").lowercase(Locale.US).trim()
                val isVtt = url.lowercase(Locale.US).contains(".vtt")
                when (lang) {
                    "ind", "id", "in" -> indo.add(Track(url, "id", "Indonesia - OpenSubtitles", isVtt))
                    "may", "msa", "ms" -> malay.add(Track(url, "ms", "Melayu - OpenSubtitles", isVtt))
                    "eng", "en" -> eng.add(Track(url, "en", "English - OpenSubtitles", isVtt))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun httpGet(u: String, readTimeoutMs: Int = 8000): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 6000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ0)")
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
