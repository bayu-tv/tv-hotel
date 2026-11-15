package com.bayutv.hotel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Pemuat gambar kecil dengan cache terbatas (hemat RAM untuk TV 2 GB).
object Images {
    private val cache = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    /** Poster: jalur TMDB ("/abc.jpg") atau alamat lengkap (Jellyfin). */
    fun load(path: String?, v: ImageView) {
        if (path == null) {
            v.tag = null
            v.setImageDrawable(null)
            return
        }
        val url = if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w185" + path
        loadUrl(url, v, 360, false)
    }

    /** Muat gambar dari alamat lengkap, diperkecil sampai sisi terpanjang +- maxPx. */
    fun loadUrl(url: String?, v: ImageView, maxPx: Int, alpha: Boolean) {
        v.tag = url
        v.setImageDrawable(null)
        if (url.isNullOrEmpty()) return
        val hit = cache.get(url)
        if (hit != null) {
            v.setImageBitmap(hit)
            return
        }
        synchronized(failed) { if (failed.contains(url)) return }
        pool.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "BayuTV")
                val bytes = c.inputStream.use { it.readBytes() }
                val b = BitmapFactory.Options()
                b.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                var sample = 1
                while (b.outWidth / (sample * 2) >= maxPx || b.outHeight / (sample * 2) >= maxPx) sample *= 2
                val o = BitmapFactory.Options()
                o.inSampleSize = sample
                o.inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (bmp != null) {
                    cache.put(url, bmp)
                    ui.post { if (v.tag == url) v.setImageBitmap(bmp) }
                } else {
                    synchronized(failed) { failed.add(url) }
                }
            } catch (e: Exception) {
                synchronized(failed) { failed.add(url) }
            }
        }
    }
}
