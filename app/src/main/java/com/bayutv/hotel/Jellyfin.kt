package com.bayutv.hotel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * Klien kecil untuk server Jellyfin milik sendiri (gratis, open source).
 * Film/anime yang Anda simpan di PC/NAS diputar di TV tanpa iklan, lengkap dengan subtitle Indonesia.
 */
object Jellyfin {

    class Sub(val url: String, val lang: String, val label: String)
    class Playable(val url: String, val fallback: String, val subs: List<Sub>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)

    fun base(ctx: Context): String = (prefs(ctx).getString("jf_url", "") ?: "").trim().trimEnd('/')

    fun configured(ctx: Context): Boolean =
        base(ctx).startsWith("http") && (prefs(ctx).getString("jf_user", "") ?: "").isNotEmpty()

    private fun deviceId(ctx: Context): String {
        var d = prefs(ctx).getString("jf_dev", null)
        if (d == null) {
            d = UUID.randomUUID().toString()
            prefs(ctx).edit().putString("jf_dev", d).apply()
        }
        return d
    }

    private fun auth(ctx: Context, token: String?): String {
        var s = "MediaBrowser Client=\"Bayu TV\", Device=\"Android TV\", DeviceId=\"" + deviceId(ctx) +
            "\", Version=\"" + BuildConfig.VERSION_NAME + "\""
        if (token != null) s += ", Token=\"" + token + "\""
        return s
    }

    @Synchronized
    fun login(ctx: Context): Boolean {
        val p = prefs(ctx)
        return try {
            val c = URL(base(ctx) + "/Users/AuthenticateByName").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", auth(ctx, null))
            c.setRequestProperty("X-Emby-Authorization", auth(ctx, null))
            val body = JSONObject()
            body.put("Username", p.getString("jf_user", "") ?: "")
            body.put("Pw", p.getString("jf_pass", "") ?: "")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return false
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            p.edit()
                .putString("jf_token", o.getString("AccessToken"))
                .putString("jf_uid", o.getJSONObject("User").getString("Id"))
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun token(ctx: Context): String? = prefs(ctx).getString("jf_token", null)
    private fun uid(ctx: Context): String = prefs(ctx).getString("jf_uid", "") ?: ""

    private fun request(ctx: Context, path: String): String? {
        if (!configured(ctx)) return null
        if (token(ctx) == null && !login(ctx)) return null
        for (attempt in 0..1) {
            try {
                // path bisa memuat {uid}; diganti setelah login
                val c = URL(base(ctx) + path.replace("{uid}", uid(ctx))).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                c.setRequestProperty("Authorization", auth(ctx, token(ctx)))
                c.setRequestProperty("X-Emby-Token", token(ctx) ?: "")
                val code = c.responseCode
                if (code == 401 && attempt == 0) {
                    if (!login(ctx)) return null
                    continue
                }
                if (code != 200) return null
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun image(ctx: Context, id: String): String =
        base(ctx) + "/Items/" + id + "/Images/Primary?maxHeight=330&quality=80"

    private fun toMovies(ctx: Context, json: String?): List<Movie>? {
        if (json == null) return null
        return try {
            val arr: JSONArray = JSONObject(json).optJSONArray("Items") ?: JSONArray(json)
            val out = ArrayList<Movie>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("Id")
                val type = o.optString("Type")
                var title = o.optString("Name")
                val kind = when (type) {
                    "Series" -> "jf_series"
                    "Episode" -> "jf_episode"
                    else -> "jf_movie"
                }
                var img = id
                if (type == "Episode") {
                    val s = o.optInt("ParentIndexNumber", 0)
                    val e = o.optInt("IndexNumber", 0)
                    title = String.format(Locale.US, "S%02dE%02d · ", s, e) + title
                    val tags = o.optJSONObject("ImageTags")
                    if (tags == null || !tags.has("Primary")) img = o.optString("SeriesId", id)
                }
                val year = o.optInt("ProductionYear", 0)
                out.add(
                    Movie(
                        0, title, if (year > 0) year.toString() else "", image(ctx, img),
                        o.optDouble("CommunityRating", 0.0), o.optString("Overview", ""), kind, id
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private const val FIELDS = "&Fields=Overview,ProductionYear&EnableImageTypes=Primary&ImageTypeLimit=1"

    fun latest(ctx: Context): List<Movie>? = toMovies(
        ctx, request(
            ctx, "/Users/{uid}/Items?IncludeItemTypes=Movie,Series&Recursive=true&SortBy=DateCreated" +
                "&SortOrder=Descending&Limit=120" + FIELDS
        )
    )

    fun search(ctx: Context, q: String): List<Movie>? = toMovies(
        ctx, request(
            ctx, "/Users/{uid}/Items?IncludeItemTypes=Movie,Series&Recursive=true&Limit=30&searchTerm=" +
                URLEncoder.encode(q, "UTF-8") + FIELDS
        )
    )

    fun episodes(ctx: Context, seriesId: String): List<Movie>? = toMovies(
        ctx, request(ctx, "/Shows/" + seriesId + "/Episodes?UserId={uid}" + FIELDS)
    )

    fun playable(ctx: Context, id: String): Playable? {
        val json = request(ctx, "/Users/{uid}/Items/" + id) ?: return null
        return try {
            val o = JSONObject(json)
            val ms = o.getJSONArray("MediaSources").getJSONObject(0)
            val msId = ms.optString("Id", id)
            val tk = token(ctx) ?: return null
            val b = base(ctx)
            val subs = ArrayList<Sub>()
            val streams = ms.optJSONArray("MediaStreams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    if (s.optString("Type") != "Subtitle") continue
                    if (!s.optBoolean("IsTextSubtitleStream", false)) continue
                    val idx = s.optInt("Index")
                    val lang = s.optString("Language", "und").ifEmpty { "und" }
                    val label = s.optString("DisplayTitle", lang)
                    subs.add(Sub(b + "/Videos/" + id + "/" + msId + "/Subtitles/" + idx + "/Stream.vtt?api_key=" + tk, lang, label))
                }
            }
            val direct = b + "/Videos/" + id + "/stream?static=true&MediaSourceId=" + msId + "&api_key=" + tk
            val hls = b + "/Videos/" + id + "/master.m3u8?MediaSourceId=" + msId + "&api_key=" + tk +
                "&DeviceId=" + deviceId(ctx) + "&PlaySessionId=" + UUID.randomUUID() +
                "&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=10000000&TranscodingMaxAudioChannels=2&SegmentContainer=ts"
            Playable(direct, hls, subs)
        } catch (e: Exception) {
            null
        }
    }
}
