package com.bayutv.hotel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

object AutoFeedUpdater {

    data class YoutubeMovieItem(
        val videoId: String,
        val title: String,
        val channel: String,
        val published: String,
        val thumbUrl: String
    )

    // Daftar Playlist / Channel Film YouTube (otomatis update saat ada upload baru)
    private val YOUTUBE_FEEDS = listOf(
        // Contoh: Ganti atau tambahkan playlist_id / channel_id film resmi pilihan Anda
        "https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw"
    )

    /**
     * Memperbarui otomatis:
     * 1. Chart Musik Terbaru (Deezer Public Chart JSON)
     * 2. Daftar Putar Film YouTube (YouTube RSS XML tanpa API Key)
     * 3. Film Rating Tertinggi & Rilis Terbaru (TMDB jika apiKey diisi)
     */
    fun refreshAllFeedsAsync(
        context: Context,
        tmdbApiKey: String = "",
        onComplete: (() -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            // 1. Auto-update Chart Musik Terbaru (otomatis terisi ke daftar lagu)
            fetchMusicChart()?.let { musicJson ->
                prefs.edit().putString("auto_music_chart", musicJson.toString()).apply()
            }

            // 2. Auto-update Daftar Putar Film YouTube dari RSS Feed
            val ytMovies = fetchYoutubePlaylists(YOUTUBE_FEEDS)
            if (ytMovies.isNotEmpty()) {
                val arr = JSONArray()
                for (m in ytMovies) {
                    arr.put(JSONObject().apply {
                        put("videoId", m.videoId)
                        put("title", m.title)
                        put("channel", m.channel)
                        put("published", m.published)
                        put("thumb", m.thumbUrl)
                    })
                }
                prefs.edit().putString("auto_youtube_movies", arr.toString()).apply()
            }

            // 3. Auto-update Film Rating Terbaru & Now Playing dari TMDB
            if (tmdbApiKey.isNotBlank()) {
                fetchTmdbCategory("https://api.themoviedb.org/3/movie/now_playing?api_key=$tmdbApiKey&language=id-ID&page=1")
                    ?.let { prefs.edit().putString("tmdb_now_playing", it.toString()).apply() }

                fetchTmdbCategory("https://api.themoviedb.org/3/movie/top_rated?api_key=$tmdbApiKey&language=id-ID&page=1")
                    ?.let { prefs.edit().putString("tmdb_top_rated", it.toString()).apply() }
            }

            mainHandler.post { onComplete?.invoke() }
        }.start()
    }

    private fun fetchMusicChart(): JSONArray? {
        return try {
            val raw = httpGet("https://api.deezer.com/chart/0/tracks?limit=30") ?: return null
            val data = JSONObject(raw).optJSONArray("data") ?: return null
            val out = JSONArray()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val title = item.optString("title")
                val artist = item.optJSONObject("artist")?.optString("name") ?: "Top Chart"
                val streamUrl = item.optString("preview")
                if (title.isNotEmpty() && streamUrl.startsWith("http")) {
                    out.put(JSONObject().apply {
                        put("title", title)
                        put("artist", "$artist · Chart #${i + 1}")
                        put("url", streamUrl)
                    })
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTmdbCategory(url: String): JSONArray? {
        return try {
            val raw = httpGet(url) ?: return null
            val results = JSONObject(raw).optJSONArray("results") ?: return null
            val out = JSONArray()
            for (i in 0 until results.length()) {
                val m = results.getJSONObject(i)
                out.put(JSONObject().apply {
                    put("id", m.optInt("id"))
                    put("title", m.optString("title"))
                    put("rating", String.format("%.1f", m.optDouble("vote_average", 0.0)))
                    put("release", m.optString("release_date"))
                    put("poster", "https://image.tmdb.org/t/p/w342" + m.optString("poster_path"))
                })
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchYoutubePlaylists(feedUrls: List<String>): List<YoutubeMovieItem> {
        val list = ArrayList<YoutubeMovieItem>()
        val seenIds = HashSet<String>()

        for (feed in feedUrls) {
            val xml = httpGet(feed) ?: continue
            try {
                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xml))
                var inEntry = false
                var videoId = ""
                var title = ""
                var author = ""
                var published = ""

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    val tag = parser.name
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (tag == "entry") {
                                inEntry = true
                                videoId = ""; title = ""; author = ""; published = ""
                            } else if (inEntry) {
                                when (tag) {
                                    "videoId" -> videoId = parser.nextText().trim()
                                    "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                                    "name" -> if (author.isEmpty()) author = parser.nextText().trim()
                                    "published" -> published = parser.nextText().take(10)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tag == "entry" && inEntry) {
                                inEntry = false
                                if (videoId.isNotEmpty() && seenIds.add(videoId)) {
                                    list.add(
                                        YoutubeMovieItem(
                                            videoId = videoId,
                                            title = title,
                                            channel = author.ifEmpty { "YouTube Film" },
                                            published = published,
                                            thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                        )
                                    )
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            } catch (_: Exception) {
            }
        }
        return list
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 9000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV)")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
