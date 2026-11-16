package com.bayutv.hotel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONObject
import java.net.URLEncoder

object YoutubeCatalog {

    data class Entry(
        val tmdbId: Int,          // 0 jika kartu kanal utama
        val title: String,
        val channelName: String,
        val category: String,     // "Anime Sub Indo" | "Drama China" | "Kanal Resmi"
        val channelUrl: String,
        val searchQuery: String,
        val accentColor: Int,
        var year: String = "",
        var rating: Double = 0.0,
        var overview: String = "",
        var posterUrl: String? = null,
        var backdropUrl: String? = null
    )

    private val posterCache = HashMap<String, Bitmap>()

    // 1. Anime Populer Sub Indo (Muse Indonesia, Ani-One, POPS)
    val ANIME_PICKS = listOf(
        Entry(209867, "Frieren: Beyond Journey's End", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Frieren Sub Indo", 0xFF2E7D6B.toInt()),
        Entry(120089, "SPY x FAMILY", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Spy x Family Sub Indo", 0xFFB85A6A.toInt()),
        Entry(85937, "Demon Slayer: Kimetsu no Yaiba", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Demon Slayer Sub Indo", 0xFF8C3B3B.toInt()),
        Entry(1429, "Attack on Titan", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Attack on Titan Sub Indo", 0xFF6B533B.toInt()),
        Entry(63926, "One-Punch Man", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia One Punch Man Sub Indo", 0xFFB88A3B.toInt()),
        Entry(46298, "Hunter x Hunter", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Hunter x Hunter Sub Indo", 0xFF3B7A57.toInt()),
        Entry(82684, "That Time I Got Reincarnated as a Slime", "Muse Indonesia", "Anime Sub Indo", "https://www.youtube.com/@MuseIndonesia/playlists", "Muse Indonesia Tensura Slime Sub Indo", 0xFF3B6E9C.toInt()),
        Entry(95479, "Jujutsu Kaisen", "Ani-One Asia", "Anime Sub Indo", "https://www.youtube.com/@AniOneAsia/playlists", "Ani-One Jujutsu Kaisen Sub Indo", 0xFF4A4E8C.toInt()),
        Entry(60863, "Haikyu!!", "Ani-One Asia", "Anime Sub Indo", "https://www.youtube.com/@AniOneAsia/playlists", "Ani-One Haikyu Sub Indo", 0xFFC26A38.toInt()),
        Entry(30983, "Detective Conan", "POPS Anime Indonesia", "Anime Sub Indo", "https://www.youtube.com/@POPSAnimeIndonesia/playlists", "POPS Anime Indonesia Detective Conan", 0xFF355C8C.toInt())
    )

    // 2. Drama China (Dracin) Populer Sub Indo (WeTV, iQIYI, YOUKU, Huace)
    val DRACIN_PICKS = listOf(
        Entry(210733, "Hidden Love", "YOUKU Indonesia", "Drama China", "https://www.youtube.com/@YOUKUIndonesia/playlists", "YOUKU Indonesia Hidden Love Sub Indo", 0xFFB86B84.toInt()),
        Entry(212204, "When I Fly Towards You", "YOUKU Indonesia", "Drama China", "https://www.youtube.com/@YOUKUIndonesia/playlists", "YOUKU Indonesia When I Fly Towards You Sub Indo", 0xFF4C7FA8.toInt()),
        Entry(137887, "Love Between Fairy and Devil", "iQIYI Indonesia", "Drama China", "https://www.youtube.com/@iQIYIIndonesia/playlists", "iQIYI Indonesia Love Between Fairy and Devil Sub Indo", 0xFF2E8555.toInt()),
        Entry(136243, "Till The End of The Moon", "YOUKU Indonesia", "Drama China", "https://www.youtube.com/@YOUKUIndonesia/playlists", "YOUKU Indonesia Till The End of The Moon Sub Indo", 0xFF6B4C9A.toInt()),
        Entry(88040, "Put Your Head on My Shoulder", "WeTV Indonesia", "Drama China", "https://www.youtube.com/@WeTVIndonesia/playlists", "WeTV Indonesia Put Your Head on My Shoulder Sub Indo", 0xFFC27438.toInt()),
        Entry(91254, "The Untamed", "WeTV Indonesia", "Drama China", "https://www.youtube.com/@WeTVIndonesia/playlists", "WeTV Indonesia The Untamed Sub Indo", 0xFF3A6B7C.toInt()),
        Entry(117376, "Falling Into Your Smile", "YOUKU Indonesia", "Drama China", "https://www.youtube.com/@YOUKUIndonesia/playlists", "YOUKU Indonesia Falling Into Your Smile Sub Indo", 0xFF8C4A6B.toInt()),
        Entry(106651, "Go Ahead", "Huace Croton TV ID", "Drama China", "https://www.youtube.com/@HuaceCrotonTVIndonesia/playlists", "Huace Croton TV Indonesia Go Ahead Sub Indo", 0xFF9C6B3B.toInt()),
        Entry(67518, "Love O2O", "YOUKU Indonesia", "Drama China", "https://www.youtube.com/@YOUKUIndonesia/playlists", "Love O2O Full Episode Sub Indo", 0xFF3B6E8C.toInt()),
        Entry(69889, "Eternal Love", "WeTV Indonesia", "Drama China", "https://www.youtube.com/@WeTVIndonesia/playlists", "Eternal Love Ten Miles of Peach Blossoms Sub Indo", 0xFF9E5A78.toInt())
    )

    // 3. Kanal Distributor Resmi (Buka seluruh daftar putar / playlist)
    val OFFICIAL_CHANNELS = listOf(
        Entry(0, "Muse Indonesia", "Anime Sub Indo 1080p", "Kanal Resmi", "https://www.youtube.com/@MuseIndonesia/playlists", "", 0xFF9E3B3B.toInt(), overview = "Distributor resmi anime Jepang dengan terjemahan Bahasa Indonesia paling populer dan jernih hingga 1080p."),
        Entry(0, "Ani-One Indonesia", "Anime Populer & Ongoing", "Kanal Resmi", "https://www.youtube.com/@AniOneIndonesia/playlists", "", 0xFF2F6E8C.toInt(), overview = "Kanal resmi Medialink Indonesia untuk berbagai judul anime shonen, aksi, dan fantasi Sub Indo."),
        Entry(0, "Ani-One Asia", "Katalog Anime Asia", "Kanal Resmi", "https://www.youtube.com/@AniOneAsia/playlists", "", 0xFF3B4E8C.toInt(), overview = "Katalog anime Asia terlengkap (aktifkan tombol CC Bahasa Indonesia saat memutar)."),
        Entry(0, "WeTV Indonesia", "Drama China Resmi", "Kanal Resmi", "https://www.youtube.com/@WeTVIndonesia/playlists", "", 0xFFB86E2E.toInt(), overview = "Koleksi drama China modern dan kolosal pilihan dengan takarir Bahasa Indonesia resmi dari episode 1 hingga tamat."),
        Entry(0, "iQIYI Indonesia", "Dracin & Romansa", "Kanal Resmi", "https://www.youtube.com/@iQIYIIndonesia/playlists", "", 0xFF257A4B.toInt(), overview = "Serial drama romantis, komedi, dan misteri Tiongkok berkualitas tinggi dengan subtitle Indonesia."),
        Entry(0, "YOUKU Indonesia", "Xianxia & Kolosal", "Kanal Resmi", "https://www.youtube.com/@YOUKUIndonesia/playlists", "", 0xFF5C428C.toInt(), overview = "Spesialis drama fantasi xianxia, wuxia, dan drama remaja Tiongkok populer."),
        Entry(0, "Huace Croton TV ID", "Studio Drama China", "Kanal Resmi", "https://www.youtube.com/@HuaceCrotonTVIndonesia/playlists", "", 0xFF8C5E32.toInt(), overview = "Kanal resmi studio produksi drama terbesar di Tiongkok dengan puluhan seri full episode Sub Indo."),
        Entry(0, "POPS Anime ID", "Anime Keluarga & Anak", "Kanal Resmi", "https://www.youtube.com/@POPSAnimeIndonesia/playlists", "", 0xFF2E6699.toInt(), overview = "Tontonan anime ramah keluarga seperti Detective Conan dan Doraemon dengan pilihan dubbing dan Sub Indo.")
    )

    // Ambil poster, backdrop, rating, dan sinopsis Indonesia dari TMDB
    fun hydrateFromTmdb(ctx: Context, item: Entry): Boolean {
        if (item.tmdbId <= 0 || item.posterUrl != null) return item.posterUrl != null
        if (Tmdb.key(ctx).isEmpty()) return false
        val json = Tmdb.get(ctx, "/tv/${item.tmdbId}", "language=id-ID") ?: return false
        return try {
            val o = JSONObject(json)
            val p = o.optString("poster_path", "")
            val b = o.optString("backdrop_path", "")
            if (p.isNotEmpty() && p != "null") item.posterUrl = "https://image.tmdb.org/t/p/w342$p"
            if (b.isNotEmpty() && b != "null") item.backdropUrl = "https://image.tmdb.org/t/p/w780$b"
            val d = o.optString("first_air_date", "")
            if (d.length >= 4) item.year = d.substring(0, 4)
            item.rating = o.optDouble("vote_average", 0.0)
            val ov = o.optString("overview", "").trim()
            if (ov.isNotEmpty()) {
                item.overview = ov
            } else {
                // Cadangan sinopsis bahasa Inggris jika bahasa Indonesia kosong di TMDB
                val enJson = Tmdb.get(ctx, "/tv/${item.tmdbId}", "language=en-US")
                if (enJson != null) {
                    val enOv = JSONObject(enJson).optString("overview", "").trim()
                    if (enOv.isNotEmpty()) item.overview = enOv
                }
            }
            item.posterUrl != null
        } catch (e: Exception) {
            false
        }
    }

    // Poster grafis sinematik otomatis (untuk kartu kanal atau saat offline)
    fun fallbackPoster(ctx: Context, item: Entry): Drawable {
        val key = "${item.title}_${item.accentColor}"
        posterCache[key]?.let { return BitmapDrawable(ctx.resources, it) }

        val w = 244
        val h = 344
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(item.accentColor, 0xFF171512.toInt(), 0xFF0D0C0B.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Bingkai garis emas tipis di dalam poster
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x66E6C88F
        }
        c.drawRoundRect(RectF(12f, 12f, w - 12f, h - 12f), 14f, 14f, borderPaint)

        // Badge kategori di bagian atas
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE6C88F.toInt()
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText(item.category.uppercase(), 24f, 44f, tagPaint)

        // Ikon play lingkaran di tengah
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
        }
        c.drawCircle(w / 2f, h * 0.42f, 34f, circlePaint)
        val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEFE8DA.toInt()
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("▶", w / 2f + 2f, h * 0.42f + 9f, playPaint)

        // Judul seri / kanal (maksimal 3 baris)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEFE8DA.toInt()
            textSize = 23f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val words = item.title.split(" ")
        val lines = ArrayList<String>()
        var cur = ""
        for (word in words) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (titlePaint.measureText(test) <= w - 48f) {
                cur = test
            } else {
                if (cur.isNotEmpty()) lines.add(cur)
                cur = word
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)

        var y = h - 56f - (lines.take(3).size - 1) * 28f
        for (line in lines.take(3)) {
            c.drawText(line, 24f, y, titlePaint)
            y += 28f
        }

        // Nama kanal di bawah
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA79F90.toInt()
            textSize = 15f
            typeface = Typeface.SANS_SERIF
        }
        c.drawText(item.channelName, 24f, h - 24f, subPaint)

        posterCache[key] = bmp
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun openYoutubeSearch(ctx: Context, query: String, fallbackUrl: String, onError: () -> Unit) {
        val target = if (query.isNotBlank()) {
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        } else {
            fallbackUrl
        }
        openUrl(ctx, target, onError)
    }

    fun openUrl(ctx: Context, url: String, onError: () -> Unit) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            onError()
        }
    }
}
