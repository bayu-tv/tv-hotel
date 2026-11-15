package com.bayutv.hotel

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val referrer: String?,
    val userAgent: String?,
    val region: String = "Indonesia",
    val category: String = "Lainnya",
    val status: String = "",
    // Diisi hanya untuk channel antena (tuner DVB-T2 bawaan TV)
    val tunerInput: String? = null,
    val tunerId: Long = -1L,
    val number: String = ""
) {
    val key: String = if (tunerInput != null) "ant:" + tunerId else Playlist.key(name)
    val isAntenna: Boolean get() = tunerInput != null
}

object Playlist {
    // Daftar yang sudah diperiksa GitHub Actions (branch "data" di repo aplikasi ini)
    fun serverUrl(): String = "https://raw.githubusercontent.com/" + BuildConfig.REPO + "/data/channels.m3u"

    // Cadangan bila branch data belum ada: playlist negara dari iptv-org
    fun fallbackUrl(ctx: Context): String {
        val cc = (prefs(ctx).getString("country", "id") ?: "id").trim().lowercase().filter { it in 'a'..'z' }
        return "https://iptv-org.github.io/iptv/countries/" + (if (cc.length == 2) cc else "id") + ".m3u"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)

    private val reBrackets = Regex("\\(.*?\\)|\\[.*?\\]")
    private val reSpaces = Regex("\\s+")

    fun key(name: String): String {
        val k = name.lowercase()
            .replace(reBrackets, " ")
            .replace(reSpaces, " ")
            .trim()
        return if (k.isEmpty()) name.lowercase() else k
    }

    private val reTags = Regex("\\s*\\[.*?\\]")
    private val reRes = Regex("\\s*\\(\\d{3,4}[pi]\\)")

    // Nama tampil tanpa "(720p)" dan "[Not 24/7]"
    fun display(name: String): String {
        val d = name.replace(reTags, "").replace(reRes, "").trim()
        return if (d.isEmpty()) name else d
    }

    fun clean(v: String?): String? {
        if (v == null) return null
        val c = v.filter { it.code in 32..126 }.trim()
        return if (c.isEmpty()) null else c
    }

    // Regex disiapkan sekali saja (daftar bisa berisi ribuan baris; TV cukup lambat)
    private val attrRe = HashMap<String, Regex>()

    private fun attr(line: String, k: String): String? {
        val re = synchronized(attrRe) { attrRe.getOrPut(k) { Regex(k + "=\"([^\"]*)\"") } }
        val v = re.find(line)?.groupValues?.get(1)
        return if (v.isNullOrEmpty()) null else v
    }

    // Kategori iptv-org (bahasa Inggris) -> kategori aplikasi
    fun mapCategory(g: String?): String {
        if (g.isNullOrEmpty()) return "Lainnya"
        val first = g.split(";")[0].trim().lowercase()
        return when (first) {
            "news", "business", "weather" -> "Berita"
            "general" -> "Nasional"
            "sports" -> "Olahraga"
            "kids", "animation", "family" -> "Anak & Kartun"
            "movies", "series", "classic" -> "Film & Serial"
            "music", "relax" -> "Musik"
            "religious" -> "Religi"
            "documentary", "science", "travel", "outdoor" -> "Dokumenter"
            "education", "culture" -> "Edukasi"
            "legislative" -> "Pemerintahan"
            "entertainment", "lifestyle", "comedy", "cooking", "auto" -> "Hiburan"
            else -> "Lainnya"
        }
    }

