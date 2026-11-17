package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Movie(
    val id: Int,
    val title: String,
    val year: String,
    val poster: String?,
    val rating: Double,
    val overview: String,
    val kind: String = "movie",
    val jfId: String = "",
    val backdrop: String? = null
) {
    val isJellyfin: Boolean get() = kind.startsWith("jf_")
}

data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String
)

object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private val cache = LinkedHashMap<String, Pair<Long, String>>()
    private const val CACHE_TTL = 15L * 60L * 1000L

    fun key(ctx: Context): String =
        (ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).getString("tmdb", "") ?: "").trim()

    fun get(ctx: Context, path: String, query: String): String? {
        val k = key(ctx)
        if (k.isEmpty()) return null
        val cacheKey = "$path?$query"
        synchronized(cache) {
            val hit = cache[cacheKey]
            if (hit != null && System.currentTimeMillis() - hit.first < CACHE_TTL) {
                return hit.second
            }
        }
        return try {
            val bearer = k.length > 60
            val url = BASE + path + "?" + query + (if (bearer) "" else "&api_key=" + URLEncoder.encode(k, "UTF-8"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + k)
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            synchronized(cache) {
                if (cache.size >= 24) {
                    val oldest = cache.keys.firstOrNull()
                    if (oldest != null) cache.remove(oldest)
                }
                cache[cacheKey] = Pair(System.currentTimeMillis(), body)
            }
            body
        } catch (e: Exception) {
            null
        }
    }

    fun imdbId(ctx: Context, id: Int, kind: String = "movie"): String {
        if (id <= 0) return ""
        val path = "/" + (if (kind == "tv") "tv" else "movie") + "/$id/external_ids"
        val json = get(ctx, path, "") ?: return ""
        return try {
            val imdb = JSONObject(json).optString("imdb_id", "").trim()
            if (imdb.startsWith("tt")) imdb else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun toMovie(o: JSONObject, defKind: String = "movie"): Movie? {
        if (o.optString("media_type") == "person") return null
        val title = o.optString("title", "").ifEmpty { o.optString("name", "") }
        if (title.isEmpty()) return null
        val date = o.optString("release_date", "").ifEmpty { o.optString("first_air_date", "") }
        val p = o.optString("poster_path", "")
        val b = o.optString("backdrop_path", "")
        var kind = o.optString("kind", "").ifEmpty { o.optString("media_type", "") }
        if (kind != "movie" && kind != "tv") kind = if (!o.has("title") && o.has("name")) "tv" else defKind
        return Movie(
            o.optInt("id"),
            title,
            if (date.length >= 4) date.substring(0, 4) else "",
            if (p.isEmpty() || p == "null") null else p,
            o.optDouble("vote_average", 0.0),
            o.optString("overview", ""),
            kind,
            "",
            if (b.isEmpty() || b == "null") null else b
        )
    }

    fun parseList(json: String, defKind: String = "movie"): List<Movie> {
        val out = ArrayList<Movie>()
        val arr = JSONObject(json).optJSONArray("results") ?: return out
        for (i in 0 until arr.length()) {
            val m = toMovie(arr.getJSONObject(i), defKind)
            if (m != null) out.add(m)
        }
        return out
    }

    fun episodes(ctx: Context, tvId: Int, season: Int): List<EpisodeItem> {
        val json = get(ctx, "/tv/$tvId/season/$season", "language=id-ID") ?: return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("episodes") ?: return emptyList()
            val out = ArrayList<EpisodeItem>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val epNum = e.optInt("episode_number", i + 1)
                val name = e.optString("name", "Episode $epNum").ifEmpty { "Episode $epNum" }
                out.add(EpisodeItem(season, epNum, "E$epNum - $name", e.optString("overview", "")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
