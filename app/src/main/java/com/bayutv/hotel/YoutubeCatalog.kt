package com.bayutv.hotel

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YoutubeCatalog {

    data class Entry(
        val title: String,
        val channelName: String,
        val channelUrl: String,
        val searchQuery: String = "",
        val category: String = "Anime Sub Indo",
        val tmdbId: Int = 0,
        val tmdbKind: String = "tv",
        var year: String = "",
        var rating: Double = 0.0,
        var overview: String = "",
        var posterUrl: String? = null,
        var backdropUrl: String? = null,
        val videoUrl: String? = null
    )

    // Kanal RSS resmi YouTube (otomatis berisi 15 unggahan terbaru setiap kanal)
    private val RSS_FEEDS = listOf(
        Pair("Muse Indonesia", "https://www.youtube.com/feeds/videos.xml?channel_id=UCxxnxya_32jcKj4yN1_kD7A"),
        Pair("Ani-One Asia", "https://www.youtube.com/feeds/videos.xml?channel_id=UC0wNSTMWIL3qaorLx0jie6A")
    )

    private var cachedRss: List<Entry> = emptyList()
    private var lastRssFetchMs: Long = 0L

    val ANIME_PICKS = listOf(
        Entry(
            title = "Frieren: Beyond Journey's End",
            channelName = "Muse Indonesia",
            channelUrl = "https://www.youtube.com/@MuseIndonesia/playlists",
            searchQuery = "Frieren Sub Indo Muse Indonesia",
            category = "Anime Sub Indo",
            tmdbId = 209867
        ),
        Entry(
            title = "Solo Leveling",
            channelName = "Ani-One Asia",
            channelUrl = "https://www.youtube.com/@AniOneAsia/playlists",
            searchQuery = "Solo Leveling Sub Indo Ani-One",
            category = "Anime Sub Indo",
            tmdbId = 127532
        ),
        Entry(
            title = "Demon Slayer: Kimetsu no Yaiba",
            channelName = "Muse Indonesia",
            channelUrl = "https://www.youtube.com/@MuseIndonesia/playlists",
            searchQuery = "Kimetsu no Yaiba Sub Indo Muse Indonesia",
            category = "Anime Sub Indo",
            tmdbId = 85937
        ),
        Entry(
            title = "One Punch Man",
            channelName = "Muse Indonesia",
            channelUrl = "https://www.youtube.com/@MuseIndonesia/playlists",
            searchQuery = "One Punch Man Sub Indo Muse Indonesia",
            category = "Anime Sub Indo",
            tmdbId = 63926
        ),
        Entry(
            title = "Spy x Family",
            channelName = "Muse Indonesia",
            channelUrl = "https://www.youtube.com/@MuseIndonesia/playlists",
            searchQuery = "Spy x Family Sub Indo Muse Indonesia",
            category = "Anime Sub Indo",
            tmdbId = 120089
        ),
        Entry(
            title = "Jujutsu Kaisen",
            channelName = "Ani-One Asia",
            channelUrl = "https://www.youtube.com/@AniOneAsia/playlists",
            searchQuery = "Jujutsu Kaisen Sub Indo Ani-One",
            category = "Anime Sub Indo",
            tmdbId = 95479
        )
    )

    val DRACIN_PICKS = listOf(
        Entry(
            title = "Love Between Fairy and Devil",
            channelName = "iQIYI Indonesia",
            channelUrl = "https://www.youtube.com/@iQIYIIndonesia/playlists",
            searchQuery = "Love Between Fairy and Devil Sub Indo iQIYI",
            category = "Drama China Sub Indo",
            tmdbId = 136248
        ),
        Entry(
            title = "Hidden Love",
            channelName = "YOUKU Indonesia",
            channelUrl = "https://www.youtube.com/@YOUKUEnglish/playlists",
            searchQuery = "Hidden Love Sub Indo YOUKU",
            category = "Drama China Sub Indo",
            tmdbId = 210733
        ),
        Entry(
            title = "You Are My Glory",
            channelName = "WeTV Indonesia",
            channelUrl = "https://www.youtube.com/@WeTVIndonesia/playlists",
            searchQuery = "You Are My Glory Sub Indo WeTV",
            category = "Drama China Sub Indo",
            tmdbId = 112627
        ),
        Entry(
            title = "The Untamed",
            channelName = "WeTV Indonesia",
            channelUrl = "https://www.youtube.com/@WeTVIndonesia/playlists",
            searchQuery = "The Untamed Sub Indo WeTV",
            category = "Drama China Sub Indo",
            tmdbId = 91001
        )
    )

    val OFFICIAL_CHANNELS = listOf(
        Entry(
            title = "Muse Indonesia (Anime Resmi)",
            channelName = "Muse Indonesia",
            channelUrl = "https://www.youtube.com/@MuseIndonesia/playlists",
            category = "Kanal Resmi"
        ),
        Entry(
            title = "Ani-One Asia (Anime Resmi)",
            channelName = "Ani-One Asia",
            channelUrl = "https://www.youtube.com/@AniOneAsia/playlists",
            category = "Kanal Resmi"
        ),
        Entry(
            title = "WeTV Indonesia (Drama Resmi)",
            channelName = "WeTV Indonesia",
            channelUrl = "https://www.youtube.com/@WeTVIndonesia/playlists",
            category = "Kanal Resmi"
        ),
        Entry(
            title = "iQIYI Indonesia (Drama & Anime)",
            channelName = "iQIYI Indonesia",
            channelUrl = "https://www.youtube.com/@iQIYIIndonesia/playlists",
            category = "Kanal Resmi"
        )
    )

    fun fetchLatestRss(context: Context): List<Entry> {
        val now = System.currentTimeMillis()
        if (cachedRss.isNotEmpty() && now - lastRssFetchMs < 30 * 60 * 1000L) {
            return cachedRss
        }
        val merged = ArrayList<Entry>()
        for ((defaultChannel, feedUrl) in RSS_FEEDS) {
            try {
                val conn = URL(feedUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.setRequestProperty("User-Agent", "BayuTV")
                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    merged.addAll(parseAtomFeed(xml, defaultChannel).take(10))
                }
            } catch (_: Exception) {
            }
        }
        if (merged.isNotEmpty()) {
            cachedRss = merged
            lastRssFetchMs = now
        }
        return cachedRss
    }

    private fun parseAtomFeed(xml: String, fallbackChannel: String): List<Entry> {
        val list = ArrayList<Entry>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inEntry = false
            var inAuthor = false
            var title = ""
            var videoId = ""
            var author = ""
            var published = ""
            var summary = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name ?: ""
                        if (tag == "entry") {
                            inEntry = true
                            title = ""
                            videoId = ""
                            author = ""
                            published = ""
                            summary = ""
                        } else if (inEntry) {
                            when (tag) {
                                "title" -> title = parser.nextText().trim()
                                "yt:videoId" -> videoId = parser.nextText().trim()
                                "published" -> published = parser.nextText().trim()
                                "media:description" -> summary = parser.nextText().trim()
                                "author" -> inAuthor = true
                                "name" -> if (inAuthor) author = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name ?: ""
                        if (tag == "author") inAuthor = false
                        if (tag == "entry" && inEntry) {
                            inEntry = false
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                val ch = author.ifBlank { fallbackChannel }
                                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                                list.add(
                                    Entry(
                                        title = cleanTitle(title),
                                        channelName = ch,
                                        channelUrl = watchUrl,
                                        searchQuery = title,
                                        category = "Episode Baru · RSS",
                                        year = if (published.length >= 4) published.substring(0, 4) else "",
                                        overview = summary.take(240),
                                        posterUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                        backdropUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                                        videoUrl = watchUrl
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
        return list
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace("[Sub Indo]", "", ignoreCase = true)
            .replace("(Sub Indo)", "", ignoreCase = true)
            .replace("| Muse Indonesia", "", ignoreCase = true)
            .replace("| Ani-One Asia", "", ignoreCase = true)
            .trim()
    }

    fun hydrateFromTmdb(context: Context, item: Entry): Boolean {
        if (item.tmdbId <= 0 || Tmdb.key(context).isEmpty()) return false
        if (item.posterUrl != null && item.overview.isNotEmpty()) return false
        val json = Tmdb.get(context, "/${item.tmdbKind}/${item.tmdbId}", "language=id-ID") ?: return false
        return try {
            val o = JSONObject(json)
            val pp = o.optString("poster_path", "")
            val bp = o.optString("backdrop_path", "")
            val ov = o.optString("overview", "").trim()
            val dt = o.optString("first_air_date", o.optString("release_date", ""))
            val rt = o.optDouble("vote_average", 0.0)
            if (pp.isNotEmpty() && pp != "null") item.posterUrl = "https://image.tmdb.org/t/p/w342$pp"
            if (bp.isNotEmpty() && bp != "null") item.backdropUrl = "https://image.tmdb.org/t/p/w1280$bp"
            if (ov.isNotEmpty()) item.overview = ov
            if (dt.length >= 4) item.year = dt.substring(0, 4)
            if (rt > 0.0) item.rating = rt
            true
        } catch (_: Exception) {
            false
        }
    }

    fun fallbackPoster(context: Context, item: Entry): Drawable {
        val topColor = when {
            item.channelName.contains("Muse", true) -> 0xFF2B2118.toInt()
            item.channelName.contains("Ani-One", true) -> 0xFF1A2530.toInt()
            item.channelName.contains("WeTV", true) -> 0xFF2A1D24.toInt()
            else -> 0xFF1E1C19.toInt()
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, 0xFF0E0D0B.toInt())
        ).apply {
            cornerRadius = Ui.dp(context, 10).toFloat()
            setStroke(Ui.dp(context, 1), 0x44B8975A)
        }
    }

    fun openYoutubeSearch(context: Context, query: String, fallbackUrl: String, onError: () -> Unit) {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        openUrl(context, url) {
            openUrl(context, fallbackUrl, onError)
        }
    }

    fun openUrl(context: Context, url: String, onError: () -> Unit) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {
            onError()
        }
    }
}
