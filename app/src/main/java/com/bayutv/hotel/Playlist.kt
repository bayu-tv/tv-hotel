package com.bayutv.hotel

import android.content.Context
import java.io.File
import java.util.TimeZone
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
    val number: String = "",
    val origin: String? = null
) {
    val key: String = if (tunerInput != null) "ant:" + tunerId else Playlist.key(name)
    val isAntenna: Boolean get() = tunerInput != null
    // Pintasan ke aplikasi resmi (RCTI+/Vidio); url berbentuk "app:NAMA"
    val isApp: Boolean get() = url.startsWith("app:")
}

object Playlist {
    // Identitas browser Android TV, dipakai bila playlist tidak mencantumkan User-Agent
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36"

    /** Header permintaan untuk satu channel: User-Agent, Referer, dan Origin (diturunkan dari Referer bila kosong). */
    fun headers(ch: Channel): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = clean(ch.userAgent) ?: DEFAULT_UA
        val ref = clean(ch.referrer)
        if (ref != null) m["Referer"] = ref
        val org = clean(ch.origin) ?: ref?.let { originOf(it) }
        if (org != null) m["Origin"] = org
        return m
    }

    private fun originOf(url: String): String? {
        return try {
            val u = URL(url)
            u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Format "alamat|Referer=...&User-Agent=..." yang umum di playlist komunitas.
     * Mengembalikan (alamat bersih, referer, user-agent, origin).
     */
    fun splitPipe(raw: String): Array<String?> {
        val i = raw.indexOf('|')
        if (i < 0) return arrayOf(raw, null, null, null)
        var ref: String? = null
        var ua: String? = null
        var org: String? = null
        for (part in raw.substring(i + 1).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim().lowercase()
            val v = try {
                java.net.URLDecoder.decode(part.substring(eq + 1).trim(), "UTF-8")
            } catch (e: Exception) {
                part.substring(eq + 1).trim()
            }
            when (k) {
                "referer", "referrer" -> ref = v
                "user-agent", "useragent" -> ua = v
                "origin" -> org = v
            }
        }
        return arrayOf(raw.substring(0, i).trim(), ref, ua, org)
    }
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
        var org: String? = null
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
                org = null
            } else if (line.startsWith("#EXTVLCOPT:http-origin=")) {
                org = line.substringAfter("=")
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
                val sp = splitPipe(line)
                val url = sp[0] ?: line
                out.add(
                    Channel(
                        name, url, logo, group, sp[1] ?: ref, sp[2] ?: ua, region, cat, status,
                        null, -1L, "", sp[3] ?: org
                    )
                )
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

    // ---- Catatan gagal putar ----
    // Channel baru disembunyikan setelah gagal pada 2 hari berbeda, lalu dicoba lagi otomatis setelah 3 hari.
    // Nilai: [jumlah hari gagal, hari terakhir gagal, waktu mulai disembunyikan (ms)]
    private const val RETRY_MS = 3L * 24L * 3600000L

    fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 86400000L
    }

    fun loadFails(ctx: Context): MutableMap<String, LongArray> {
        val m = HashMap<String, LongArray>()
        val f = File(ctx.filesDir, "fails.txt")
        if (!f.exists()) return m
        for (line in f.readLines()) {
            val p = line.split('\t')
            if (p.size < 4) continue
            val a = p[1].toLongOrNull() ?: continue
            val b = p[2].toLongOrNull() ?: continue
            val c = p[3].toLongOrNull() ?: continue
            m[p[0]] = longArrayOf(a, b, c)
        }
        return m
    }

    @Synchronized
    fun saveFails(ctx: Context, m: Map<String, LongArray>) {
        val f = File(ctx.filesDir, "fails.txt.tmp")
        f.writeText(m.entries.joinToString("\n") { it.key + "\t" + it.value[0] + "\t" + it.value[1] + "\t" + it.value[2] })
        f.renameTo(File(ctx.filesDir, "fails.txt"))
    }

    fun recordFail(m: MutableMap<String, LongArray>, key: String) {
        val v = m[key] ?: longArrayOf(0L, 0L, 0L)
        val d = today()
        if (v[1] != d) {
            v[0] = v[0] + 1
            v[1] = d
        }
        val now = System.currentTimeMillis()
        if (v[0] >= 2 && (v[2] == 0L || now - v[2] >= RETRY_MS)) v[2] = now
        m[key] = v
    }

    fun hiddenByFails(v: LongArray?): Boolean {
        if (v == null || v[0] < 2 || v[2] == 0L) return false
        return System.currentTimeMillis() - v[2] < RETRY_MS
    }

    fun clearFails(ctx: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val m = loadFails(ctx)
        var changed = false
        for (k in keys) if (m.remove(k) != null) changed = true
        if (changed) saveFails(ctx, m)
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
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            for ((k, v) in headers(ch)) c.setRequestProperty(k, v)
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
