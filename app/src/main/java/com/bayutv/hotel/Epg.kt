package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jadwal acara (EPG) yang disusun GitHub Actions menjadi epg.json di branch data.
 * Format: {"k": {"sctv": [[mulai, selesai, "judul"], ...]}} dengan waktu dalam menit sejak 1970 (UTC).
 */
object Epg {
    class Show(val start: Long, val end: Long, val title: String)

    @Volatile
    private var map: Map<String, List<Show>> = emptyMap()
    private var loadedAt = 0L

    fun url(): String = "https://raw.githubusercontent.com/" + BuildConfig.REPO + "/data/epg.json"

    private fun file(ctx: Context) = File(ctx.filesDir, "epg.json")

    /** Unduh bila berumur lebih dari 6 jam (dipanggil dari thread latar). */
    fun refresh(ctx: Context) {
        val f = file(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 6L * 3600000L) return
        val t = Net.get(url(), 20000) ?: return
        if (t.length > 20 && t.trimStart().startsWith("{")) {
            f.writeText(t)
            loadedAt = 0L
        }
    }

    /** Muat ke memori (dipanggil dari thread latar). */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists() || f.lastModified() == loadedAt) return
        try {
            val k = JSONObject(f.readText()).getJSONObject("k")
            val out = HashMap<String, List<Show>>()
            val it = k.keys()
            while (it.hasNext()) {
                val key = it.next()
                val arr = k.getJSONArray(key)
                val list = ArrayList<Show>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list.add(Show(a.getLong(0), a.getLong(1), a.getString(2)))
                }
                out[key] = list
            }
            map = out
            loadedAt = f.lastModified()
        } catch (e: Exception) {
        }
    }

    fun has(): Boolean = map.isNotEmpty()

    /** Acara yang sedang tayang dan berikutnya untuk nama channel ini. */
    fun nowNext(name: String): Pair<Show?, Show?> {
        val list = map[AppLinks.mergeKey(name)] ?: return Pair(null, null)
        val now = System.currentTimeMillis() / 60000L
        val i = list.indexOfFirst { it.end > now }
        if (i < 0) return Pair(null, null)
        val cur = if (list[i].start <= now) list[i] else null
        val nxt = if (cur != null) list.getOrNull(i + 1) else list[i]
        return Pair(cur, nxt)
    }

    fun clock(min: Long): String = SimpleDateFormat("HH.mm", Locale.US).format(Date(min * 60000L))

    fun line(name: String): String {
        val (cur, nxt) = nowNext(name)
        val sb = StringBuilder()
        if (cur != null) sb.append(cur.title).append(" (s/d ").append(clock(cur.end)).append(")")
        if (nxt != null) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append("Berikutnya ").append(clock(nxt.start)).append(" ").append(nxt.title)
        }
        return sb.toString()
    }
}
