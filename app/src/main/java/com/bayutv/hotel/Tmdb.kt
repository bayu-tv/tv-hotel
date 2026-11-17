package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * kind: "movie" / "tv" (katalog TMDB), "jf_movie" / "jf_series" / "jf_episode" (Koleksi Saya di Jellyfin)
 * poster & backdrop: jalur TMDB ("/abc.jpg") atau alamat lengkap untuk Jellyfin.
 */
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

// Data film dari TMDB (gratis untuk pemakaian non-komersial dengan atribusi).
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

    fun imdbId(ctx: Context, tmdbId: Int, kind: String = "movie"): String? {
        if (tmdbId <= 0) return null
        val path = "/" + (if (kind == "tv") "tv" else "movie") + "/$tmdbId/external_ids"
        val json = get(ctx, path, "") ?: return null
        return try {
            val id = JSONObject(json).optString("imdb_id", "").trim()
            if (id.startsWith("tt")) id else null
        } catch (_: Exception) {
            null
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
            o.optInt("id
