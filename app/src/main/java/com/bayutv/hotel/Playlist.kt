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
    val userAgent: String?
) {
    val key: String get() = Playlist.key(name)
}

object Playlist {
    // Playlist Indonesia dari iptv-org. Kode negara lain: us, my, sg, jp, dst.
    const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/id.m3u"

    fun key(name: String): String {
        val k = name.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (k.isEmpty()) name.lowercase() else k
    }

    fun clean(v: String?): String? {
        if (v == null) return null
        val c = v.filter { it.code in 32..126 }.trim()
        return if (c.isEmpty()) null else c
    }

    private fun attr(line: String, k: String): String? {
        val v = Regex(k + "=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
        return if (v.isNullOrEmpty()) null else v
    }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var ref: String? = null
        var ua: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
                ref = null
                ua = null
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                ref = line.substringAfter("=")
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                ua = line.substringAfter("=")
            } else if (!line.startsWith("#") && name != null) {
                out.add(Channel(name, line, logo, group, ref, ua))
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

    fun loadCached(ctx: Context): String? {
        val f = cacheFile(ctx)
        return if (f.exists()) f.readText() else null
    }

    // Hasil pindai: nama channel -> alamat yang aktif (kosong = tidak ada yang aktif)
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

    fun saveScan(ctx: Context, m: Map<String, String>) {
        File(ctx.filesDir, "scan.txt").writeText(m.entries.joinToString("\n") { it.key + "\t" + it.value })
    }

    // ---- Uji satu alamat ----
    private fun fetch(u: String, ch: Channel, max: Int): ByteArray? {
        var url = u
        for (hop in 0..4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", clean(ch.userAgent) ?: "Mozilla/5.0 (Linux; Android 14) BayuTV")
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
        return try {
            val first = fetch(ch.url, ch, 8192) ?: return false
            var body = String(first, Charsets.ISO_8859_1)
            if (!body.trimStart().startsWith("#EXTM3U")) return first.size >= 188
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
