package com.bayutv.hotel

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class FilmActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private var movies: List<Movie> = emptyList()
    private lateinit var grid: GridView
    private lateinit var heading: TextView
    private lateinit var notice: Notice
    private lateinit var adapter: BaseAdapter
    private val cats = arrayOf("Populer", "Sedang tayang", "Film Indonesia", "Anime", "Akan tayang", "Daftar saya", "Koleksi Saya", "Cari...")
    private val searchIdx = 7
    private val catViews = ArrayList<TextView>()
    private var catIdx = -1
    private var token = 0

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Themes.pick(this).bg)
        val row = LinearLayout(this)
        row.setPadding(dp(40), dp(28), dp(40), dp(16))
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv("Film", 30f, brass, Typeface.SERIF))
        for (i in cats.indices) {
            val t = tv(cats[i], 18f, mute, Typeface.SERIF)
            t.setPadding(dp(16), dp(12), dp(16), dp(12))
            t.isFocusable = true
            t.isClickable = true
            t.setOnFocusChangeListener { _, f ->
                if (f && i < searchIdx) select(i)
                t.setTextColor(if (f) brassLight else if (i == catIdx) brass else mute)
            }
            t.setOnClickListener {
                if (i == searchIdx) askSearch() else grid.requestFocus()
            }
            val lp = LinearLayout.LayoutParams(dp(190), -2)
            lp.topMargin = dp(if (i == 0) 18 else 4)
            left.addView(t, lp)
            catViews.add(t)
        }
        row.addView(left, LinearLayout.LayoutParams(dp(210), -1))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        heading = tv("", 20f, ivory, Typeface.SERIF)
        heading.setPadding(0, 0, 0, dp(10))
        right.addView(heading)

        grid = GridView(this)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dp(132)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = dp(12)
        grid.verticalSpacing = dp(12)
        grid.isFocusable = true
        val sel = GradientDrawable()
        sel.cornerRadius = dp(8).toFloat()
        sel.setColor(0x22B8975A)
        sel.setStroke(dp(2), brass)
        grid.selector = sel
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = movies.size
            override fun getItem(position: Int): Any = movies[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v: LinearLayout
                if (convertView == null) {
                    v = LinearLayout(this@FilmActivity)
                    v.orientation = LinearLayout.VERTICAL
                    v.setPadding(dp(4), dp(4), dp(4), dp(4))
                    val img = ImageView(this@FilmActivity)
                    img.scaleType = ImageView.ScaleType.CENTER_CROP
                    img.setBackgroundColor(0xFF1A1816.toInt())
                    v.addView(img, LinearLayout.LayoutParams(-1, dp(190)))
                    val t = tv("", 13f, ivory, Typeface.SANS_SERIF)
                    t.maxLines = 1
                    t.ellipsize = TextUtils.TruncateAt.END
                    t.setPadding(0, dp(4), 0, 0)
                    v.addView(t)
                    v.addView(tv("", 11f, mute, Typeface.SANS_SERIF))
                } else {
                    v = convertView as LinearLayout
                }
                val m = movies[position]
                Images.load(m.poster, v.getChildAt(0) as ImageView)
                (v.getChildAt(1) as TextView).text = m.title
                val r = if (m.rating > 0) String.format(Locale.US, "%.1f", m.rating) else "-"
                (v.getChildAt(2) as TextView).text = (if (m.year.isEmpty()) "" else m.year + "  \u00B7  ") + "\u2605 " + r
                return v
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> if (pos < movies.size) showDetail(movies[pos]) }
        right.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = tv("Katalog: TMDB (tidak didukung atau disertifikasi oleh TMDB). Ketersediaan layanan: JustWatch. Koleksi Saya: server Jellyfin Anda.", 11f, 0xFF6F685B.toInt(), Typeface.SANS_SERIF)
        foot.setPadding(0, dp(6), 0, 0)
        right.addView(foot)
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        notice = Notice(this, root)
        setContentView(root)
        catViews[0].requestFocus()
        if (Tmdb.key(this).isEmpty()) {
            notice.show("Kunci TMDB belum diisi", "Buka Pengaturan lewat HP, isi bagian Film (kunci gratis dari themoviedb.org), lalu kembali ke sini.")
        }
    }

    private fun select(i: Int) {
        if (i == catIdx) return
        catIdx = i
        for (j in catViews.indices) catViews[j].setTextColor(if (j == i) brassLight else mute)
        heading.text = cats[i]
        when (i) {
            0 -> fetchList("/movie/popular", "language=id-ID&region=ID&page=1", "movie")
            1 -> fetchList("/movie/now_playing", "language=id-ID&region=ID&page=1", "movie")
            2 -> fetchList("/discover/movie", "language=id-ID&with_original_language=id&sort_by=primary_release_date.desc&vote_count.gte=3&page=1", "movie")
            3 -> fetchList("/discover/tv", "language=id-ID&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=1", "tv")
            4 -> fetchList("/movie/upcoming", "language=id-ID&region=ID&page=1", "movie")
            5 -> {
                token++
                movies = loadWatch()
                adapter.notifyDataSetChanged()
                if (movies.isEmpty()) notice.show("Daftar saya kosong", "Buka detail film, lalu pilih Tambah ke daftar.", 3500L)
            }
            6 -> loadCollection()
        }
    }

    private fun fetchList(path: String, query: String, kind: String) {
        if (Tmdb.key(this).isEmpty()) {
            movies = emptyList()
            adapter.notifyDataSetChanged()
            notice.show("Kunci TMDB belum diisi", "Buka Pengaturan lewat HP, isi bagian Film (kunci gratis dari themoviedb.org).", 5000L)
            return
        }
        val my = ++token
        Thread {
            val json = Tmdb.get(this, path, query)
            ui.post {
                if (my == token) {
                    if (json == null) {
                        notice.show("Tidak bisa memuat", "Periksa kunci TMDB di Pengaturan dan koneksi internet TV.", 5000L)
                    } else {
                        notice.hide()
                        movies = Tmdb.parseList(json, kind)
                        adapter.notifyDataSetChanged()
                        grid.setSelection(0)
                    }
                }
            }
        }.start()
    }

    // ---- Koleksi Saya (server Jellyfin) ----
    private fun loadCollection() {
        val my = ++token
        movies = emptyList()
        adapter.notifyDataSetChanged()
        if (!Jellyfin.configured(this)) {
            notice.show(
                "Koleksi Saya belum diatur",
                "Pasang Jellyfin (gratis) di PC/laptop, masukkan film/anime milik Anda beserta subtitle Indonesia (.srt), " +
                    "lalu isi alamat server, nama pengguna, dan sandi di Pengaturan lewat HP."
            )
            return
        }
        notice.show(null, "Memuat Koleksi Saya...")
        Thread {
            val list = Jellyfin.latest(this)
            ui.post {
                if (my == token) {
                    if (list == null) {
                        notice.show("Server tidak terhubung", "Pastikan PC Jellyfin menyala dan alamat, nama pengguna, serta sandi di Pengaturan benar.", 6000L)
                    } else {
                        notice.hide()
                        movies = list
                        adapter.notifyDataSetChanged()
                        grid.setSelection(0)
                        if (list.isEmpty()) notice.show(null, "Koleksi masih kosong.", 3000L)
                    }
                }
            }
        }.start()
    }

    private fun openJellyfin(m: Movie) {
        if (m.kind == "jf_series") {
            notice.show(null, "Memuat episode...")
            Thread {
                val eps = Jellyfin.episodes(this, m.jfId)
                ui.post {
                    notice.hide()
                    if (eps.isNullOrEmpty()) {
                        notice.show(null, "Episode tidak ditemukan.", 3000L)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(m.title)
                            .setItems(eps.map { e -> e.title + (if (resumeOf(e.jfId) > 0L) "  \u25B6" else "") }.toTypedArray()) { _, w ->
                                playJellyfin(eps[w])
                            }
                            .show()
                    }
                }
            }.start()
        } else {
            playJellyfin(m)
        }
    }

    private fun resumeOf(id: String): Long = prefs.getLong("pos_jf_" + id, 0L)

    private fun playJellyfin(m: Movie) {
        notice.show(null, "Menyiapkan " + m.title + "...")
        Thread {
            val pl = Jellyfin.playable(this, m.jfId)
            ui.post {
                notice.hide()
                if (pl == null) {
                    notice.show("Tidak bisa diputar", "Server Jellyfin tidak menjawab.", 4000L)
                    return@post
                }
                val i = Intent(this, VodPlayerActivity::class.java)
                i.putExtra("title", m.title)
                i.putExtra("url", pl.url)
                i.putExtra("fallback", pl.fallback)
                i.putExtra("resume_key", "jf_" + m.jfId)
                i.putExtra("sub_urls", pl.subs.map { it.url }.toTypedArray())
                i.putExtra("sub_langs", pl.subs.map { it.lang }.toTypedArray())
                i.putExtra("sub_labels", pl.subs.map { it.label }.toTypedArray())
                startActivity(i)
            }
        }.start()
    }

    // Cari judul dari katalog TMDB di Koleksi Saya
    private fun playFromCollection(m: Movie) {
        if (!Jellyfin.configured(this)) {
            notice.show("Koleksi Saya belum diatur", "Isi server Jellyfin di Pengaturan lewat HP.", 5000L)
            return
        }
        notice.show(null, "Mencari di Koleksi Saya...")
        Thread {
            val found = Jellyfin.search(this, m.title)
            ui.post {
                notice.hide()
                if (found.isNullOrEmpty()) {
                    notice.show(null, "\"" + m.title + "\" belum ada di Koleksi Saya.", 3500L)
                } else if (found.size == 1) {
                    openJellyfin(found[0])
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Pilih dari Koleksi Saya")
                        .setItems(found.map { it.title + (if (it.year.isNotEmpty()) " (" + it.year + ")" else "") }.toTypedArray()) { _, w ->
                            openJellyfin(found[w])
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun askSearch() {
        val et = EditText(this)
        et.isSingleLine = true
        et.hint = "Judul film"
        AlertDialog.Builder(this)
            .setTitle("Cari film")
            .setView(et)
            .setPositiveButton("Cari") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) {
                    catIdx = searchIdx
                    for (j in catViews.indices) catViews[j].setTextColor(if (j == searchIdx) brassLight else mute)
                    heading.text = "Hasil: " + q
                    fetchList("/search/multi", "language=id-ID&include_adult=false&query=" + URLEncoder.encode(q, "UTF-8"), "movie")
                    grid.requestFocus()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- Daftar saya (disimpan di TV) ----
    private fun loadWatch(): List<Movie> {
        val out = ArrayList<Movie>()
        try {
            val a = JSONArray(prefs.getString("watch", "[]"))
            for (i in 0 until a.length()) {
                val m = Tmdb.toMovie(a.getJSONObject(i))
                if (m != null) out.add(m)
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun isWatched(id: Int): Boolean = loadWatch().any { it.id == id }

    private fun watchKey(m: Movie): String = m.kind + ":" + m.id

    private fun toggleWatch(m: Movie): Boolean {
        val list = ArrayList(loadWatch())
        val had = list.removeAll { watchKey(it) == watchKey(m) }
        if (!had) list.add(0, m)
        val a = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("title", x.title)
            o.put("release_date", x.year)
            o.put("poster_path", x.poster ?: "")
            o.put("vote_average", x.rating)
            o.put("overview", x.overview)
            o.put("kind", x.kind)
            a.put(o)
        }
        prefs.edit().putString("watch", a.toString()).apply()
        if (catIdx == 5) {
            movies = list
            adapter.notifyDataSetChanged()
        }
        return !had
    }

    // ---- Detail film ----
    private fun names(a: JSONArray?): String {
        if (a == null) return ""
        val l = ArrayList<String>()
        for (i in 0 until a.length()) l.add(a.getJSONObject(i).optString("provider_name"))
        return l.joinToString(", ")
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun findInstalled(providers: List<String>): Pair<String, ComponentName>? {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = pm.queryIntentActivities(q, 0)
        for (p in providers) {
            val np = norm(p)
            if (np.length < 4) continue
            for (a in apps) {
                val label = a.loadLabel(pm).toString()
                val nl = norm(label)
                if (nl.length >= 4 && (nl.contains(np) || np.contains(nl))) {
                    return Pair(label, ComponentName(a.activityInfo.packageName, a.activityInfo.name))
                }
            }
        }
        return null
    }

    private fun showDetail(m: Movie) {
        if (m.isJellyfin) {
            openJellyfin(m)
            return
        }
        notice.show(null, "Memuat detail...")
        Thread {
            val json = Tmdb.get(this, "/" + (if (m.kind == "tv") "tv" else "movie") + "/" + m.id, "language=id-ID&append_to_response=videos,watch/providers&include_video_language=id,en,ja,null")
            ui.post {
                notice.hide()
                if (json == null) {
                    notice.show("Tidak bisa memuat detail", "Periksa koneksi internet TV.", 4000L)
                } else {
                    try {
                        renderDetail(m, JSONObject(json))
                    } catch (e: Exception) {
                        notice.show("Detail tidak terbaca", e.javaClass.simpleName, 4000L)
                    }
                }
            }
        }.start()
    }

    private fun renderDetail(m: Movie, o: JSONObject) {
        val sb = StringBuilder()
        val head = ArrayList<String>()
        if (m.year.isNotEmpty()) head.add(m.year)
        if (m.rating > 0) head.add("\u2605 " + String.format(Locale.US, "%.1f", m.rating))
        val rt = o.optInt("runtime", 0)
        if (rt > 0) head.add(rt.toString() + " menit")
        val seasons = o.optInt("number_of_seasons", 0)
        if (seasons > 0) head.add(seasons.toString() + " musim, " + o.optInt("number_of_episodes", 0) + " episode")
        sb.append(head.joinToString("  \u00B7  "))
        val g = o.optJSONArray("genres")
        if (g != null && g.length() > 0) {
            val gl = ArrayList<String>()
            for (i in 0 until g.length()) gl.add(g.getJSONObject(i).optString("name"))
            sb.append("\n").append(gl.joinToString(", "))
        }
        var ov = o.optString("overview", "").trim()
        if (ov.isEmpty()) ov = "Sinopsis bahasa Indonesia belum tersedia."
        if (ov.length > 420) ov = ov.substring(0, 420) + "..."
        sb.append("\n\n").append(ov)

        var trailer: String? = null
        val vs = o.optJSONObject("videos")?.optJSONArray("results")
        if (vs != null) {
            for (i in 0 until vs.length()) {
                val v = vs.getJSONObject(i)
                if (v.optString("site") == "YouTube" && (v.optString("type") == "Trailer" || v.optString("type") == "Teaser")) {
                    trailer = v.optString("key")
                    break
                }
            }
        }

        val wp = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("ID")
        val provNames = ArrayList<String>()
        sb.append("\n\nTonton di Indonesia:")
        if (wp == null) {
            sb.append("\nBelum ada data layanan resmi.")
        } else {
            val fl = names(wp.optJSONArray("flatrate"))
            val fr = names(wp.optJSONArray("free"))
            val ad = names(wp.optJSONArray("ads"))
            val rent = names(wp.optJSONArray("rent"))
            val buy = names(wp.optJSONArray("buy"))
            if (fl.isNotEmpty()) sb.append("\nLangganan: ").append(fl)
            if (fr.isNotEmpty()) sb.append("\nGratis: ").append(fr)
            if (ad.isNotEmpty()) sb.append("\nGratis dengan iklan: ").append(ad)
            if (rent.isNotEmpty()) sb.append("\nSewa: ").append(rent)
            if (buy.isNotEmpty()) sb.append("\nBeli: ").append(buy)
            if (fl.isEmpty() && fr.isEmpty() && ad.isEmpty() && rent.isEmpty() && buy.isEmpty()) sb.append("\nBelum ada data layanan resmi.")
            for (s in listOf(fl, fr, ad, rent, buy)) if (s.isNotEmpty()) provNames.addAll(s.split(", "))
        }
        sb.append("\n\nData layanan: JustWatch (via TMDB).")

        val installed = findInstalled(provNames)
        val tk = trailer
        val inList = loadWatch().any { watchKey(it) == watchKey(m) }
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (Jellyfin.configured(this)) {
            labels.add("Putar dari Koleksi Saya")
            acts.add { playFromCollection(m) }
        }
        if (tk != null && tk.isNotEmpty()) {
            labels.add("Tonton trailer")
            acts.add { openTrailer(tk) }
        }
        if (installed != null) {
            labels.add("Buka " + installed.first)
            acts.add {
                try {
                    val i = Intent(Intent.ACTION_MAIN)
                    i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
                    i.component = installed.second
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    notice.show("Tidak terbuka", "Buka aplikasinya langsung dari layar utama TV.", 4000L)
                }
            }
        }
        labels.add(if (inList) "Hapus dari Daftar saya" else "Tambah ke Daftar saya")
        acts.add {
            val added = toggleWatch(m)
            notice.show(null, if (added) "Ditambahkan ke Daftar saya" else "Dihapus dari Daftar saya", 2000L)
        }
        AlertDialog.Builder(this)
            .setTitle(m.title)
            .setMessage(sb.toString())
            .setPositiveButton("Pilihan") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(m.title)
                    .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun openTrailer(key: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + key))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e2: Exception) {
                notice.show("Trailer tidak terbuka", "Pasang aplikasi YouTube di TV.", 4000L)
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