    fun parse(text: String, defaultRegion: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var ref: String? = null
        var ua: String? = null
        var status = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
                status = attr(line, "bayu-status") ?: ""
                ref = null
                ua = null
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                ref = line.substringAfter("=")
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                ua = line.substringAfter("=")
            } else if (!line.startsWith("#") && name != null) {
                var region = defaultRegion
                var cat: String
                val g = group
                if (g != null && g.contains("/")) {
                    region = g.substringBefore("/")
                    cat = g.substringAfter("/")
                } else {
                    cat = mapCategory(g)
                }
                if (cat.isEmpty()) cat = "Lainnya"
                out.add(Channel(name, line, logo, group, ref, ua, region, cat, status))
                name = null
            }
        }
        return out
    }

    fun group(list: List<Channel>): LinkedHashMap<String, MutableList<Channel>> {
        val m = LinkedHashMap<String, MutableList<Channel>>()
        for (c in list) m.getOrPut(c.key) { ArrayList() }.add(c)
        return m
    }

    fun cacheFile(ctx: Context): File = File(ctx.filesDir, "channels.m3u")
    private fun customFile(ctx: Context): File = File(ctx.filesDir, "custom.m3u")

    fun loadCached(ctx: Context): String? {
        val f = cacheFile(ctx)
        return if (f.exists()) f.readText() else null
    }

    private fun httpText(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 25000
            c.setRequestProperty("User-Agent", "BayuTV")
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /** Unduh daftar terbaru. Mengembalikan true bila daftar utama berhasil diperbarui. */
    fun download(ctx: Context): Boolean {
        var main = httpText(serverUrl())
        if (main == null || main.length < 100 || !main.contains("#EXTINF")) {
            main = httpText(fallbackUrl(ctx))
        }
        val ok = main != null && main.length > 100 && main.contains("#EXTINF")
        if (ok) cacheFile(ctx).writeText(main!!)
        val custom = (prefs(ctx).getString("playlist", "") ?: "").trim()
        if (custom.startsWith("http://") || custom.startsWith("https://")) {
            val t = httpText(custom)
            if (t != null && t.contains("#EXTINF")) customFile(ctx).writeText(t)
        } else {
            customFile(ctx).delete()
        }
        if (ok) prefs(ctx).edit().putLong("list_at", System.currentTimeMillis()).apply()
        return ok
    }

    /** Semua channel internet dari cache (daftar utama + playlist saya). */
    fun loadAll(ctx: Context): List<Channel> {
        val out = ArrayList<Channel>()
        val main = loadCached(ctx)
        if (main != null) out.addAll(parse(main, "Indonesia"))
        val cf = customFile(ctx)
        if (cf.exists()) {
            for (c in parse(cf.readText(), "Playlist Saya")) {
                out.add(if (c.group != null && c.group.contains("/")) c else c.copy(region = "Playlist Saya"))
            }
        }
        return out
    }

    fun ageHours(ctx: Context): Long {
        val t = prefs(ctx).getLong("list_at", 0L)
        if (t == 0L) return 9999L
        return (System.currentTimeMillis() - t) / 3600000L
    }

    // Hasil pindai: kunci channel -> alamat yang aktif (kosong = mati, disembunyikan)
    fun loadScan(ctx: Context): MutableMap<String, String> {
        val m = HashMap<String, String>()
        val f = File(ctx.filesDir, "scan.txt")
        if (!f.exists()) return m
        for (line in f.readLines()) {
            val i = line.indexOf('\t')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }

    @Synchronized
    fun saveScan(ctx: Context, m: Map<String, String>) {
        val f = File(ctx.filesDir, "scan.txt.tmp")
        f.writeText(m.entries.joinToString("\n") { it.key + "\t" + it.value })
        f.renameTo(File(ctx.filesDir, "scan.txt"))
    }

    // ---- Favorit ----
    fun favorites(ctx: Context): MutableSet<String> = HashSet(prefs(ctx).getStringSet("fav", emptySet()) ?: emptySet())

    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val s = favorites(ctx)
        val added = if (s.contains(key)) {
            s.remove(key)
            false
        } else {
            s.add(key)
            true
        }
        prefs(ctx).edit().putStringSet("fav", s).apply()
        return added
    }

    // ---- Uji satu alamat ----
    private fun fetch(u: String, ch: Channel, max: Int): ByteArray? {
        var url = u
        for (hop in 0..4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", clean(ch.userAgent) ?: "Mozilla/5.0 (Linux; Android 11) BayuTV")
            val ref = clean(ch.referrer)
            if (ref != null) c.setRequestProperty("Referer", ref)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) return null
                url = URL(URL(url), loc).toString()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                return null
            }
            val buf = ByteArray(max)
            var n = 0
            c.inputStream.use { s ->
                while (n < max) {
                    val r = s.read(buf, n, max - n)
                    if (r < 0) break
                    n += r
                }
            }
            c.disconnect()
            return buf.copyOf(n)
        }
        return null
    }

    fun check(ch: Channel): Boolean {
        if (ch.isAntenna) return true
        return try {
            val first = fetch(ch.url, ch, 8192) ?: return false
            var body = String(first, Charsets.ISO_8859_1)
            if (!body.trimStart().startsWith("#EXTM3U")) {
                if (body.trimStart().startsWith("<") && body.contains("MPD")) return true
                return first.size >= 188
            }
            var base = ch.url
            for (depth in 0..2) {
                val next = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
                val full = URL(URL(base), next).toString()
                val isMaster = body.contains("#EXT-X-STREAM-INF")
                val r = fetch(full, ch, if (isMaster) 8192 else 2048) ?: return false
                if (!isMaster) return r.isNotEmpty()
                base = full
                body = String(r, Charsets.ISO_8859_1)
                if (!body.trimStart().startsWith("#EXTM3U")) return r.size >= 188
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
