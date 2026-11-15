package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * kind: "movie" / "tv" (katalog TMDB), "jf_movie" / "jf_series" / "jf_episode" (Koleksi Saya di Jellyfin)
 * poster: jalur TMDB ("/abc.jpg") atau alamat lengkap untuk Jellyfin.
 */
data class Movie(
    val id: Int,
    val title: String,
    val year: String,
    val poster: String?,
    val rating: Double,
    val overview: String,
    val kind: String = "movie",
    val jfId: String = ""
) {
    val isJellyfin: Boolean get() = kind.startsWith("jf_")
}

// Data film dari TMDB (gratis untuk pemakaian non-komersial dengan atribusi).
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"

    fun key(ctx: Context): String =
        (ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).getString("tmdb", "") ?: "").trim()

    fun get(ctx: Context, path: String, query: String): String? {
        val k = key(ctx)
        if (k.isEmpty()) return null
        return try {
            val bearer = k.length > 60
            val url = BASE + path + "?" + query + (if (bearer) "" else "&api_key=" + URLEncoder.encode(k, "UTF-8"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + k)
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    fun toMovie(o: JSONObject, defKind: String = "movie"): Movie? {
        if (o.optString("media_type") == "person") return null
        val title = o.optString("title", "").ifEmpty { o.optString("name", "") }
        if (title.isEmpty()) return null
        val date = o.optString("release_date", "").ifEmpty { o.optString("first_air_date", "") }
        val p = o.optString("poster_path", "")
        var kind = o.optString("kind", "").ifEmpty { o.optString("media_type", "") }
        if (kind != "movie" && kind != "tv") kind = if (!o.has("title") && o.has("name")) "tv" else defKind
        return Movie(
            o.optInt("id"),
            title,
            if (date.length >= 4) date.substring(0, 4) else "",
            if (p.isEmpty() || p == "null") null else p,
            o.optDouble("vote_average", 0.0),
            o.optString("overview", ""),
            kind
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
}
