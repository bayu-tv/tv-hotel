package com.bayutv.hotel

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.random.Random

class RadioMusicActivity : AppCompatActivity() {

    data class AudioItem(
        val title: String,
        val subtitle: String,
        val uri: String,
        val isRadio: Boolean = false
    )

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val streamUrl: String = "",
        val thumbUrl: String? = null,
        val isLive: Boolean = false,
        val isYtMusic: Boolean = false
    )

    data class AlbumItem(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val thumbUrl: String?
    )

    data class UserPlaylist(
        val name: String,
        val tracks: MutableList<Track>,
        val isGoogleSynced: Boolean = false,
        val browseId: String = ""
    )

    data class CuratedMix(
        val tag: String,
        val title: String,
        val subtitle: String,
        val query: String,
        val colorStart: Int,
        val colorEnd: Int,
        val isAlbumBrowse: Boolean = false
    )

    data class LyricLine(val timeMs: Long, val text: String)

    data class ResolvedStream(
        val url: String,
        val userAgent: String,
        val sourceLabel: String,
        val cookie: String = ""
    )

    data class RadioScanResult(
        val state: Int,
        val label: String,
        val detail: String
    )

    enum class PlayState {
        IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ERROR
    }

    // =========================================================================
    // IKON VEKTOR RINGAN (0 KB ASSET, TANPA EMOJI, TAJAM DI TV & HP)
    // =========================================================================
    enum class Glyph {
        PLAY, PAUSE, STOP, NEXT, PREV, SHUFFLE, REPEAT, REPEAT_ONE,
        MIC, LYRICS, TIMER, MOON, SEARCH, PLUS, CLOSE, CHEVRON_DOWN, BACK, DISC, RADIO
    }

    class GlyphDrawable(
        private val glyph: Glyph,
        private val color: Int,
        private val sizePx: Int
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@GlyphDrawable.color
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@GlyphDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = (sizePx * 0.105f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())

            when (glyph) {
                Glyph.PLAY -> {
                    val p = Path().apply {
                        moveTo(w * 0.28f, h * 0.18f)
                        lineTo(w * 0.82f, h * 0.50f)
                        lineTo(w * 0.28f, h * 0.82f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                }
                Glyph.PAUSE -> {
                    val r1 = RectF(w * 0.24f, h * 0.20f, w * 0.42f, h * 0.80f)
                    val r2 = RectF(w * 0.58f, h * 0.20f, w * 0.76f, h * 0.80f)
                    canvas.drawRoundRect(r1, w * 0.06f, w * 0.06f, fillPaint)
                    canvas.drawRoundRect(r2, w * 0.06f, w * 0.06f, fillPaint)
                }
                Glyph.STOP -> {
                    val r = RectF(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f)
                    canvas.drawRoundRect(r, w * 0.10f, w * 0.10f, fillPaint)
                }
                Glyph.NEXT -> {
                    val p = Path().apply {
                        moveTo(w * 0.20f, h * 0.22f)
                        lineTo(w * 0.64f, h * 0.50f)
                        lineTo(w * 0.20f, h * 0.78f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                    val bar = RectF(w * 0.68f, h * 0.22f, w * 0.80f, h * 0.78f)
                    canvas.drawRoundRect(bar, w * 0.04f, w * 0.04f, fillPaint)
                }
                Glyph.PREV -> {
                    val bar = RectF(w * 0.20f, h * 0.22f, w * 0.32f, h * 0.78f)
                    canvas.drawRoundRect(bar, w * 0.04f, w * 0.04f, fillPaint)
                    val p = Path().apply {
                        moveTo(w * 0.80f, h * 0.22f)
                        lineTo(w * 0.36f, h * 0.50f)
                        lineTo(w * 0.80f, h * 0.78f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                }
                Glyph.SHUFFLE -> {
                    canvas.drawLine(w * 0.18f, h * 0.30f, w * 0.42f, h * 0.30f, strokePaint)
                    canvas.drawLine(w * 0.42f, h * 0.30f, w * 0.62f, h * 0.70f, strokePaint)
                    canvas.drawLine(w * 0.62f, h * 0.70f, w * 0.82f, h * 0.70f, strokePaint)

                    canvas.drawLine(w * 0.18f, h * 0.70f, w * 0.42f, h * 0.70f, strokePaint)
                    canvas.drawLine(w * 0.42f, h * 0.70f, w * 0.62f, h * 0.30f, strokePaint)
                    canvas.drawLine(w * 0.62f, h * 0.30f, w * 0.82f, h * 0.30f, strokePaint)

                    canvas.drawLine(w * 0.73f, h * 0.21f, w * 0.83f, h * 0.30f, strokePaint)
                    canvas.drawLine(w * 0.73f, h * 0.39f, w * 0.83f, h * 0.30f, strokePaint)
                    canvas.drawLine(w * 0.73f, h * 0.61f, w * 0.83f, h * 0.70f, strokePaint)
                    canvas.drawLine(w * 0.73f, h * 0.79f, w * 0.83f, h * 0.70f, strokePaint)
                }
                Glyph.REPEAT, Glyph.REPEAT_ONE -> {
                    val r = RectF(w * 0.20f, h * 0.26f, w * 0.80f, h * 0.74f)
                    canvas.drawRoundRect(r, w * 0.16f, h * 0.16f, strokePaint)
                    canvas.drawLine(w * 0.66f, h * 0.17f, w * 0.76f, h * 0.26f, strokePaint)
                    canvas.drawLine(w * 0.66f, h * 0.35f, w * 0.76f, h * 0.26f, strokePaint)
                    if (glyph == Glyph.REPEAT_ONE) {
                        canvas.drawLine(w * 0.50f, h * 0.39f, w * 0.50f, h * 0.61f, strokePaint)
                    }
                }
                Glyph.MIC -> {
                    val cap = RectF(w * 0.37f, h * 0.16f, w * 0.63f, h * 0.55f)
                    canvas.drawRoundRect(cap, w * 0.13f, w * 0.13f, strokePaint)
                    val arc = RectF(w * 0.26f, h * 0.30f, w * 0.74f, h * 0.68f)
                    canvas.drawArc(arc, 0f, 180f, false, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.68f, w * 0.50f, h * 0.84f, strokePaint)
                    canvas.drawLine(w * 0.34f, h * 0.84f, w * 0.66f, h * 0.84f, strokePaint)
                }
                Glyph.LYRICS -> {
                    canvas.drawLine(w * 0.20f, h * 0.28f, w * 0.80f, h * 0.28f, strokePaint)
                    canvas.drawLine(w * 0.20f, h * 0.50f, w * 0.80f, h * 0.50f, strokePaint)
                    canvas.drawLine(w * 0.20f, h * 0.72f, w * 0.58f, h * 0.72f, strokePaint)
                }
                Glyph.TIMER -> {
                    canvas.drawCircle(w * 0.50f, h * 0.52f, w * 0.33f, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.52f, w * 0.50f, h * 0.33f, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.52f, w * 0.65f, h * 0.58f, strokePaint)
                }
                Glyph.MOON -> {
                    val p = Path().apply {
                        val outer = RectF(w * 0.22f, h * 0.18f, w * 0.82f, h * 0.82f)
                        arcTo(outer, 45f, 240f, true)
                        quadTo(w * 0.44f, h * 0.52f, w * 0.73f, h * 0.71f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                }
                Glyph.SEARCH -> {
                    canvas.drawCircle(w * 0.44f, h * 0.44f, w * 0.25f, strokePaint)
                    canvas.drawLine(w * 0.63f, h * 0.63f, w * 0.82f, h * 0.82f, strokePaint)
                }
                Glyph.PLUS -> {
                    canvas.drawLine(w * 0.50f, h * 0.22f, w * 0.50f, h * 0.78f, strokePaint)
                    canvas.drawLine(w * 0.22f, h * 0.50f, w * 0.78f, h * 0.50f, strokePaint)
                }
                Glyph.CLOSE -> {
                    canvas.drawLine(w * 0.28f, h * 0.28f, w * 0.72f, h * 0.72f, strokePaint)
                    canvas.drawLine(w * 0.72f, h * 0.28f, w * 0.28f, h * 0.72f, strokePaint)
                }
                Glyph.CHEVRON_DOWN -> {
                    canvas.drawLine(w * 0.22f, h * 0.36f, w * 0.50f, h * 0.66f, strokePaint)
                    canvas.drawLine(w * 0.50f, h * 0.66f, w * 0.78f, h * 0.36f, strokePaint)
                }
                Glyph.BACK -> {
                    canvas.drawLine(w * 0.76f, h * 0.50f, w * 0.24f, h * 0.50f, strokePaint)
                    canvas.drawLine(w * 0.46f, h * 0.28f, w * 0.24f, h * 0.50f, strokePaint)
                    canvas.drawLine(w * 0.46f, h * 0.72f, w * 0.24f, h * 0.50f, strokePaint)
                }
                Glyph.DISC -> {
                    canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.35f, strokePaint)
                    canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.10f, fillPaint)
                }
                Glyph.RADIO -> {
                    canvas.drawCircle(w * 0.50f, h * 0.56f, w * 0.10f, fillPaint)
                    val r1 = RectF(w * 0.30f, h * 0.36f, w * 0.70f, h * 0.76f)
                    val r2 = RectF(w * 0.16f, h * 0.22f, w * 0.84f, h * 0.90f)
                    canvas.drawArc(r1, 210f, 120f, false, strokePaint)
                    canvas.drawArc(r2, 210f, 120f, false, strokePaint)
                }
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // =========================================================================
    // ORNAMEN DEKORATIF KARTU SPOTIFY (0 KB RAM, LANGSUNG TAMPIL INSTAN)
    // =========================================================================
    class SpotifyCardDecorDrawable(private val showCenterVinyl: Boolean) : Drawable() {
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x16FFFFFF
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x24FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())

            canvas.drawCircle(w * 0.14f, h * 0.16f, w * 0.42f, circlePaint)

            if (showCenterVinyl) {
                val cx = w * 0.5f
                val cy = h * 0.45f
                val r = minOf(w, h) * 0.28f
                canvas.drawCircle(cx, cy, r, ringPaint)
                canvas.drawCircle(cx, cy, r * 0.44f, ringPaint)
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            circlePaint.alpha = alpha
            ringPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            circlePaint.colorFilter = colorFilter
            ringPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // =========================================================================
    // DSP VOCAL REDUCER 2 TINGKAT (0 = OFF, 1 = NORMAL, 2 = ULTRA)
    // =========================================================================
    @OptIn(UnstableApi::class)
    class VocalReducerAudioProcessor : BaseAudioProcessor() {
        @Volatile
        var mode: Int = 0

        private var lowPassL = 0f
        private var lowPassR = 0f

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
                return AudioProcessor.AudioFormat.NOT_SET
            }
            lowPassL = 0f
            lowPassR = 0f
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val pos = inputBuffer.position()
            val limit = inputBuffer.limit()
            val size = limit - pos
            if (size <= 0) return

            val out = replaceOutputBuffer(size)
            val currentMode = mode
            if (currentMode == 0) {
                out.put(inputBuffer)
                out.flip()
                return
            }

            val lpAlpha = if (currentMode == 2) 0.012f else 0.018f
            val sideBoost = if (currentMode == 2) 1.15f else 0.95f
            val bassKeep = if (currentMode == 2) 0.42f else 0.50f

            var i = pos
            while (i + 3 < limit) {
                val l = ((inputBuffer.get(i + 1).toInt() shl 8) or (inputBuffer.get(i).toInt() and 0xFF)).toShort().toInt()
                val r = ((inputBuffer.get(i + 3).toInt() shl 8) or (inputBuffer.get(i + 2).toInt() and 0xFF)).toShort().toInt()

                lowPassL += lpAlpha * (l - lowPassL)
                lowPassR += lpAlpha * (r - lowPassR)
                val bassMono = ((lowPassL + lowPassR) * bassKeep).toInt()

                val side = ((l - r) * sideBoost).toInt()
                val outL = (bassMono + side).coerceIn(-32768, 32767)
                val outR = (bassMono - side).coerceIn(-32768, 32767)

                out.put((outL and 0xFF).toByte())
                out.put(((outL shr 8) and 0xFF).toByte())
                out.put((outR and 0xFF).toByte())
                out.put(((outR shr 8) and 0xFF).toByte())
                i += 4
            }
            inputBuffer.position(limit)
            out.flip()
        }

        override fun onFlush() {
            lowPassL = 0f
            lowPassR = 0f
        }

        override fun onReset() {
            lowPassL = 0f
            lowPassR = 0f
        }
    }

    // =========================================================================
    // PEMUAT GAMBAR HEMAT RAM + AUTO-CROP 1:1 (HILANGKAN BAR HITAM YOUTUBE)
    // =========================================================================
    object SafeArtLoader {
        private const val MAX_CACHE_BYTES = 3 * 1024 * 1024
        private val mainHandler = Handler(Looper.getMainLooper())
        private val pool = Executors.newFixedThreadPool(2)
        private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        fun load(url: String?, target: ImageView) {
            target.tag = url
            if (url.isNullOrBlank()) {
                target.setImageDrawable(null)
                return
            }
            cache.get(url)?.let { cached ->
                if (!cached.isRecycled) {
                    target.setImageBitmap(cached)
                    return
                }
            }
            target.setImageDrawable(null)

            pool.execute {
                val bmp = downloadAndDownsample(url, 240, 240)
                if (bmp != null) {
                    cache.put(url, bmp)
                    mainHandler.post {
                        if (target.tag == url && !bmp.isRecycled) {
                            target.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        fun clearCache() {
            try {
                cache.evictAll()
            } catch (_: Exception) {
            }
        }

        private fun downloadAndDownsample(rawUrl: String, reqW: Int, reqH: Int): Bitmap? {
            return try {
                val conn = URL(rawUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                var inSample = 1
                while ((bounds.outHeight / inSample) > reqH * 2 || (bounds.outWidth / inSample) > reqW * 2) {
                    inSample *= 2
                }

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = inSample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

                if (rawUrl.contains("ytimg.com", ignoreCase = true) && decoded.width > decoded.height) {
                    val side = decoded.height
                    val xOffset = (decoded.width - side) / 2
                    val square = Bitmap.createBitmap(decoded, xOffset, 0, side, side)
                    if (square != decoded) decoded.recycle()
                    return square
                }
                decoded
            } catch (_: Exception) {
                null
            }
        }
    }

    // =========================================================================
    // PENYIMPANAN RIWAYAT & RADIO MANUAL
    // =========================================================================
    object RecentHistoryStore {
        private const val PREF_NAME = "bbox_recent"
        private const val KEY_LIST = "recent_tracks"
        private const val MAX_ITEMS = 15

        fun recordPlay(ctx: Context, track: Track) {
            val list = getRecent(ctx)
            list.removeAll { it.id == track.id && it.title == track.title }
            list.add(0, track)
            if (list.size > MAX_ITEMS) {
                list.subList(MAX_ITEMS, list.size).clear()
            }
            save(ctx, list)
        }

        fun getRecent(ctx: Context): MutableList<Track> {
            val raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            val out = ArrayList<Track>()
            if (raw.isNullOrBlank()) return out
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Track(
                            id = o.optString("id", ""),
                            title = o.optString("title", ""),
                            artist = o.optString("artist", ""),
                            streamUrl = o.optString("streamUrl", ""),
                            thumbUrl = o.optString("thumbUrl", "").ifBlank { null },
                            isLive = o.optBoolean("isLive", false),
                            isYtMusic = o.optBoolean("isYtMusic", false)
                        )
                    )
                }
            } catch (_: Exception) {
            }
            return out
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_LIST).apply()
        }

        private fun save(ctx: Context, list: List<Track>) {
            val arr = JSONArray()
            for (t in list) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("title", t.title)
                o.put("artist", t.artist)
                o.put("streamUrl", t.streamUrl)
                o.put("thumbUrl", t.thumbUrl ?: "")
                o.put("isLive", t.isLive)
                o.put("isYtMusic", t.isYtMusic)
                arr.put(o)
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
        }
    }

    object CustomRadioStore {
        private const val PREF_NAME = "bbox_custom_radios"
        private const val KEY_LIST = "radios_json"

        fun getRadios(ctx: Context): MutableList<Track> {
            val raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            val out = ArrayList<Track>()
            if (raw.isNullOrBlank()) return out
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Track(
                            id = o.optString("id", "custom_$i"),
                            title = o.optString("title", "Radio Lokal"),
                            artist = o.optString("artist", "Radio Manual"),
                            streamUrl = o.optString("streamUrl", ""),
                            isLive = true
                        )
                    )
                }
            } catch (_: Exception) {
            }
            return out
        }

        fun addRadio(ctx: Context, name: String, city: String, url: String) {
            val list = getRadios(ctx)
            list.add(
                0,
                Track(
                    id = "custom_${System.currentTimeMillis()}",
                    title = name.trim(),
                    artist = city.trim().ifBlank { "Radio Manual" },
                    streamUrl = url.trim(),
                    isLive = true
                )
            )
            save(ctx, list)
        }

        fun deleteRadio(ctx: Context, id: String) {
            val list = getRadios(ctx)
            if (list.removeAll { it.id == id }) {
                save(ctx, list)
            }
        }

        private fun save(ctx: Context, list: List<Track>) {
            val arr = JSONArray()
            for (t in list) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("title", t.title)
                o.put("artist", t.artist)
                o.put("streamUrl", t.streamUrl)
                arr.put(o)
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
        }
    }

    // =========================================================================
    // AUTENTIKASI AKUN GOOGLE OPSIONAL (SAPISIDHASH)
    // =========================================================================
    object GoogleMusicAuth {
        private const val YTM_ORIGIN = "https://music.youtube.com"
        const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun getCookie(): String {
            return try {
                CookieManager.getInstance().getCookie(YTM_ORIGIN) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        fun isLoggedIn(): Boolean {
            val c = getCookie()
            return c.contains("SAPISID=") || c.contains("__Secure-3PAPISID=")
        }

        fun buildSapisidHashAuth(): String? {
            val cookie = getCookie()
            if (cookie.isBlank()) return null
            val parts = cookie.split(";")
            var sapisid = ""
            for (p in parts) {
                val kv = p.trim()
                if (kv.startsWith("SAPISID=")) {
                    sapisid = kv.substringAfter("=")
                    break
                }
                if (kv.startsWith("__Secure-3PAPISID=") && sapisid.isEmpty()) {
                    sapisid = kv.substringAfter("=")
                }
            }
            if (sapisid.isBlank()) return null
            return try {
                val ts = System.currentTimeMillis() / 1000L
                val raw = "$ts $sapisid $YTM_ORIGIN"
                val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                "SAPISIDHASH ${ts}_$hex"
            } catch (_: Exception) {
                null
            }
        }

        fun logout() {
            try {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            } catch (_: Exception) {
            }
        }
    }

    class NativeHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val targetUrl = URL(request.url())
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
            }

            for ((key, values) in request.headers()) {
                for (value in values) {
                    conn.addRequestProperty(key, value)
                }
            }

            val cookie = GoogleMusicAuth.getCookie()
            if (cookie.isNotBlank() && request.url().contains("youtube.com", ignoreCase = true)) {
                conn.setRequestProperty("Cookie", cookie)
            }

            val payload = request.dataToSend()
            if (payload != null && payload.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(payload) }
            }

            val code = conn.responseCode
            val message = conn.responseMessage ?: ""
            val inStream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = inStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            val respHeaders = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null) {
                    respHeaders[k] = v
                }
            }

            val latestUrl = conn.url?.toString() ?: request.url()
            conn.disconnect()
            return Response(code, message, respHeaders, body, latestUrl)
        }
    }

    object PlaylistStore {
        private const val PREF_NAME = "bbox_playlists"
        private const val KEY_DATA = "playlists_json"

        fun getLocalPlaylists(ctx: Context): MutableList<UserPlaylist> {
            val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DATA, null)
            val result = ArrayList<UserPlaylist>()
            if (!raw.isNullOrBlank()) {
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name", "Playlist ${i + 1}")
                        val tArr = obj.optJSONArray("tracks") ?: JSONArray()
                        val tracks = ArrayList<Track>()
                        for (j in 0 until tArr.length()) {
                            val to = tArr.getJSONObject(j)
                            tracks.add(
                                Track(
                                    id = to.optString("id", ""),
                                    title = to.optString("title", "Tanpa Judul"),
                                    artist = to.optString("artist", ""),
                                    streamUrl = to.optString("streamUrl", ""),
                                    thumbUrl = to.optString("thumbUrl", "").ifBlank { null },
                                    isLive = to.optBoolean("isLive", false),
                                    isYtMusic = to.optBoolean("isYtMusic", false)
                                )
                            )
                        }
                        result.add(UserPlaylist(name, tracks))
                    }
                } catch (_: Exception) {
                }
            }
            if (result.isEmpty()) {
                result.add(UserPlaylist("Favorit Saya", ArrayList()))
                saveLocalPlaylists(ctx, result)
            }
            return result
        }

        fun saveLocalPlaylists(ctx: Context, list: List<UserPlaylist>) {
            val arr = JSONArray()
            for (pl in list) {
                if (pl.isGoogleSynced) continue
                val obj = JSONObject()
                obj.put("name", pl.name)
                val tArr = JSONArray()
                for (t in pl.tracks) {
                    val to = JSONObject()
                    to.put("id", t.id)
                    to.put("title", t.title)
                    to.put("artist", t.artist)
                    to.put("streamUrl", t.streamUrl)
                    to.put("thumbUrl", t.thumbUrl ?: "")
                    to.put("isLive", t.isLive)
                    to.put("isYtMusic", t.isYtMusic)
                    tArr.put(to)
                }
                obj.put("tracks", tArr)
                arr.put(obj)
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        }

        fun createPlaylist(ctx: Context, name: String): Int {
            val all = getLocalPlaylists(ctx)
            all.add(UserPlaylist(name.trim(), ArrayList()))
            saveLocalPlaylists(ctx, all)
            return all.lastIndex
        }

        fun deletePlaylist(ctx: Context, index: Int) {
            val all = getLocalPlaylists(ctx)
            if (index in all.indices) {
                all.removeAt(index)
                saveLocalPlaylists(ctx, all)
            }
        }

        fun addTrackToPlaylist(ctx: Context, playlistIndex: Int, track: Track): Boolean {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex !in all.indices) return false
            val target = all[playlistIndex]
            if (target.tracks.any { it.id == track.id && it.title == track.title }) {
                return false
            }
            target.tracks.add(track)
            saveLocalPlaylists(ctx, all)
            return true
        }

        fun addManyTracksToPlaylist(ctx: Context, playlistIndex: Int, newTracks: List<Track>): Int {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex !in all.indices) return 0
            val target = all[playlistIndex]
            var addedCount = 0
            for (t in newTracks) {
                if (target.tracks.none { it.id == t.id && it.title == t.title }) {
                    target.tracks.add(t)
                    addedCount++
                }
            }
            if (addedCount > 0) saveLocalPlaylists(ctx, all)
            return addedCount
        }

        fun removeTrackFromPlaylist(ctx: Context, playlistIndex: Int, trackIndex: Int) {
            val all = getLocalPlaylists(ctx)
            if (playlistIndex in all.indices && trackIndex in all[playlistIndex].tracks.indices) {
                all[playlistIndex].tracks.removeAt(trackIndex)
                saveLocalPlaylists(ctx, all)
            }
        }

        fun fetchGoogleAccountPlaylists(): List<UserPlaylist> {
            val auth = GoogleMusicAuth.buildSapisidHashAuth() ?: return emptyList()
            val cookie = GoogleMusicAuth.getCookie()
            val found = ArrayList<UserPlaylist>()
            found.add(UserPlaylist("Lagu Disukai (Google)", ArrayList(), isGoogleSynced = true, browseId = "VLLM"))

            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", auth)
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("X-Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)

                val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"browseId":"FEmusic_liked_playlists"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractGooglePlaylists(json, found)
                }
            } catch (_: Exception) {
            }
            return found
        }

        private fun extractGooglePlaylists(node: Any?, out: MutableList<UserPlaylist>) {
            if (node == null) return
            when (node) {
                is JSONObject -> {
                    val twoRow = node.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val browseId = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId", "") ?: ""
                        val runs = twoRow.optJSONObject("title")?.optJSONArray("runs")
                        val title = if (runs != null && runs.length() > 0) runs.getJSONObject(0).optString("text", "") else ""
                        if (browseId.startsWith("VL") && browseId != "VLLM" && title.isNotBlank() && out.none { it.browseId == browseId }) {
                            out.add(UserPlaylist("$title (Google)", ArrayList(), isGoogleSynced = true, browseId = browseId))
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) extractGooglePlaylists(node.opt(keys.next()), out)
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) extractGooglePlaylists(node.opt(i), out)
                }
            }
        }

        fun fetchBrowseTracks(
            browseId: String,
            fallbackArtist: String = "",
            fallbackThumb: String? = null
        ): List<Track> {
            val auth = GoogleMusicAuth.buildSapisidHashAuth()
            val cookie = GoogleMusicAuth.getCookie()
            val tracks = ArrayList<Track>()
            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("X-Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
                if (auth != null) {
                    conn.setRequestProperty("Authorization", auth)
                    conn.setRequestProperty("Cookie", cookie)
                }

                val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"browseId":"$browseId"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractTracksFromBrowse(json, tracks, fallbackArtist, fallbackThumb)
                }
            } catch (_: Exception) {
            }
            return tracks
        }

        private fun extractTracksFromBrowse(
            node: Any?,
            out: MutableList<Track>,
            fallbackArtist: String,
            fallbackThumb: String?
        ) {
            if (out.size >= 80 || node == null) return
            when (node) {
                is JSONObject -> {
                    val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                    if (renderer != null) {
                        val vidFromData = renderer.optJSONObject("playlistItemData")?.optString("videoId", "")
                        val vid = if (!vidFromData.isNullOrEmpty()) {
                            vidFromData
                        } else {
                            renderer.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId", "") ?: ""
                        }

                        val cols = renderer.optJSONArray("flexColumns")
                        var title = ""
                        var artist = ""
                        if (cols != null && cols.length() > 0) {
                            title = readRuns(cols.getJSONObject(0))
                            if (cols.length() > 1) artist = readRuns(cols.getJSONObject(1))
                        }
                        if (vid.isNotEmpty() && title.isNotEmpty() && out.none { it.id == vid }) {
                            val resolvedArtist = if (artist.isNotBlank() && !artist.matches(Regex("""\d+:\d+"""))) {
                                artist
                            } else {
                                fallbackArtist.ifBlank { "YouTube Music" }
                            }
                            out.add(
                                Track(
                                    id = vid,
                                    title = title,
                                    artist = resolvedArtist,
                                    thumbUrl = fallbackThumb ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                    isLive = false,
                                    isYtMusic = true
                                )
                            )
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        extractTracksFromBrowse(node.opt(keys.next()), out, fallbackArtist, fallbackThumb)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        extractTracksFromBrowse(node.opt(i), out, fallbackArtist, fallbackThumb)
                    }
                }
            }
        }

        private fun readRuns(obj: JSONObject?): String {
            val runs = obj?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.getJSONObject(i).optString("text", ""))
            return sb.toString().trim()
        }
    }

    // =========================================================================
    // MESIN PEMUTAR GLOBAL + KARAOKE DSP AMAN + SINKRON NOTIFIKASI LAYAR KUNCI
    // =========================================================================
    object MusicEngine {
        private const val WEB_UA = GoogleMusicAuth.CHROME_UA
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
        private val mainHandler = Handler(Looper.getMainLooper())
        private var appContext: Context? = null

        val vocalReducer = VocalReducerAudioProcessor()

        var player: ExoPlayer? = null
        var currentTrack: Track? = null
        var queue: List<Track> = emptyList()
        var queueIndex: Int = -1
        var state: PlayState = PlayState.IDLE
        var statusDetail: String = "Pilih lagu, album, atau radio untuk mulai"
        var lyrics: List<LyricLine> = emptyList()
        var lyricStatus: String = "Pilih lagu untuk melihat lirik karaoke"
        var lyricsEnabled: Boolean = false
        var shuffleEnabled: Boolean = false
        var repeatMode: Int = 0
        var onStateChanged: (() -> Unit)? = null

        var vocalReduceLevel: Int = 0
            set(value) {
                field = value.coerceIn(0, 2)
                vocalReducer.mode = field
            }
        var lyricOffsetMs: Long = 0L
        var sleepEndTimeMs: Long = 0L

        @Volatile
        private var playSeq = 0
        private var newPipeInitialized = false
        private var savedResumePosMs: Long = 0L
        private var pausedByFocusLoss: Boolean = false
        private var audioFocusReq: AudioFocusRequest? = null

        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (state == PlayState.PLAYING || state == PlayState.BUFFERING) {
                        pausedByFocusLoss = true
                        togglePause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    try {
                        player?.volume = 0.2f
                    } catch (_: Exception) {
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    try {
                        player?.volume = 1.0f
                    } catch (_: Exception) {
                    }
                    if (pausedByFocusLoss && state == PlayState.PAUSED) {
                        pausedByFocusLoss = false
                        togglePause()
                    }
                }
            }
        }

        private fun requestFocus(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build()
                    audioFocusReq = req
                    am.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                }
            } catch (_: Exception) {
            }
        }

        private val idleReleaseRunnable = Runnable {
            val exo = player ?: return@Runnable
            if (state == PlayState.PAUSED || state == PlayState.ERROR || state == PlayState.IDLE) {
                try {
                    if (currentTrack?.isLive != true && exo.currentPosition > 0) {
                        savedResumePosMs = exo.currentPosition
                    }
                    exo.release()
                } catch (_: Exception) {
                }
                player = null
                statusDetail = "Dijeda lama · RAM TV dibebaskan"
                notifyUi()
            }
        }

        private val sleepCheckRunnable = object : Runnable {
            override fun run() {
                if (sleepEndTimeMs <= 0L) return
                val rem = sleepEndTimeMs - System.currentTimeMillis()
                if (rem <= 0L) {
                    sleepEndTimeMs = 0L
                    stopAndReleaseNow(clearTrack = false, customStatus = "Sleep Timer selesai · Audio dimatikan")
                    return
                }
                if (rem <= 10_000L) {
                    try {
                        player?.volume = (rem / 10_000f).coerceIn(0.05f, 1.0f)
                    } catch (_: Exception) {
                    }
                }
                notifyUi()
                mainHandler.postDelayed(this, 1000L)
            }
        }

        fun setSleepTimerMinutes(minutes: Int) {
            mainHandler.removeCallbacks(sleepCheckRunnable)
            if (minutes <= 0) {
                sleepEndTimeMs = 0L
                try {
                    player?.volume = 1.0f
                } catch (_: Exception) {
                }
            } else {
                sleepEndTimeMs = System.currentTimeMillis() + minutes * 60_000L
                try {
                    player?.volume = 1.0f
                } catch (_: Exception) {
                }
                mainHandler.post(sleepCheckRunnable)
            }
            notifyUi()
        }

        fun getSleepRemainingText(): String {
            if (sleepEndTimeMs <= 0L) return ""
            val remSec = ((sleepEndTimeMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            return String.format(Locale.US, "%02d:%02d", remSec / 60L, remSec % 60L)
        }

        private fun scheduleIdleRelease() {
            mainHandler.removeCallbacks(idleReleaseRunnable)
            mainHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
        }

        private fun cancelIdleRelease() {
            mainHandler.removeCallbacks(idleReleaseRunnable)
        }

        @Synchronized
        private fun ensureNewPipeInit() {
            if (!newPipeInitialized) {
                NewPipe.init(NativeHttpDownloader())
                newPipeInitialized = true
            }
        }

        private fun notifyUi() {
            mainHandler.post {
                onStateChanged?.invoke()
                appContext?.let { MusicPlaybackService.syncFromEngine(it) }
            }
        }

        fun playTrack(
            ctx: Context,
            track: Track,
            list: List<Track>,
            index: Int,
            resumeFromMs: Long = 0L
        ) {
            val appCtx = ctx.applicationContext
            appContext = appCtx
            cancelIdleRelease()
            pausedByFocusLoss = false
            requestFocus(appCtx)

            val seq = ++playSeq
            currentTrack = track
            queue = list
            queueIndex = index
            savedResumePosMs = resumeFromMs
            lyricOffsetMs = 0L

            if (track.isLive) {
                vocalReduceLevel = 0
            }

            state = PlayState.RESOLVING
            statusDetail = when {
                track.isYtMusic -> "Mendekripsi aliran audio..."
                track.isLive -> "Menghubungkan ke siaran..."
                else -> "Membuka file audio..."
            }

            lyrics = emptyList()
            lyricStatus = when {
                !lyricsEnabled -> "Lirik dinonaktifkan"
                track.isLive -> "Siaran Langsung · Lirik tidak tersedia"
                else -> "Mencari lirik karaoke..."
            }
            notifyUi()

            Thread {
                RecentHistoryStore.recordPlay(appCtx, track)
            }.start()

            mainHandler.post {
                try {
                    player?.stop()
                } catch (_: Exception) {
                }
            }

            Thread {
                try {
                    if (!track.isLive && lyricsEnabled) {
                        val fetchedLyrics = fetchSyncedLyrics(track.title, track.artist)
                        if (seq == playSeq) {
                            lyrics = fetchedLyrics
                            lyricStatus = if (fetchedLyrics.isEmpty()) {
                                "Lirik sinkron tidak tersedia untuk lagu ini"
                            } else {
                                ""
                            }
                            notifyUi()
                        }
                    }

                    val resolved: ResolvedStream? = when {
                        track.isYtMusic && track.streamUrl.isEmpty() -> resolveViaNewPipe(track)
                        track.isLive -> resolveRadioUrl(track.streamUrl)
                        else -> ResolvedStream(track.streamUrl, WEB_UA, "Lokal")
                    }

                    if (seq != playSeq) return@Thread

                    if (resolved == null || resolved.url.isBlank()) {
                        state = PlayState.ERROR
                        if (statusDetail.startsWith("Mendekripsi")) {
                            statusDetail = "Gagal memuat audio lagu ini. Coba lagu lain."
                        }
                        lyricStatus = statusDetail
                        scheduleIdleRelease()
                        notifyUi()
                        return@Thread
                    }

                    mainHandler.post {
                        if (seq != playSeq) return@post
                        startExoPlayer(appCtx, resolved, seq, resumeFromMs)
                    }
                } catch (e: Exception) {
                    if (seq == playSeq) {
                        state = PlayState.ERROR
                        statusDetail = "Gagal memuat audio (${e.javaClass.simpleName})"
                        lyricStatus = statusDetail
                        scheduleIdleRelease()
                        notifyUi()
                    }
                }
            }.start()
        }

        fun cycleKaraokeLevel(onChangedMessage: (String) -> Unit) {
            val track = currentTrack ?: run {
                onChangedMessage("Putar lagu terlebih dahulu untuk mengaktifkan Karaoke.")
                return
            }
            if (track.isLive) {
                onChangedMessage("Mode Karaoke tidak tersedia untuk siaran radio langsung.")
                return
            }
            vocalReduceLevel = (vocalReduceLevel + 1) % 3
            if (vocalReduceLevel > 0 && !lyricsEnabled) {
                lyricsEnabled = true
                refreshLyricsIfNeeded()
            }
            notifyUi()
            val msg = when (vocalReduceLevel) {
                1 -> "Karaoke NORMAL aktif · Lirik tampil & suara vokal dikurangi."
                2 -> "Karaoke ULTRA aktif · Suara vokal ditekan maksimal."
                else -> "Mode Karaoke dimatikan · Suara vokal kembali normal."
            }
            onChangedMessage(msg)
        }

        private fun resolveViaNewPipe(track: Track): ResolvedStream? {
            ensureNewPipeInit()

            extractSingleVideoWithNewPipe(track.id)?.let { return it }

            statusDetail = "Mencari sumber audio alternatif..."
            notifyUi()
            val cleanArtist = track.artist.split("•", "·", ",").firstOrNull()?.trim() ?: ""
            val altId = searchFallbackVideoId("${track.title} $cleanArtist", track.id)
            if (altId != null) {
                extractSingleVideoWithNewPipe(altId)?.let { alt ->
                    return ResolvedStream(alt.url, alt.userAgent, "${alt.sourceLabel} (Alt)", alt.cookie)
                }
            }
            return null
        }

        private fun extractSingleVideoWithNewPipe(videoId: String): ResolvedStream? {
            return try {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()

                val audioStreams = extractor.audioStreams
                val bestM4a = audioStreams
                    .filter { it.format == MediaFormat.M4A && it.content.startsWith("http") }
                    .maxByOrNull { it.averageBitrate }
                val anyAudio = audioStreams
                    .filter { it.content.startsWith("http") }
                    .maxByOrNull { it.averageBitrate }

                val chosenAudio = bestM4a ?: anyAudio
                if (chosenAudio != null) {
                    val kbps = if (chosenAudio.averageBitrate > 0) "${chosenAudio.averageBitrate} kbps" else "HQ"
                    return ResolvedStream(
                        url = chosenAudio.content,
                        userAgent = WEB_UA,
                        sourceLabel = "Audio $kbps",
                        cookie = GoogleMusicAuth.getCookie()
                    )
                }

                val muxed = extractor.videoStreams.firstOrNull { it.content.startsWith("http") }
                if (muxed != null) {
                    return ResolvedStream(
                        url = muxed.content,
                        userAgent = WEB_UA,
                        sourceLabel = "Stream HQ",
                        cookie = GoogleMusicAuth.getCookie()
                    )
                }
                null
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                statusDetail = "Stream: ${msg.take(48)}"
                null
            }
        }

        private fun searchFallbackVideoId(query: String, excludeId: String): String? {
            return try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()
                val items = searchExtractor.initialPage.items
                for (item in items) {
                    val u = item.url ?: continue
                    val vid = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(u)?.groupValues?.getOrNull(1)
                    if (vid != null && vid != excludeId) return vid
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        fun refreshLyricsIfNeeded() {
            val track = currentTrack ?: return
            if (!lyricsEnabled || track.isLive || lyrics.isNotEmpty()) return
            lyricStatus = "Mencari lirik karaoke..."
            notifyUi()
            val seq = playSeq
            Thread {
                val fetched = fetchSyncedLyrics(track.title, track.artist)
                if (seq == playSeq) {
                    lyrics = fetched
                    lyricStatus = if (fetched.isEmpty()) "Lirik sinkron tidak tersedia untuk lagu ini" else ""
                    notifyUi()
                }
            }.start()
        }

        @OptIn(UnstableApi::class)
        private fun startExoPlayer(appCtx: Context, resolved: ResolvedStream, seq: Int, resumeFromMs: Long) {
            try {
                player?.release()
            } catch (_: Exception) {
            }

            state = PlayState.BUFFERING
            statusDetail = "Memuat (${resolved.sourceLabel})..."
            notifyUi()

            val lc = DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 22000, 1500, 2500)
                .build()

            val renderersFactory = object : DefaultRenderersFactory(appCtx) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(vocalReducer))
                        .build()
                }
            }

            val isLocalFile = !resolved.url.startsWith("http", ignoreCase = true)
            val exo = if (isLocalFile) {
                ExoPlayer.Builder(appCtx, renderersFactory)
                    .setLoadControl(lc)
                    .build()
            } else {
                val dsf = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent(resolved.userAgent)
                if (resolved.cookie.isNotBlank()) {
                    dsf.setDefaultRequestProperties(mapOf("Cookie" to resolved.cookie))
                }
                ExoPlayer.Builder(appCtx, renderersFactory)
                    .setLoadControl(lc)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                    .build()
            }

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (seq != playSeq) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            state = PlayState.BUFFERING
                            statusDetail = "Buffering audio..."
                            notifyUi()
                        }
                        Player.STATE_READY -> {
                            state = if (exo.playWhenReady) PlayState.PLAYING else PlayState.PAUSED
                            statusDetail = if (currentTrack?.isLive == true) {
                                "Siaran Aktif · ${resolved.sourceLabel}"
                            } else {
                                resolved.sourceLabel
                            }
                            if (exo.playWhenReady) cancelIdleRelease() else scheduleIdleRelease()
                            notifyUi()
                        }
                        Player.STATE_ENDED -> {
                            if (currentTrack?.isLive == true) {
                                state = PlayState.PAUSED
                                statusDetail = "Siaran radio terhenti"
                                scheduleIdleRelease()
                                notifyUi()
                            } else {
                                if (repeatMode == 2) {
                                    exo.seekTo(0)
                                    exo.playWhenReady = true
                                } else {
                                    playNext(appCtx, autoAdvance = true)
                                }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (seq != playSeq) return
                    if (exo.playbackState == Player.STATE_READY) {
                        state = if (isPlaying) PlayState.PLAYING else PlayState.PAUSED
                        if (isPlaying) cancelIdleRelease() else scheduleIdleRelease()
                        notifyUi()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (seq != playSeq) return
                    state = PlayState.ERROR
                    val cause = error.cause
                    statusDetail = when {
                        cause is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
                            404, 410 -> "Server siaran offline (HTTP ${cause.responseCode})"
                            401, 403 -> "Akses ditolak server (HTTP ${cause.responseCode})"
                            429, 502, 503 -> "Server sedang sibuk (HTTP ${cause.responseCode})"
                            else -> "Gangguan server HTTP ${cause.responseCode}"
                        }
                        currentTrack?.isLive == true -> "Radio tidak bersuara / gagal terhubung"
                        else -> "Audio gagal diputar (${error.errorCodeName})"
                    }
                    if (currentTrack?.isLive == true) lyricStatus = statusDetail
                    scheduleIdleRelease()
                    notifyUi()
                }
            })

            player = exo
            exo.volume = 1.0f
            exo.setMediaItem(MediaItem.fromUri(resolved.url))
            if (resumeFromMs > 0L) {
                exo.seekTo(resumeFromMs)
            }
            exo.prepare()
            exo.playWhenReady = true
        }

        fun togglePause() {
            if (state == PlayState.RESOLVING) {
                ++playSeq
                state = PlayState.PAUSED
                statusDetail = "Koneksi dibatalkan"
                notifyUi()
                return
            }

            val exo = player
            if (exo == null) {
                val track = currentTrack ?: return
                val ctx = appContext ?: return
                playTrack(
                    ctx,
                    track,
                    queue,
                    queueIndex.coerceAtLeast(0),
                    resumeFromMs = savedResumePosMs
                )
                return
            }
            try {
                if (exo.playWhenReady || exo.isPlaying) {
                    exo.playWhenReady = false
                    exo.pause()
                    state = PlayState.PAUSED
                    statusDetail = if (currentTrack?.isLive == true) "Siaran dijeda" else "Dijeda"
                    scheduleIdleRelease()
                } else {
                    appContext?.let { requestFocus(it) }
                    cancelIdleRelease()
                    exo.volume = 1.0f
                    exo.playWhenReady = true
                    exo.play()
                    state = PlayState.PLAYING
                }
                notifyUi()
            } catch (_: Exception) {
            }
        }

        fun stopAndReleaseNow(
            clearTrack: Boolean = true,
            customStatus: String = "Audio dimatikan · RAM dibebaskan"
        ) {
            ++playSeq
            cancelIdleRelease()
            mainHandler.removeCallbacks(sleepCheckRunnable)
            sleepEndTimeMs = 0L

            try {
                if (currentTrack?.isLive != true && (player?.currentPosition ?: 0L) > 0L) {
                    savedResumePosMs = player?.currentPosition ?: 0L
                }
                player?.playWhenReady = false
                player?.stop()
                player?.release()
            } catch (_: Exception) {
            }
            player = null
            vocalReduceLevel = 0

            if (clearTrack) {
                currentTrack = null
                queue = emptyList()
                queueIndex = -1
                lyrics = emptyList()
                savedResumePosMs = 0L
                state = PlayState.IDLE
            } else {
                state = PlayState.PAUSED
            }
            statusDetail = customStatus
            notifyUi()
        }

        fun seekBy(deltaMs: Long) {
            val exo = player ?: return
            if (currentTrack?.isLive == true) return
            val dur = exo.duration
            if (dur > 0) {
                val target = (exo.currentPosition + deltaMs).coerceIn(0L, dur)
                exo.seekTo(target)
                notifyUi()
            }
        }

        fun playNext(ctx: Context, autoAdvance: Boolean = false) {
            if (queue.isEmpty()) return
            if (shuffleEnabled && queue.size > 1) {
                var rnd = Random.nextInt(queue.size)
                if (rnd == queueIndex) rnd = (rnd + 1) % queue.size
                playTrack(ctx, queue[rnd], queue, rnd)
                return
            }
            val next = queueIndex + 1
            if (next >= queue.size) {
                if (autoAdvance && repeatMode == 0) {
                    state = PlayState.PAUSED
                    statusDetail = "Daftar putar selesai"
                    scheduleIdleRelease()
                    notifyUi()
                    return
                }
                playTrack(ctx, queue[0], queue, 0)
            } else {
                playTrack(ctx, queue[next], queue, next)
            }
        }

        fun playPrev(ctx: Context) {
            if (queue.isEmpty()) return
            val prev = if (queueIndex - 1 < 0) queue.size - 1 else queueIndex - 1
            playTrack(ctx, queue[prev], queue, prev)
        }

        private fun resolveRadioUrl(rawUrl: String): ResolvedStream? {
            var cur = rawUrl.trim()
            if (!cur.startsWith("http", ignoreCase = true)) return null

            for (hop in 0..4) {
                try {
                    val conn = URL(cur).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("User-Agent", WEB_UA)
                    conn.setRequestProperty("Icy-MetaData", "1")
                    val code = conn.responseCode
                    if (code in 301..308) {
                        val loc = conn.getHeaderField("Location") ?: break
                        cur = URL(URL(cur), loc).toString()
                        conn.disconnect()
                        continue
                    }
                    if (code !in 200..299) {
                        conn.disconnect()
                        return null
                    }
                    val ctype = (conn.contentType ?: "").lowercase(Locale.US)
                    val isPlaylistFile = cur.lowercase(Locale.US).endsWith(".pls") ||
                        cur.lowercase(Locale.US).endsWith(".m3u") ||
                        ctype.contains("scpls") ||
                        ctype.contains("mpegurl")

                    if (isPlaylistFile && !cur.lowercase(Locale.US).contains(".m3u8")) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val extracted = extractStreamFromPlaylist(text, cur)
                        if (extracted != null && extracted != cur) {
                            cur = extracted
                            continue
                        }
                    } else {
                        conn.disconnect()
                    }
                    val fmt = when {
                        ctype.contains("aac") -> "AAC Live"
                        ctype.contains("mpeg") || ctype.contains("mp3") -> "MP3 Live"
                        ctype.contains("ogg") -> "OGG Live"
                        else -> "Stream Live"
                    }
                    return ResolvedStream(cur, WEB_UA, fmt)
                } catch (_: Exception) {
                    return null
                }
            }
            return ResolvedStream(cur, WEB_UA, "Stream Live")
        }

        private fun extractStreamFromPlaylist(body: String, baseUrl: String): String? {
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val candidate = if (line.startsWith("File", ignoreCase = true) && line.contains("=")) {
                    line.substringAfter("=").trim()
                } else {
                    line
                }
                if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                    return candidate
                }
                if (!candidate.contains("=") && candidate.contains(".")) {
                    return try {
                        URL(URL(baseUrl), candidate).toString()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return null
        }

        private fun fetchSyncedLyrics(title: String, artist: String): List<LyricLine> {
            val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat.", "ft.", "·", "•").firstOrNull()?.trim() ?: ""
            val queries = listOf(
                "https://lrclib.net/api/get?track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}&artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}",
                "https://lrclib.net/api/search?q=${URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")}"
            )
            for (qUrl in queries) {
                try {
                    val conn = URL(qUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 7000
                    conn.setRequestProperty("User-Agent", "BBoxTV/1.0")
                    if (conn.responseCode != 200) continue
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    val obj = if (raw.startsWith("[")) {
                        val arr = JSONArray(raw)
                        if (arr.length() > 0) arr.getJSONObject(0) else null
                    } else {
                        JSONObject(raw)
                    }
                    val lrc = obj?.optString("syncedLyrics", "") ?: ""
                    if (lrc.isNotBlank()) {
                        val parsed = parseLrc(lrc)
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (_: Exception) {
                }
            }
            return emptyList()
        }

        private fun parseLrc(lrc: String): List<LyricLine> {
            val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
            val out = ArrayList<LyricLine>()
            for (line in lrc.lines()) {
                val m = regex.find(line.trim()) ?: continue
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val msStr = m.groupValues[3]
                val ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10L else (msStr.toLongOrNull() ?: 0L)
                val text = m.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    out.add(LyricLine(min * 60_000L + sec * 1_000L + ms, text))
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }

    // =========================================================================
    // PALET WARNA SPOTIFY DARK + AKSEN B-BOX
    // =========================================================================
    private val bgDark = 0xFF121212.toInt()
    private val surfaceCard = 0xFF181818.toInt()
    private val surfaceElevated = 0xFF242424.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val spotifyGreen = 0xFF1DB954.toInt()
    private val ivory = 0xFFFFFFFF.toInt()
    private val mute = 0xFFA7A7A7.toInt()
    private val redDead = 0xFFE06C65.toInt()

    private val prefs by lazy { getSharedPreferences("bbox", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = arrayOf("Jelajah", "Koleksi", "Terakhir", "Radio ID", "Murottal & Lofi", "Musik Lokal")
    private val tabViews = ArrayList<TextView>()
    private var activeTab = 0

    private val quickShortcuts = listOf(
        CuratedMix("TRENDING", "Trending Indonesia", "Viral & Hits Terbaru Indonesia", "Lagu Viral Trending Indonesia Terbaru", 0xFF1E3264.toInt(), 0xFF121C38.toInt()),
        CuratedMix("TERBAIK", "Terbaik Indonesia", "Pop & Balada Pilihan Tanah Air", "Lagu Pop Indonesia Terbaik Terpopuler", 0xFF8C1932.toInt(), 0xFF3E0B16.toInt()),
        CuratedMix("GLOBAL", "Top Global Hits", "Tangga Lagu Mancanegara Populer", "Top Global Hits Trending English Songs", 0xFF148A08.toInt(), 0xFF0A3D04.toInt()),
        CuratedMix("ERA 90-AN", "Nostalgia 90-an", "Lagu Klasik Indo & Barat Era 90s", "Lagu Nostalgia Era 90an Indonesia & Barat Terbaik", 0xFFBA5D07.toInt(), 0xFF4D2603.toInt()),
        CuratedMix("ERA 2000-AN", "Hits 2000-an", "Band & Pop Legendaris Era 2000s", "Lagu Pop Indonesia & Barat Era 2000an Terpopuler", 0xFF477D95.toInt(), 0xFF1D3540.toInt()),
        CuratedMix("KOPLO", "Dangdut & Koplo", "Pop Jawa & Koplo Terpopuler", "Lagu Dangdut Koplo & Pop Jawa Terpopuler", 0xFF8D67AB.toInt(), 0xFF3A2947.toInt()),
        CuratedMix("BALADA", "Galau & Balada", "Lagu Menyentuh & Akustik Indonesia", "Lagu Galau Indonesia Terbaik Paling Menyentuh", 0xFF503750.toInt(), 0xFF221722.toInt()),
        CuratedMix("TIMUR HITS", "Lagu Timur Viral", "Nyong Timur & Musik Timur Pilihan", "Lagu Timur Indonesia Viral Terbaik", 0xFFBC5900.toInt(), 0xFF4E2500.toInt())
    )

    private val editorialMixes = listOf(
        CuratedMix("DAILY MIX 01", "Pop Indonesia Terbaik", "Tulus, Mahalini, Lyodra, Hindia", "Lagu Pop Indonesia Terbaik Terpopuler", 0xFF283EA3.toInt(), 0xFF131D4D.toInt()),
        CuratedMix("VIRAL 50", "Viral TikTok & Reels", "Lagu paling ramai diputar saat ini", "Lagu Viral TikTok Indonesia Terbaru", 0xFFAF2896.toInt(), 0xFF4A103F.toInt()),
        CuratedMix("GLOBAL POP", "Mancanegara Pilihan", "Hits internasional & R&B terbaik", "Best International Pop and R&B Hits", 0xFF0D73EC.toInt(), 0xFF063266.toInt()),
        CuratedMix("ROMANTIS", "Love Songs Barat", "Lagu cinta barat sepanjang masa", "Best English Love Songs Romantic Hits", 0xFF8C1932.toInt(), 0xFF3B0A15.toInt()),
        CuratedMix("90s INDO", "Pop & Rock 90-an Indo", "Nostalgia klasik Indonesia era 90-an", "Lagu Pop & Slow Rock Indonesia Era 90an Terbaik", 0xFF7D4B32.toInt(), 0xFF362015.toInt()),
        CuratedMix("90s BARAT", "90s Western Classics", "Pop & slow rock barat era 90-an", "Best 90s Western Pop & Love Songs Hits", 0xFF2D46B9.toInt(), 0xFF131E52.toInt()),
        CuratedMix("2000s BAND", "Band Indo 2000-an", "Peterpan, Dewa 19, Sheila On 7, Ungu", "Lagu Band Indonesia Terbaik Peterpan Dewa 19 Sheila On 7 Ungu", 0xFF1E824C.toInt(), 0xFF0C3820.toInt()),
        CuratedMix("2000s HITS", "2000s International", "Pop & R&B barat era 2000-an", "Best 2000s English Pop & R&B Hits", 0xFF477D95.toInt(), 0xFF1E3640.toInt()),
        CuratedMix("AKUSTIK", "Santai & Kopi Sore", "Akustik Indonesia & jazz ringan", "Lagu Akustik Indonesia Santai & Cover Terbaik", 0xFF608108.toInt(), 0xFF293803.toInt()),
        CuratedMix("ASIA HITS", "K-Pop & J-Pop Pilihan", "Hits Korea & Jepang terpopuler", "Top K-Pop and Japanese Pop Hits", 0xFFE13300.toInt(), 0xFF5C1500.toInt())
    )

    private val browseGenreCards = listOf(
        CuratedMix("TREND", "Trend Saat Ini", "Tangga lagu & viral terbaru", "Lagu Viral Trending Indonesia & Barat Terbaru", 0xFFE8115B.toInt(), 0xFF630727.toInt()),
        CuratedMix("INDONESIA", "Lagu Terbaik Indo", "Kumpulan hits terbaik tanah air", "Lagu Pop Indonesia Terbaik Terpopuler", 0xFFDC148C.toInt(), 0xFF5E083C.toInt()),
        CuratedMix("MANCANEGARA", "Lagu Mancanegara", "Pop, R&B, dan Rock internasional", "Best International Pop Songs Top Global Hits", 0xFF1E3264.toInt(), 0xFF0C152B.toInt()),
        CuratedMix("ERA 90-AN", "Lagu Era 90-an", "Nostalgia Indo & Barat tahun 90-an", "Lagu Nostalgia Era 90an Indonesia & Barat Terbaik", 0xFFBA5D07.toInt(), 0xFF4F2703.toInt()),
        CuratedMix("ERA 2000-AN", "Lagu Era 2000-an", "Hits legendaris tahun 2000-an", "Lagu Pop Indonesia & Barat Era 2000an Terpopuler", 0xFF148A08.toInt(), 0xFF083B03.toInt()),
        CuratedMix("SLOW ROCK", "Slow Rock Klasik", "Slow rock Malaysia & Indonesia", "Lagu Slow Rock Malaysia & Indonesia 90an", 0xFF503750.toInt(), 0xFF241824.toInt()),
        CuratedMix("DANGDUT", "Dangdut & Koplo", "Koplo terbaru hingga klasik", "Lagu Dangdut Koplo & Pop Jawa Terpopuler", 0xFF8D67AB.toInt(), 0xFF3C2B4A.toInt()),
        CuratedMix("ALBUM", "Jelajah Album", "Telusuri album penuh artis favorit", "Album Pop Indonesia Terbaik", 0xFF27856A.toInt(), 0xFF10382D.toInt(), isAlbumBrowse = true)
    )

    private var tvHeroBanner: LinearLayout? = null
    private var tvHeroTag: TextView? = null
    private var tvHeroTitle: TextView? = null
    private var tvHeroSubtitle: TextView? = null

    private var lastAlbumSearchQuery = ""
    private var lastFoundAlbums: List<AlbumItem> = emptyList()
    private var searchModeAlbum = false
    private lateinit var pillSearchSongs: TextView
    private lateinit var pillSearchAlbums: TextView

    private var combinedPlaylists: List<UserPlaylist> = emptyList()
    private var selectedPlaylistIdx = 0

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                searchInput.setText(spoken)
                searchInput.setSelection(spoken.length)
                executeSearch()
            }
        }
    }
    private lateinit var browseContainer: LinearLayout
    private lateinit var btnGoogleAccount: TextView
    private lateinit var searchSectionBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var btnClearSearch: TextView

    private lateinit var recentBarBox: LinearLayout
    private lateinit var playlistBarBox: LinearLayout
    private lateinit var playlistChipRow: LinearLayout

    private lateinit var radioBarBox: LinearLayout
    private lateinit var btnScanRadio: TextView
    private lateinit var radioScanSummary: TextView

    private lateinit var listScroll: ScrollView
    private lateinit var listBox: LinearLayout
    private var currentRenderedTracks: List<Track> = emptyList()
    private var lastRenderedPlayingTrackId: String? = null
    private val radioScanMap = ConcurrentHashMap<String, RadioScanResult>()
    private var isScanningRadios = false
    private var activeScanPool: ExecutorService? = null

    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var miniProgressBar: ProgressBar
    private lateinit var miniArtView: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniBtnPlayPause: ImageView

    private var isFullPlayerOpen = false
    private lateinit var fullPlayerOverlay: LinearLayout
    private lateinit var fullBtnKaraoke: TextView
    private lateinit var fullBtnSleepTimer: TextView
    private lateinit var fullBtnNightMode: TextView
    private lateinit var fullBtnAutoDim: TextView
    private lateinit var fullBtnToggleLyrics: TextView
    private lateinit var fullCoverSection: LinearLayout
    private lateinit var fullArtView: ImageView
    private lateinit var fullNowTitle: TextView
    private lateinit var fullNowArtist: TextView
    private lateinit var fullStatusRow: LinearLayout
    private lateinit var fullSpinner: ProgressBar
    private lateinit var fullStatusText: TextView
    private val eqBars = ArrayList<View>()

    private lateinit var fullLyricsSection: LinearLayout
    private lateinit var lyricMiniArt: ImageView
    private lateinit var lyricMiniTitle: TextView
    private lateinit var lyricMiniSub: TextView
    private lateinit var lyricOffsetLabel: TextView
    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricBox: LinearLayout
    private val lyricViews = ArrayList<TextView>()
    private var lastHighlightedIdx = -1
    private var userBrowsingLyricsUntilMs = 0L

    private lateinit var fullQualityLabel: TextView
    private lateinit var fullTimeLabel: TextView
    private lateinit var fullSeekBar: SeekBar
    private lateinit var fullBtnShuffle: ImageView
    private lateinit var fullBtnPlayPause: ImageView
    private lateinit var fullBtnRepeat: ImageView

    private var isNightModeOpen = false
    private var autoDimEnabled = false
    private var lastInteractionMs = 0L
    private lateinit var nightModeOverlay: LinearLayout
    private lateinit var nightClockText: TextView
    private lateinit var nightTrackText: TextView
    private lateinit var nightSleepText: TextView
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

    private lateinit var notice: Notice
    private var isSeeking = false
    private var eqPhase = 0.0

    private val ticker = object : Runnable {
        override fun run() {
            if (isNightModeOpen) {
                updateNightModeClock()
            } else {
                updatePlaybackProgress()
                if (isFullPlayerOpen) {
                    animateEqualizer()
                    if (autoDimEnabled &&
                        MusicEngine.state == PlayState.PLAYING &&
                        System.currentTimeMillis() - lastInteractionMs >= 20_000L
                    ) {
                        openNightMode()
                    }
                }
            }
            ui.postDelayed(this, 280L)
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface = Typeface.SANS_SERIF): TextView {
        return TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this@RadioMusicActivity, sp))
            setTextColor(color)
            typeface = face
        }
    }

    private fun iconView(glyph: Glyph, color: Int, sizeDp: Int): ImageView {
        return ImageView(this).apply {
            setImageDrawable(GlyphDrawable(glyph, color, dp(sizeDp)))
            scaleType = ImageView.ScaleType.CENTER
        }
    }

    private fun setTextLeadingGlyph(tv: TextView, glyph: Glyph, color: Int, sizeDp: Int) {
        val d = GlyphDrawable(glyph, color, dp(sizeDp)).apply {
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }
        tv.compoundDrawablePadding = dp(5)
        tv.setCompoundDrawables(d, null, null, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        voiceInput = VoiceInput(this)
        MusicEngine.lyricsEnabled = prefs.getBoolean("music_lyrics_enabled", false)
        autoDimEnabled = prefs.getBoolean("music_auto_dim_20s", false)
        lastInteractionMs = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 203)
        }

        val isPhonePortrait = isPortraitPhone()
        val padH = dp(if (isPhonePortrait) 14 else 28)

        val root = FrameLayout(this).apply { setBackgroundColor(bgDark) }

        browseContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, dp(10), padH, dp(10))
        }

        // Header B-BOX Music
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val brandBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(brassLight)
            }
        }
        logoCircle.addView(iconView(Glyph.DISC, bgDark, 16), FrameLayout.LayoutParams(-1, -1))
        brandBadge.addView(logoCircle, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(9) })

        val brand = tv("B-BOX MUSIC", 17f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            letterSpacing = 0.08f
        }
        brandBadge.addView(brand)
        brandRow.addView(brandBadge, LinearLayout.LayoutParams(0, -2, 1f))

        btnGoogleAccount = actionPillBtn(if (GoogleMusicAuth.isLoggedIn()) "Google Aktif" else "Login Google") {
            handleGoogleAccountButton()
        }
        brandRow.addView(btnGoogleAccount)
        browseContainer.addView(brandRow)

        // Navigation Tabs
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            setPadding(0, 0, 0, dp(10))
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in tabs.indices) {
            val t = tv(tabs[i], 12.5f, mute, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(15), dp(7), dp(15), dp(7))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f -> styleTab(this, i, f) }
                setOnClickListener { selectTab(i) }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            tabViews.add(t)
        }
        tabScroll.addView(tabRow)
        browseContainer.addView(tabScroll)

        // Search Bar + Voice Mic
        searchSectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(surfaceElevated)
                setStroke(dp(1), 0x28FFFFFF)
            }
        }
        searchBarBox.addView(
            iconView(Glyph.SEARCH, mute, 16),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        )
        searchInput = EditText(this).apply {
            hint = "Apa yang ingin Anda dengarkan?"
            setHintTextColor(0xFF9E9E9E.toInt())
            setTextColor(ivory)
            textSize = 13.5f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    btnClearSearch.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    executeSearch()
                    hideKeyboard()
                    true
                } else false
            }
        }
        val micBtn = voiceInput.createMicButton(searchInput, "Ucapkan judul lagu atau penyanyi...")
        btnClearSearch = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(GlyphDrawable(Glyph.CLOSE, mute, dp(13)))
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1AFFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f) Color.WHITE else 0x1AFFFFFF)
                }
                setImageDrawable(GlyphDrawable(Glyph.CLOSE, if (f) bgDark else mute, dp(13)))
            }
            setOnClickListener {
                searchInput.setText("")
                btnClearSearch.visibility = View.GONE
                renderSpotifyHome(isPortraitPhone())
            }
        }

        val btnVoiceMic = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(GlyphDrawable(Glyph.MIC, brassLight, dp(16)))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22E6C88F)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f) brassLight else 0x22E6C88F)
                }
                setImageDrawable(GlyphDrawable(Glyph.MIC, if (f) bgDark else brassLight, dp(16)))
            }
            setOnClickListener { startIndonesianVoiceSearch() }
        }

        val searchBtn = actionPillBtn("Cari", primary = true) {
            executeSearch()
            hideKeyboard()
        }

        searchBarBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchBarBox.addView(btnClearSearch, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(4) })
        searchBarBox.addView(btnVoiceMic, LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(6) })
        searchBarBox.addView(searchBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        searchSectionBox.addView(searchBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        browseContainer.addView(searchSectionBox)

        // Bar Tab Terakhir
        recentBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceCard)
            }
        }
        recentBarBox.addView(
            tv("Riwayat 15 lagu & radio terakhir diputar", 12.5f, mute),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        recentBarBox.addView(
            actionPillBtn("Bersihkan") {
                RecentHistoryStore.clear(this)
                loadRecentTracks()
            }
        )
        browseContainer.addView(recentBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Bar Tab Koleksi / Playlist
        playlistBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceCard)
            }
        }
        val plScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        playlistChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        plScroll.addView(playlistChipRow)
        playlistBarBox.addView(plScroll, LinearLayout.LayoutParams(0, -2, 1f))

        val btnNewPl = actionPillBtn("Playlist Baru", primary = true) { promptCreatePlaylist() }
        val btnDelPl = actionPillBtn("Hapus") { promptDeleteCurrentPlaylist() }
        playlistBarBox.addView(btnNewPl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        playlistBarBox.addView(btnDelPl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        browseContainer.addView(playlistBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Bar Tab Radio ID
        radioBarBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceCard)
            }
        }
        radioScanSummary = tv("Siap memindai stasiun radio aktif", 12f, mute).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val btnAddManualRadio = actionPillBtn("Tambah URL") { promptAddCustomRadio() }
        btnScanRadio = actionPillBtn("Scan Radio", primary = true) { startRadioScan() }
        radioBarBox.addView(radioScanSummary, LinearLayout.LayoutParams(0, -2, 1f))
        radioBarBox.addView(btnAddManualRadio, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        radioBarBox.addView(btnScanRadio, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        browseContainer.addView(radioBarBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(14))
        }
        listScroll.addView(listBox, FrameLayout.LayoutParams(-1, -2))
        browseContainer.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        miniPlayerBar = buildSpotifyMiniPlayer()
        browseContainer.addView(miniPlayerBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        root.addView(browseContainer, FrameLayout.LayoutParams(-1, -1))

        fullPlayerOverlay = buildSpotifyFullScreenPlayer(isPhonePortrait)
        fullPlayerOverlay.visibility = View.GONE
        root.addView(fullPlayerOverlay, FrameLayout.LayoutParams(-1, -1))

        nightModeOverlay = buildNightModeOverlay()
        nightModeOverlay.visibility = View.GONE
        root.addView(nightModeOverlay, FrameLayout.LayoutParams(-1, -1))

        notice = Notice(this, root)
        setContentView(root)

        MusicEngine.onStateChanged = {
            ui.post {
                if (!isFinishing) {
                    syncAllPlayerViews()
                    val newCurId = MusicEngine.currentTrack?.id
                    if (!isFullPlayerOpen && currentRenderedTracks.isNotEmpty() && newCurId != lastRenderedPlayingTrackId) {
                        val isLocalPl = (activeTab == 1 && combinedPlaylists.getOrNull(selectedPlaylistIdx)?.isGoogleSynced == false)
                        renderTrackList(currentRenderedTracks, isLocalPlaylistTab = isLocalPl, openFullOnPlay = true)
                    }
                }
            }
        }

        refreshGoogleAccountButton()
        selectTab(0)
        syncAllPlayerViews()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastInteractionMs = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInteractionMs = System.currentTimeMillis()
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            isNightModeOpen -> closeNightMode()
            isFullPlayerOpen -> closeFullPlayer()
            else -> super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        lastInteractionMs = System.currentTimeMillis()
        if (isNightModeOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_BACK
            ) {
                closeNightMode()
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                MusicEngine.togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (MusicEngine.state != PlayState.PLAYING) MusicEngine.togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (MusicEngine.state == PlayState.PLAYING) MusicEngine.togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                MusicEngine.playNext(this)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                MusicEngine.playPrev(this)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                MusicEngine.seekBy(-10_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                MusicEngine.seekBy(10_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlaybackCompletely()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SafeArtLoader.clearCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        SafeArtLoader.clearCache()
    }

    override fun onDestroy() {
        MusicEngine.onStateChanged = null
        activeScanPool?.shutdownNow()
        mixArtPool.shutdownNow()
        super.onDestroy()
    }

    private fun stopPlaybackCompletely() {
        MusicEngine.stopAndReleaseNow(clearTrack = true)
        if (isNightModeOpen) {
            isNightModeOpen = false
            nightModeOverlay.visibility = View.GONE
        }
        if (isFullPlayerOpen) {
            closeFullPlayer()
        } else {
            syncAllPlayerViews()
            if (currentRenderedTracks.isNotEmpty()) {
                val isLocalPl = (activeTab == 1 && combinedPlaylists.getOrNull(selectedPlaylistIdx)?.isGoogleSynced == false)
                renderTrackList(currentRenderedTracks, isLocalPlaylistTab = isLocalPl, openFullOnPlay = true)
            }
        }
        notice.show("Dimatikan", "Radio / musik dimatikan sepenuhnya.", 2500L)
    }

    // =========================================================================
    // ADAPTIVE SPOTIFY UI: TV & HP
    // =========================================================================
    private fun isPortraitPhone(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600

    private val mixThumbCache = ConcurrentHashMap<String, String>()
    private val mixArtPool = Executors.newFixedThreadPool(2)

    private fun bindMixCover(mix: CuratedMix, target: ImageView) {
        val key = "mix_${mix.query}"
        target.tag = key
        val cachedUrl = mixThumbCache[mix.query]
        if (!cachedUrl.isNullOrBlank()) {
            SafeArtLoader.load(cachedUrl, target)
            return
        }
        target.setImageDrawable(null)
        mixArtPool.execute {
            val tracks = queryYtMusicTracks(mix.query)
            val foundThumb = tracks.firstOrNull { !it.thumbUrl.isNullOrBlank() }?.thumbUrl
            if (!foundThumb.isNullOrBlank()) {
                mixThumbCache[mix.query] = foundThumb
                ui.post {
                    if (!isFinishing && target.tag == key) {
                        SafeArtLoader.load(foundThumb, target)
                    }
                }
            }
        }
    }

    private fun updateTvHero(mix: CuratedMix) {
        val banner = tvHeroBanner ?: return
        tvHeroTag?.text = "KURASI PILIHAN · ${mix.tag}"
        tvHeroTitle?.text = mix.title
        tvHeroSubtitle?.text = "${mix.subtitle} · Tekan OK pada remote untuk membuka playlist"
        banner.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(mix.colorStart, mix.colorEnd, surfaceCard)
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x26FFFFFF)
        }
    }

    private fun renderSpotifyHome(isPhone: Boolean = isPortraitPhone()) {
        currentRenderedTracks = emptyList()
        lastRenderedPlayingTrackId = null
        btnClearSearch.visibility = View.GONE
        tvHeroBanner = null
        listBox.removeAllViews()
        listScroll.scrollTo(0, 0)

        val defaultMix = quickShortcuts.first()

        if (!isPhone) {
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(defaultMix.colorStart, defaultMix.colorEnd, surfaceCard)
                ).apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0x26FFFFFF)
                }
            }
            val tag = tv("KURASI PILIHAN · ${defaultMix.tag}", 10f, 0xCCFFFFFF.toInt(), Typeface.DEFAULT_BOLD).apply {
                letterSpacing = 0.08f
            }
            val title = tv(defaultMix.title, 22f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(3))
            }
            val sub = tv("${defaultMix.subtitle} · Tekan OK pada remote untuk membuka playlist", 12.5f, 0xDDFFFFFF.toInt()).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            hero.addView(tag)
            hero.addView(title)
            hero.addView(sub)

            tvHeroBanner = hero
            tvHeroTag = tag
            tvHeroTitle = title
            tvHeroSubtitle = sub
            listBox.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            listBox.addView(
                tv("Pilihan Cepat", 15.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                    setPadding(dp(2), 0, 0, dp(8))
                }
            )
        }

        val shortcutCols = if (isPhone) 2 else 4
        val tileHeight = dp(if (isPhone) 50 else 54)
        var idx = 0
        while (idx < quickShortcuts.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until shortcutCols) {
                if (idx < quickShortcuts.size) {
                    val mix = quickShortcuts[idx]
                    val tile = buildShortcutTile(mix, tileHeight, isPhone)
                    val lp = LinearLayout.LayoutParams(0, tileHeight, 1f).apply {
                        if (col < shortcutCols - 1) rightMargin = dp(8)
                    }
                    row.addView(tile, lp)
                    idx++
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, tileHeight, 1f))
                }
            }
            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        listBox.addView(
            tv("Mix & Kurasi Untuk Anda", 15.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(2), dp(8), 0, dp(8))
            }
        )

        val mixScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            setPadding(0, 0, 0, dp(6))
        }
        val mixRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cardSize = dp(if (isPhone) 132 else 142)
        for (mix in editorialMixes) {
            mixRow.addView(
                buildSpotifyShelfCard(mix, cardSize),
                LinearLayout.LayoutParams(cardSize, -2).apply { rightMargin = dp(10) }
            )
        }
        mixScroll.addView(mixRow)
        listBox.addView(mixScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        listBox.addView(
            tv("Jelajahi Kategori & Era", 15.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                setPadding(dp(2), dp(6), 0, dp(8))
            }
        )

        val cols = if (isPhone) 2 else 4
        var gIdx = 0
        while (gIdx < browseGenreCards.size) {
            val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                if (gIdx < browseGenreCards.size) {
                    val item = browseGenreCards[gIdx]
                    val card = buildGenreBrowseCard(item)
                    val lp = LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                        if (c < cols - 1) rightMargin = dp(8)
                    }
                    gRow.addView(card, lp)
                    gIdx++
                } else {
                    gRow.addView(View(this), LinearLayout.LayoutParams(0, dp(72), 1f))
                }
            }
            listBox.addView(gRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun buildShortcutTile(mix: CuratedMix, tileHeightPx: Int, isPhone: Boolean): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(surfaceElevated)
            }
            clipToOutline = true
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (f) 0xFF333333.toInt() else surfaceElevated)
                    if (f) setStroke(dp(2), Color.WHITE)
                }
                if (f) updateTvHero(mix)
            }
            setOnClickListener { openCuratedMixDetail(mix) }
        }

        val leftSwatch = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix.colorStart, mix.colorEnd)
            )
        }
        leftSwatch.addView(iconView(Glyph.DISC, 0xCCFFFFFF.toInt(), 18), FrameLayout.LayoutParams(-1, -1))
        val coverImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        bindMixCover(mix, coverImg)
        leftSwatch.addView(coverImg, FrameLayout.LayoutParams(-1, -1))
        tile.addView(leftSwatch, LinearLayout.LayoutParams(tileHeightPx, -1))

        val label = tv(
            mix.title,
            if (isPhone) 12.5f else 13f,
            ivory,
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
        }
        tile.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        return tile
    }

    private fun buildSpotifyShelfCard(mix: CuratedMix, cardSizePx: Int): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, f ->
                val artFrame = (v as ViewGroup).getChildAt(0) as FrameLayout
                val borderView = artFrame.getChildAt(artFrame.childCount - 1)
                borderView.visibility = if (f) View.VISIBLE else View.GONE
                if (f) updateTvHero(mix)
            }
            setOnClickListener { openCuratedMixDetail(mix) }
        }

        val artFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix.colorStart, mix.colorEnd)
            ).apply {
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }

        artFrame.addView(
            View(this).apply { background = SpotifyCardDecorDrawable(showCenterVinyl = true) },
            FrameLayout.LayoutParams(-1, -1)
        )

        val coverImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        bindMixCover(mix, coverImg)
        artFrame.addView(coverImg, FrameLayout.LayoutParams(-1, -1))

        artFrame.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x22000000, 0x33000000, 0xDF000000.toInt())
                )
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val overlayCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val topTag = tv(mix.tag, 8.5f, ivory, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.08f
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x99000000.toInt())
            }
        }
        val tagWrap = LinearLayout(this).apply { setPadding(dp(9), dp(9), dp(9), 0) }
        tagWrap.addView(topTag)
        overlayCol.addView(tagWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), dp(8))
        }
        val accentBar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(mix.colorStart)
            }
        }
        titleRow.addView(accentBar, LinearLayout.LayoutParams(dp(4), dp(24)).apply { rightMargin = dp(7) })
        titleRow.addView(
            tv(mix.title, 13f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        overlayCol.addView(titleRow)

        val bottomAccentLine = View(this).apply { setBackgroundColor(mix.colorStart) }
        overlayCol.addView(bottomAccentLine, LinearLayout.LayoutParams(-1, dp(4)))

        artFrame.addView(overlayCol, FrameLayout.LayoutParams(-1, -1))

        val focusBorder = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
        }
        artFrame.addView(focusBorder, FrameLayout.LayoutParams(-1, -1))

        wrapper.addView(artFrame, LinearLayout.LayoutParams(cardSizePx, cardSizePx))
        wrapper.addView(
            tv(mix.subtitle, 11f, mute).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }
        )
        return wrapper
    }

    private fun buildGenreBrowseCard(mix: CuratedMix): FrameLayout {
        val focusOutline = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
        }

        val card = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix.colorStart, mix.colorEnd)
            ).apply {
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
            setOnFocusChangeListener { _, f ->
                focusOutline.visibility = if (f) View.VISIBLE else View.GONE
                if (f) updateTvHero(mix)
            }
            setOnClickListener {
                if (mix.isAlbumBrowse) {
                    searchYtAlbums(mix.query)
                } else {
                    openCuratedMixDetail(mix)
                }
            }
        }

        card.addView(
            View(this).apply { background = SpotifyCardDecorDrawable(showCenterVinyl = false) },
            FrameLayout.LayoutParams(-1, -1)
        )

        val tiltBoxSize = dp(58)
        val tiltedArtFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(0x44000000)
                setStroke(dp(1), 0x33FFFFFF)
            }
            clipToOutline = true
            rotation = 24f
            translationX = dp(10).toFloat()
            translationY = dp(9).toFloat()
            elevation = dp(4).toFloat()
        }
        tiltedArtFrame.addView(iconView(Glyph.DISC, 0xCCFFFFFF.toInt(), 24), FrameLayout.LayoutParams(-1, -1))
        val tiltedImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        bindMixCover(mix, tiltedImg)
        tiltedArtFrame.addView(tiltedImg, FrameLayout.LayoutParams(-1, -1))

        card.addView(
            tiltedArtFrame,
            FrameLayout.LayoutParams(tiltBoxSize, tiltBoxSize, Gravity.BOTTOM or Gravity.END)
        )

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(54), dp(10))
        }
        textCol.addView(
            tv(mix.tag, 8.5f, 0xCCFFFFFF.toInt(), Typeface.DEFAULT_BOLD).apply {
                letterSpacing = 0.08f
            }
        )
        textCol.addView(
            tv(mix.title, 13.5f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
        )
        card.addView(textCol, FrameLayout.LayoutParams(-1, -1))
        card.addView(focusOutline, FrameLayout.LayoutParams(-1, -1))
        return card
    }

    private fun openCuratedMixDetail(mix: CuratedMix) {
        btnClearSearch.visibility = View.VISIBLE
        listBox.removeAllViews()
        listScroll.scrollTo(0, 0)

        val backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        backRow.addView(actionPillBtn("Kembali ke Jelajah", leadingGlyph = Glyph.BACK) {
            renderSpotifyHome()
        })
        listBox.addView(backRow)

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(mix.colorStart, surfaceCard)
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
        }

        val topInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val coverSquare = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix.colorStart, mix.colorEnd)
            ).apply {
                cornerRadius = dp(10).toFloat()
            }
        }
        coverSquare.addView(
            tv(mix.tag, 8.5f, 0xCCFFFFFF.toInt(), Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        coverSquare.addView(
            tv(mix.title, 12f, ivory, Typeface.DEFAULT_BOLD).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        topInfoRow.addView(coverSquare, LinearLayout.LayoutParams(dp(74), dp(74)))

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        metaCol.addView(
            tv("PLAYLIST KURASI · ${mix.tag}", 10f, 0xDDFFFFFF.toInt(), Typeface.DEFAULT_BOLD).apply {
                letterSpacing = 0.08f
            }
        )
        metaCol.addView(
            tv(mix.title, 18f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(3))
            }
        )
        metaCol.addView(
            tv(mix.subtitle, 12f, 0xCCFFFFFF.toInt()).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        topInfoRow.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f))
        heroCard.addView(topInfoRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val btnPlayAll = actionPillBtn("Putar", primary = true, leadingGlyph = Glyph.PLAY) {
            if (currentRenderedTracks.isNotEmpty()) {
                MusicEngine.shuffleEnabled = false
                MusicEngine.playTrack(this, currentRenderedTracks[0], currentRenderedTracks, 0)
                openFullPlayer()
            }
        }
        val btnShuffleAll = actionPillBtn("Acak", leadingGlyph = Glyph.SHUFFLE) {
            if (currentRenderedTracks.isNotEmpty()) {
                MusicEngine.shuffleEnabled = true
                val rnd = Random.nextInt(currentRenderedTracks.size)
                MusicEngine.playTrack(this, currentRenderedTracks[rnd], currentRenderedTracks, rnd)
                openFullPlayer()
            }
        }
        val btnSaveMix = actionPillBtn("Simpan", leadingGlyph = Glyph.PLUS) {
            if (currentRenderedTracks.isNotEmpty()) {
                showSaveAlbumToPlaylistDialog(mix.title, currentRenderedTracks)
            }
        }
        actionRow.addView(btnPlayAll)
        actionRow.addView(btnShuffleAll, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionRow.addView(btnSaveMix, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        heroCard.addView(actionRow)

        listBox.addView(heroCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val loadingView = tv("Menyiapkan daftar lagu...", 13f, mute).apply {
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        listBox.addView(loadingView)

        Thread {
            val tracks = queryYtMusicTracks(mix.query)
            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeView(loadingView)
                if (tracks.isEmpty()) {
                    listBox.addView(tv("Tidak ada lagu ditemukan untuk kurasi ini.", 13f, mute))
                } else {
                    renderTrackList(tracks, clearFirst = false, openFullOnPlay = true)
                    btnPlayAll.requestFocus()
                }
            }
        }.start()
    }

    private fun executeSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) {
            renderSpotifyHome()
            return
        }
        btnClearSearch.visibility = View.VISIBLE
        if (searchModeAlbum) {
            searchYtAlbums(q)
        } else {
            searchYtMusicSongs(q)
        }
    }

    private fun buildSearchModeHeader(query: String): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        pillSearchSongs = tv("Lagu", 12f, if (!searchModeAlbum) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isSingleLine = true
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (!searchModeAlbum) brassLight else surfaceElevated)
            }
            setOnClickListener {
                searchModeAlbum = false
                searchYtMusicSongs(query)
            }
        }
        pillSearchAlbums = tv("Album", 12f, if (searchModeAlbum) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isSingleLine = true
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (searchModeAlbum) brassLight else surfaceElevated)
            }
            setOnClickListener {
                searchModeAlbum = true
                searchYtAlbums(query)
            }
        }
        val btnBackHome = actionPillBtn("Jelajah", leadingGlyph = Glyph.BACK) {
            searchInput.setText("")
            renderSpotifyHome()
        }
        bar.addView(pillSearchSongs)
        bar.addView(pillSearchAlbums, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(btnBackHome)
        return bar
    }

    // =========================================================================
    // MODE LAYAR GELAP (NIGHT MODE)
    // =========================================================================
    private fun buildNightModeOverlay(): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isFocusable = true
            isClickable = true
            setOnClickListener { closeNightMode() }
        }

        nightClockText = tv("00:00", 52f, 0xFF5C5448.toInt(), Typeface.create(Typeface.SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
        }
        screen.addView(nightClockText)

        nightTrackText = tv("Audio sedang diputar...", 14f, 0xFF474138.toInt(), Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(6))
        }
        screen.addView(nightTrackText)

        nightSleepText = tv("", 12.5f, 0xFF6B5A3E.toInt()).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(12))
        }
        screen.addView(nightSleepText)

        screen.addView(
            tv("Mode Layar Gelap · Ketuk layar atau tekan OK di remote untuk kembali", 11.5f, 0xFF332F29.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            }
        )
        return screen
    }

    private fun openNightMode() {
        isNightModeOpen = true
        fullPlayerOverlay.visibility = View.GONE
        browseContainer.visibility = View.GONE
        nightModeOverlay.visibility = View.VISIBLE
        nightModeOverlay.requestFocus()
        updateNightModeClock()
    }

    private fun closeNightMode() {
        isNightModeOpen = false
        lastInteractionMs = System.currentTimeMillis()
        nightModeOverlay.visibility = View.GONE
        if (isFullPlayerOpen) {
            fullPlayerOverlay.visibility = View.VISIBLE
            fullBtnNightMode.requestFocus()
        } else {
            browseContainer.visibility = View.VISIBLE
        }
        syncAllPlayerViews()
    }

    private fun updateNightModeClock() {
        nightClockText.text = clockFormat.format(Date())
        val cur = MusicEngine.currentTrack
        nightTrackText.text = if (cur != null) "${cur.title} · ${cur.artist}" else "Tidak ada audio"
        val rem = MusicEngine.getSleepRemainingText()
        if (rem.isNotEmpty()) {
            nightSleepText.visibility = View.VISIBLE
            nightSleepText.text = "Sleep Timer aktif: $rem"
        } else {
            nightSleepText.visibility = View.GONE
        }
    }

    // =========================================================================
    // MINI PLAYER & FULL-SCREEN PLAYER RESPONSIF (TV & HP)
    // =========================================================================
    private fun buildSpotifyMiniPlayer(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF22201D.toInt())
                setStroke(dp(1), 0x44B8975A)
            }
            clipToOutline = true
        }

        miniProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(brassLight)
            progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
        }
        wrapper.addView(miniProgressBar, LinearLayout.LayoutParams(-1, dp(2)))

        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        val expandArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(6), dp(2))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, f ->
                background = if (f) {
                    GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(0x28FFFFFF)
                    }
                } else null
            }
            setOnClickListener { openFullPlayer() }
        }

        val artBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF2B2B2B.toInt())
            }
            clipToOutline = true
        }
        artBox.addView(iconView(Glyph.DISC, mute, 20), FrameLayout.LayoutParams(-1, -1))
        miniArtView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(miniArtView, FrameLayout.LayoutParams(-1, -1))
        expandArea.addView(artBox, LinearLayout.LayoutParams(dp(42), dp(42)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        miniTitle = tv("Belum ada lagu", 13f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniSubtitle = tv("Ketuk untuk layar penuh", 11f, mute).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(miniTitle)
        textCol.addView(miniSubtitle)
        expandArea.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        contentRow.addView(expandArea, LinearLayout.LayoutParams(0, -2, 1f))

        val btnMiniPrev = miniVectorBtn(Glyph.PREV) { MusicEngine.playPrev(this) }
        miniBtnPlayPause = miniVectorBtn(Glyph.PLAY, primary = true) { MusicEngine.togglePause() }
        val btnMiniStop = miniVectorBtn(Glyph.STOP, danger = true) { stopPlaybackCompletely() }
        val btnMiniNext = miniVectorBtn(Glyph.NEXT) { MusicEngine.playNext(this) }

        contentRow.addView(btnMiniPrev)
        contentRow.addView(miniBtnPlayPause, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(5) })
        contentRow.addView(btnMiniStop, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(5) })
        contentRow.addView(btnMiniNext, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(5) })

        wrapper.addView(contentRow)
        return wrapper
    }

    private fun miniVectorBtn(
        glyph: Glyph,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit
    ): ImageView {
        val baseBg = when {
            primary -> ivory
            danger -> 0x26E06C65
            else -> 0x1AFFFFFF
        }
        val baseFg = when {
            primary -> bgDark
            danger -> redDead
            else -> ivory
        }
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(GlyphDrawable(glyph, baseFg, dp(15)))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(baseBg)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f) brassLight else baseBg)
                }
                setImageDrawable(GlyphDrawable(glyph, if (f) bgDark else baseFg, dp(15)))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildSpotifyFullScreenPlayer(isPhone: Boolean): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPhone) 16 else 34), dp(14), dp(if (isPhone) 16 else 34), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF28221A.toInt(), 0xFF141312.toInt(), 0xFF0E0E0E.toInt())
            )
            isFocusable = false
            isClickable = true
        }

        val topScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            setPadding(0, 0, 0, dp(10))
        }
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnMinimize = actionPillBtn("Perkecil", leadingGlyph = Glyph.CHEVRON_DOWN) { closeFullPlayer() }
        topHeader.addView(btnMinimize)

        fullBtnKaraoke = actionPillBtn("Karaoke: OFF", leadingGlyph = Glyph.MIC) {
            MusicEngine.cycleKaraokeLevel { msg ->
                notice.show("Mode Karaoke", msg, 3000L)
            }
            syncAllPlayerViews()
        }
        fullBtnKaraoke.setOnFocusChangeListener { _, f -> styleKaraokeButton(f) }
        topHeader.addView(fullBtnKaraoke, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        fullBtnToggleLyrics = actionPillBtn(
            if (MusicEngine.lyricsEnabled) "Lirik: AKTIF" else "Lirik: OFF",
            leadingGlyph = Glyph.LYRICS
        ) {
            MusicEngine.lyricsEnabled = !MusicEngine.lyricsEnabled
            prefs.edit().putBoolean("music_lyrics_enabled", MusicEngine.lyricsEnabled).apply()
            if (MusicEngine.lyricsEnabled) {
                MusicEngine.refreshLyricsIfNeeded()
            }
            syncAllPlayerViews()
        }
        fullBtnToggleLyrics.setOnFocusChangeListener { _, f -> styleLyricsToggleButton(f) }
        topHeader.addView(fullBtnToggleLyrics, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        fullBtnSleepTimer = actionPillBtn("Timer", leadingGlyph = Glyph.TIMER) { showSleepTimerDialog() }
        fullBtnSleepTimer.setOnFocusChangeListener { _, f -> styleSleepButton(f) }
        topHeader.addView(fullBtnSleepTimer, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        fullBtnNightMode = actionPillBtn("Layar Gelap", leadingGlyph = Glyph.MOON) { openNightMode() }
        topHeader.addView(fullBtnNightMode, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        fullBtnAutoDim = actionPillBtn(if (autoDimEnabled) "Auto Gelap: 20d" else "Auto Gelap: OFF") {
            autoDimEnabled = !autoDimEnabled
            prefs.edit().putBoolean("music_auto_dim_20s", autoDimEnabled).apply()
            lastInteractionMs = System.currentTimeMillis()
            syncAllPlayerViews()
            notice.show(
                "Layar Gelap Otomatis",
                if (autoDimEnabled) "Layar otomatis meredup setelah 20 detik pemutaran." else "Layar gelap otomatis dimatikan.",
                2500L
            )
        }
        fullBtnAutoDim.setOnFocusChangeListener { _, f -> styleAutoDimButton(f) }
        topHeader.addView(fullBtnAutoDim, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        val btnSaveFull = actionPillBtn("Simpan", leadingGlyph = Glyph.PLUS) {
            val cur = MusicEngine.currentTrack
            if (cur != null) showAddToPlaylistDialog(cur)
        }
        topHeader.addView(btnSaveFull, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        topScroll.addView(topHeader)
        page.addView(topScroll)

        fullCoverSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = false
        }

        val artSize = dp(if (isPhone) 200 else 215)
        val bigArtFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF222222.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            clipToOutline = true
        }
        bigArtFrame.addView(iconView(Glyph.DISC, 0xFF4A4A4A.toInt(), 64), FrameLayout.LayoutParams(-1, -1))
        fullArtView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        bigArtFrame.addView(fullArtView, FrameLayout.LayoutParams(-1, -1))
        fullCoverSection.addView(bigArtFrame, LinearLayout.LayoutParams(artSize, artSize).apply {
            bottomMargin = dp(16)
        })

        fullNowTitle = tv("Belum ada lagu diputar", if (isPhone) 19f else 22f, ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fullNowArtist = tv("Pilih lagu atau album", 14f, mute).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
        }
        fullCoverSection.addView(fullNowTitle, LinearLayout.LayoutParams(-1, -2))
        fullCoverSection.addView(fullNowArtist, LinearLayout.LayoutParams(-1, -2))

        fullStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = false
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x1EFFFFFF)
            }
        }
        fullSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }
        fullStatusRow.addView(fullSpinner, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) })

        fullStatusText = tv("Siap memutar", 11.5f, mute, Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        fullStatusRow.addView(fullStatusText)

        val eqBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), 0, 0, 0)
        }
        eqBars.clear()
        for (i in 0 until 4) {
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(brassLight)
                }
            }
            eqBox.addView(bar, LinearLayout.LayoutParams(dp(3), dp(6)).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
            eqBars.add(bar)
        }
        fullStatusRow.addView(eqBox, LinearLayout.LayoutParams(-2, dp(16)))
        fullCoverSection.addView(fullStatusRow, LinearLayout.LayoutParams(-2, -2))

        page.addView(fullCoverSection, LinearLayout.LayoutParams(-1, 0, 1f))

        fullLyricsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isFocusable = false
        }

        val lyricSongBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x1FFFFFFF)
            }
        }
        val smallArtFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF262626.toInt())
            }
            clipToOutline = true
        }
        lyricMiniArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        smallArtFrame.addView(lyricMiniArt, FrameLayout.LayoutParams(-1, -1))
        lyricSongBar.addView(smallArtFrame, LinearLayout.LayoutParams(dp(42), dp(42)))

        val lyricMetaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        lyricMiniTitle = tv("Judul Lagu", 14f, ivory, Typeface.DEFAULT_BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lyricMiniSub = tv("Penyanyi", 11.5f, mute).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lyricMetaCol.addView(lyricMiniTitle)
        lyricMetaCol.addView(lyricMiniSub)
        lyricSongBar.addView(lyricMetaCol, LinearLayout.LayoutParams(0, -2, 1f))
        fullLyricsSection.addView(lyricSongBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val syncControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x16FFFFFF)
            }
        }
        val btnLyricMinus = actionPillBtn("-0.5 dtk") {
            MusicEngine.lyricOffsetMs -= 500L
            updateLyricOffsetLabel()
        }
        lyricOffsetLabel = tv("Sinkron: 0.0 dtk", 11.5f, brassLight, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, f ->
                background = if (f) {
                    GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.WHITE)
                    }
                } else null
                setTextColor(if (f) bgDark else brassLight)
            }
            setOnClickListener {
                MusicEngine.lyricOffsetMs = 0L
                updateLyricOffsetLabel()
                notice.show("Sinkron Lirik", "Waktu lirik direset ke 0.0 detik.", 2000L)
            }
        }
        val btnLyricPlus = actionPillBtn("+0.5 dtk") {
            MusicEngine.lyricOffsetMs += 500L
            updateLyricOffsetLabel()
        }
        syncControlRow.addView(btnLyricMinus)
        syncControlRow.addView(lyricOffsetLabel, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
        syncControlRow.addView(btnLyricPlus)
        fullLyricsSection.addView(syncControlRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        lyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF141414.toInt())
                setStroke(dp(1), 0x22FFFFFF)
            }
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        lyricBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = false
        }
        lyricScroll.addView(lyricBox, FrameLayout.LayoutParams(-1, -2))
        fullLyricsSection.addView(lyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(fullLyricsSection, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        fullQualityLabel = tv("B-BOX AUDIO", 10.5f, mute, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.08f
        }
        fullTimeLabel = tv("00:00 / 00:00", 11.5f, mute, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.END
        }
        timeRow.addView(fullQualityLabel, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(fullTimeLabel)
        bottomControls.addView(timeRow)

        fullSeekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ivory)
            thumbTintList = ColorStateList.valueOf(ivory)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setPadding(0, dp(4), 0, dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = MusicEngine.player?.duration ?: 0
                        if (dur > 0) {
                            val target = (dur * progress) / 1000L
                            fullTimeLabel.text = "${fmtTime(target)} / ${fmtTime(dur)}"
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeeking = false
                    val exo = MusicEngine.player ?: return
                    val dur = exo.duration
                    if (dur > 0 && sb != null) {
                        exo.seekTo((dur * sb.progress) / 1000L)
                    }
                }
            })
        }
        bottomControls.addView(fullSeekBar, LinearLayout.LayoutParams(-1, -2))

        val smallBtnDp = if (isPhone) 34 else 42
        val medBtnDp = if (isPhone) 40 else 48
        val bigBtnDp = if (isPhone) 54 else 60
        val gapOuter = dp(if (isPhone) 8 else 12)
        val gapInner = dp(if (isPhone) 10 else 14)

        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        fullBtnShuffle = transportVectorBtn(Glyph.SHUFFLE, boxDp = smallBtnDp, iconDp = if (isPhone) 15 else 18) {
            MusicEngine.shuffleEnabled = !MusicEngine.shuffleEnabled
            syncAllPlayerViews()
            notice.show("Mode Acak", if (MusicEngine.shuffleEnabled) "Acak lagu aktif" else "Acak lagu nonaktif", 2000L)
        }
        fullBtnShuffle.setOnFocusChangeListener { _, f ->
            styleTransportToggleBtn(fullBtnShuffle, Glyph.SHUFFLE, MusicEngine.shuffleEnabled, f, if (isPhone) 15 else 18)
        }

        val btnPrev = transportVectorBtn(Glyph.PREV, boxDp = medBtnDp, iconDp = if (isPhone) 18 else 21) { MusicEngine.playPrev(this) }
        fullBtnPlayPause = transportVectorBtn(Glyph.PLAY, boxDp = bigBtnDp, iconDp = if (isPhone) 22 else 25, primary = true) { MusicEngine.togglePause() }
        val btnStop = transportVectorBtn(Glyph.STOP, boxDp = medBtnDp, iconDp = if (isPhone) 17 else 20, danger = true) { stopPlaybackCompletely() }
        val btnNext = transportVectorBtn(Glyph.NEXT, boxDp = medBtnDp, iconDp = if (isPhone) 18 else 21) { MusicEngine.playNext(this) }

        fullBtnRepeat = transportVectorBtn(Glyph.REPEAT, boxDp = smallBtnDp, iconDp = if (isPhone) 15 else 18) {
            MusicEngine.repeatMode = (MusicEngine.repeatMode + 1) % 3
            syncAllPlayerViews()
            val msg = when (MusicEngine.repeatMode) {
                1 -> "Ulang semua daftar aktif"
                2 -> "Ulang 1 lagu aktif"
                else -> "Mode ulang nonaktif"
            }
            notice.show("Mode Ulang", msg, 2000L)
        }
        fullBtnRepeat.setOnFocusChangeListener { _, f ->
            val g = if (MusicEngine.repeatMode == 2) Glyph.REPEAT_ONE else Glyph.REPEAT
            styleTransportToggleBtn(fullBtnRepeat, g, MusicEngine.repeatMode > 0, f, if (isPhone) 15 else 18)
        }

        transportRow.addView(fullBtnShuffle)
        transportRow.addView(btnPrev, LinearLayout.LayoutParams(dp(medBtnDp), dp(medBtnDp)).apply { leftMargin = gapOuter })
        transportRow.addView(fullBtnPlayPause, LinearLayout.LayoutParams(dp(bigBtnDp), dp(bigBtnDp)).apply { leftMargin = gapInner })
        transportRow.addView(btnStop, LinearLayout.LayoutParams(dp(medBtnDp), dp(medBtnDp)).apply { leftMargin = gapInner })
        transportRow.addView(btnNext, LinearLayout.LayoutParams(dp(medBtnDp), dp(medBtnDp)).apply { leftMargin = gapOuter })
        transportRow.addView(fullBtnRepeat, LinearLayout.LayoutParams(dp(smallBtnDp), dp(smallBtnDp)).apply { leftMargin = gapOuter })
        bottomControls.addView(transportRow)

        page.addView(bottomControls)
        return page
    }

    private fun transportVectorBtn(
        glyph: Glyph,
        boxDp: Int,
        iconDp: Int,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit
    ): ImageView {
        val baseBg = when {
            primary -> ivory
            danger -> 0x26E06C65
            else -> 0x1AFFFFFF
        }
        val baseFg = when {
            primary -> bgDark
            danger -> redDead
            else -> ivory
        }
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(GlyphDrawable(glyph, baseFg, dp(iconDp)))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(baseBg)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f) brassLight else baseBg)
                }
                setImageDrawable(GlyphDrawable(glyph, if (f) bgDark else baseFg, dp(iconDp)))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun styleKaraokeButton(focused: Boolean) {
        val level = MusicEngine.vocalReduceLevel
        val bg = when {
            focused -> Color.WHITE
            level == 1 -> brassLight
            level == 2 -> spotifyGreen
            else -> surfaceElevated
        }
        val fg = if (focused || level > 0) bgDark else ivory
        fullBtnKaraoke.setTextColor(fg)
        setTextLeadingGlyph(fullBtnKaraoke, Glyph.MIC, fg, 14)
        fullBtnKaraoke.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bg)
            if (focused) setStroke(dp(2), brassLight)
        }
    }

    private fun styleLyricsToggleButton(focused: Boolean) {
        val active = MusicEngine.lyricsEnabled
        val bg = when {
            focused -> Color.WHITE
            active -> 0x33E6C88F
            else -> surfaceElevated
        }
        val fg = when {
            focused -> bgDark
            active -> brassLight
            else -> mute
        }
        fullBtnToggleLyrics.setTextColor(fg)
        setTextLeadingGlyph(fullBtnToggleLyrics, Glyph.LYRICS, fg, 14)
        fullBtnToggleLyrics.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bg)
            if (focused) setStroke(dp(2), brassLight)
        }
    }

    private fun styleSleepButton(focused: Boolean) {
        val active = MusicEngine.getSleepRemainingText().isNotEmpty()
        val bg = when {
            focused -> Color.WHITE
            active -> 0x33E6C88F
            else -> surfaceElevated
        }
        val fg = when {
            focused -> bgDark
            active -> brassLight
            else -> ivory
        }
        fullBtnSleepTimer.setTextColor(fg)
        setTextLeadingGlyph(fullBtnSleepTimer, Glyph.TIMER, fg, 14)
        fullBtnSleepTimer.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bg)
            if (focused) setStroke(dp(2), brassLight)
        }
    }

    private fun styleAutoDimButton(focused: Boolean) {
        val bg = when {
            focused -> Color.WHITE
            autoDimEnabled -> 0x33E6C88F
            else -> surfaceElevated
        }
        val fg = when {
            focused -> bgDark
            autoDimEnabled -> brassLight
            else -> mute
        }
        fullBtnAutoDim.setTextColor(fg)
        fullBtnAutoDim.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bg)
            if (focused) setStroke(dp(2), brassLight)
        }
    }

    private fun styleTransportToggleBtn(btn: ImageView, glyph: Glyph, active: Boolean, focused: Boolean, iconDp: Int) {
        val fg = if (focused) bgDark else if (active) spotifyGreen else mute
        btn.setImageDrawable(GlyphDrawable(glyph, fg, dp(iconDp)))
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (focused) brassLight else if (active) 0x261DB954 else 0x1AFFFFFF)
        }
    }

    private fun updateLyricOffsetLabel() {
        val sec = MusicEngine.lyricOffsetMs / 1000.0
        val sign = if (sec > 0) "+" else ""
        lyricOffsetLabel.text = String.format(Locale.US, "Sinkron: %s%.1f dtk", sign, sec)
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            "15 Menit",
            "30 Menit",
            "45 Menit",
            "60 Menit (1 Jam)",
            "90 Menit (1,5 Jam)",
            "Matikan Sleep Timer"
        )
        val minutes = intArrayOf(15, 30, 45, 60, 90, 0)
        AlertDialog.Builder(this)
            .setTitle("Sleep Timer (Otomatis Hentikan Audio)")
            .setItems(options) { _, which ->
                val min = minutes[which]
                MusicEngine.setSleepTimerMinutes(min)
                if (min > 0) {
                    notice.show("Sleep Timer Aktif", "Audio akan berhenti otomatis dalam $min menit.", 3500L)
                } else {
                    notice.show("Sleep Timer", "Timer tidur dinonaktifkan.", 2500L)
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun openFullPlayer() {
        isFullPlayerOpen = true
        lastInteractionMs = System.currentTimeMillis()
        browseContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browseContainer.visibility = View.GONE
        fullPlayerOverlay.visibility = View.VISIBLE
        syncAllPlayerViews()
        fullPlayerOverlay.post {
            fullBtnPlayPause.requestFocus()
        }
    }

    private fun closeFullPlayer() {
        isFullPlayerOpen = false
        fullPlayerOverlay.visibility = View.GONE
        browseContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        browseContainer.visibility = View.VISIBLE
        syncAllPlayerViews()
        if (currentRenderedTracks.isNotEmpty()) {
            val isLocalPl = (activeTab == 1 && combinedPlaylists.getOrNull(selectedPlaylistIdx)?.isGoogleSynced == false)
            renderTrackList(currentRenderedTracks, isLocalPlaylistTab = isLocalPl, openFullOnPlay = true)
        }
        browseContainer.post {
            if (miniPlayerBar.visibility == View.VISIBLE) {
                miniBtnPlayPause.requestFocus()
            } else {
                tabViews.getOrNull(activeTab)?.requestFocus()
            }
        }
    }

    private fun actionPillBtn(
        label: String,
        primary: Boolean = false,
        leadingGlyph: Glyph? = null,
        onClick: () -> Unit
    ): TextView {
        val baseBg = if (primary) brassLight else surfaceElevated
        val baseFg = if (primary) bgDark else ivory
        return tv(label, 11.5f, baseFg, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            isSingleLine = true
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            if (leadingGlyph != null) {
                setTextLeadingGlyph(this, leadingGlyph, baseFg, 13)
            }
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(baseBg)
            }
            setOnFocusChangeListener { _, f ->
                val curBg = if (f) Color.WHITE else baseBg
                val curFg = if (f || primary) bgDark else ivory
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(curBg)
                }
                setTextColor(curFg)
                if (leadingGlyph != null) {
                    setTextLeadingGlyph(this, leadingGlyph, curFg, 13)
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun styleTab(v: TextView, idx: Int, focused: Boolean) {
        val active = idx == activeTab
        v.setTextColor(if (focused || active) bgDark else ivory)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            when {
                focused -> setColor(Color.WHITE)
                active -> setColor(brassLight)
                else -> setColor(surfaceElevated)
            }
        }
    }

    private fun selectTab(idx: Int) {
        activeTab = idx
        for (i in tabViews.indices) styleTab(tabViews[i], i, tabViews[i].hasFocus())
        searchSectionBox.visibility = if (idx == 0) View.VISIBLE else View.GONE
        playlistBarBox.visibility = if (idx == 1) View.VISIBLE else View.GONE
        recentBarBox.visibility = if (idx == 2) View.VISIBLE else View.GONE
        radioBarBox.visibility = if (idx == 3) View.VISIBLE else View.GONE
        currentRenderedTracks = emptyList()
        lastRenderedPlayingTrackId = null
        listBox.removeAllViews()

        when (idx) {
            0 -> {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) executeSearch() else renderSpotifyHome()
            }
            1 -> loadUserPlaylists()
            2 -> loadRecentTracks()
            3 -> loadIndonesianRadios()
            4 -> loadMurottalAndLofi()
            5 -> loadLocalMusic()
        }
    }

    private fun loadRecentTracks() {
        listBox.removeAllViews()
        val recents = RecentHistoryStore.getRecent(this)
        if (recents.isEmpty()) {
            listBox.addView(
                tv(
                    "Belum ada riwayat pemutaran.\n\nLagu atau stasiun radio yang Anda putar akan otomatis tersimpan di sini agar mudah diputar ulang.",
                    13f,
                    mute
                ).apply {
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                    setLineSpacing(0f, 1.3f)
                }
            )
            return
        }
        renderTrackList(recents, openFullOnPlay = true)
    }

    private fun refreshGoogleAccountButton() {
        val logged = GoogleMusicAuth.isLoggedIn()
        btnGoogleAccount.text = if (logged) "Google Aktif" else "Login Google"
        btnGoogleAccount.setTextColor(if (logged) spotifyGreen else ivory)
    }

    private fun handleGoogleAccountButton() {
        if (GoogleMusicAuth.isLoggedIn()) {
            AlertDialog.Builder(this)
                .setTitle("Akun Google Terhubung")
                .setMessage("Sesi YouTube Music Anda aktif. Playlist dari akun Google bisa langsung dibuka di tab Koleksi.")
                .setPositiveButton("Buka Koleksi") { _, _ -> selectTab(1) }
                .setNeutralButton("Login Ulang") { _, _ -> openGoogleLoginDialog() }
                .setNegativeButton("Keluar Akun") { _, _ ->
                    GoogleMusicAuth.logout()
                    refreshGoogleAccountButton()
                    notice.show("Akun Google", "Sesi akun Google telah dihapus.", 3000L)
                    if (activeTab == 1) loadUserPlaylists()
                }
                .show()
        } else {
            openGoogleLoginDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openGoogleLoginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val infoBar = tv(
            "Masuk ke akun Google Anda untuk menyinkronkan Playlist YouTube Music.",
            12f,
            Color.BLACK,
            Typeface.DEFAULT_BOLD
        ).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF3E9D2.toInt())
        }
        container.addView(infoBar)

        val wv = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(420))
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = GoogleMusicAuth.CHROME_UA
        }
        container.addView(wv)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Login Akun Google")
            .setView(container)
            .setNegativeButton("Tutup", null)
            .create()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val u = url ?: ""
                if (u.startsWith("https://music.youtube.com") && GoogleMusicAuth.isLoggedIn()) {
                    CookieManager.getInstance().flush()
                    refreshGoogleAccountButton()
                    notice.show("Login Berhasil", "Akun Google terhubung! Memuat koleksi Anda...", 3500L)
                    dialog.dismiss()
                    selectTab(1)
                }
            }
        }

        dialog.setOnDismissListener {
            try {
                wv.stopLoading()
                wv.clearHistory()
                wv.clearCache(true)
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                container.removeView(wv)
                wv.destroy()
            } catch (_: Exception) {
            }
        }

        dialog.show()
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F&hl=id")
    }

    private fun queryYtMusicTracks(query: String): List<Track> {
        val results = ArrayList<Track>()
        try {
            val conn = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)
            val auth = GoogleMusicAuth.buildSapisidHashAuth()
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth)
                conn.setRequestProperty("Cookie", GoogleMusicAuth.getCookie())
            }

            val safeQ = query.replace("\"", "")
            val payload = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"query":"$safeQ","params":"EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"}"""
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val rootJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                extractMusicItems(rootJson, results)
            }
        } catch (_: Exception) {
        }
        return results
    }

    private fun searchYtMusicSongs(query: String) {
        listBox.removeAllViews()
        listBox.addView(buildSearchModeHeader(query))
        val loading = tv("Mencari \"$query\"...", 13f, mute)
        listBox.addView(loading)

        Thread {
            val results = queryYtMusicTracks(query)
            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeView(loading)
                if (results.isEmpty()) {
                    listBox.addView(tv("Tidak ditemukan hasil untuk \"$query\".", 13f, mute))
                } else {
                    val headerBar = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), 0, dp(2), dp(8))
                    }
                    headerBar.addView(
                        tv("Hasil Teratas (${results.size} Lagu)", 13f, ivory, Typeface.DEFAULT_BOLD),
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    headerBar.addView(
                        actionPillBtn("Putar Semua", primary = true, leadingGlyph = Glyph.PLAY) {
                            MusicEngine.playTrack(this, results[0], results, 0)
                            openFullPlayer()
                        }
                    )
                    listBox.addView(headerBar)
                    renderTrackList(results, clearFirst = false, openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun searchYtAlbums(query: String) {
        lastAlbumSearchQuery = query
        btnClearSearch.visibility = View.VISIBLE
        listBox.removeAllViews()
        if (searchInput.text.toString().trim().isNotEmpty()) {
            listBox.addView(buildSearchModeHeader(query))
        } else {
            val backRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            backRow.addView(actionPillBtn("Kembali ke Jelajah", leadingGlyph = Glyph.BACK) {
                renderSpotifyHome()
            })
            listBox.addView(backRow)
        }
        val loading = tv("Mencari album \"$query\"...", 13f, mute)
        listBox.addView(loading)

        Thread {
            val albums = ArrayList<AlbumItem>()
            try {
                val conn = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Origin", "https://music.youtube.com")
                conn.setRequestProperty("User-Agent", GoogleMusicAuth.CHROME_UA)

                val safeQ = query.replace("\"", "")
                val payload = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241106.01.00","hl":"id","gl":"ID"}},"query":"$safeQ","params":"EgWKAQIYAWoKEAMQBBAJEAoQBQ%3D%3D"}"""
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val rootJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    extractAlbumItems(rootJson, albums)
                }
            } catch (_: Exception) {
            }

            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeView(loading)
                lastFoundAlbums = albums
                renderAlbumList(albums, clearFirst = false)
            }
        }.start()
    }

    private fun extractAlbumItems(node: Any?, out: MutableList<AlbumItem>) {
        if (out.size >= 20 || node == null) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    val browseId = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "") ?: ""
                    val cols = renderer.optJSONArray("flexColumns")
                    var title = ""
                    var sub = ""
                    if (cols != null && cols.length() > 0) {
                        title = readRunsText(cols.getJSONObject(0))
                        if (cols.length() > 1) sub = readRunsText(cols.getJSONObject(1))
                    }
                    val thumbs = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                        thumbs.getJSONObject(thumbs.length() - 1).optString("url", "").ifBlank { null }
                    } else null

                    if (browseId.isNotEmpty() && title.isNotEmpty() && out.none { it.browseId == browseId }) {
                        out.add(AlbumItem(browseId, title, sub.ifBlank { "Album" }, thumbUrl))
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) extractAlbumItems(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractAlbumItems(node.opt(i), out)
            }
        }
    }

    private fun renderAlbumList(albums: List<AlbumItem>, clearFirst: Boolean = true) {
        currentRenderedTracks = emptyList()
        lastRenderedPlayingTrackId = null
        if (clearFirst) listBox.removeAllViews()
        if (albums.isEmpty()) {
            listBox.addView(tv("Album tidak ditemukan. Ketik nama penyanyi atau judul album pada kotak pencarian.", 13f, mute))
            return
        }

        listBox.addView(
            tv("Album Tersedia", 14.5f, ivory, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(2), 0, 0, dp(8))
            }
        )

        for (album in albums) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(12), dp(9))
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(surfaceCard)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (f) surfaceElevated else surfaceCard)
                        if (f) setStroke(dp(2), brassLight)
                    }
                }
                setOnClickListener { openAlbumDetail(album) }
            }

            val coverFrame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFF282828.toInt())
                }
                clipToOutline = true
            }
            coverFrame.addView(iconView(Glyph.DISC, mute, 24), FrameLayout.LayoutParams(-1, -1))
            val coverImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            SafeArtLoader.load(album.thumbUrl, coverImg)
            coverFrame.addView(coverImg, FrameLayout.LayoutParams(-1, -1))
            row.addView(coverFrame, LinearLayout.LayoutParams(dp(52), dp(52)))

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            col.addView(tv(album.title, 14f, ivory, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(tv(album.subtitle, 12f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val openPill = tv("ALBUM", 10f, brassLight, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x22E6C88F)
                }
            }
            row.addView(openPill)

            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun openAlbumDetail(album: AlbumItem) {
        listBox.removeAllViews()
        listBox.addView(tv("Memuat lagu dalam album \"${album.title}\"...", 13f, mute))

        Thread {
            val artistHint = album.subtitle.split("•", "·").firstOrNull {
                !it.trim().equals("Album", true) && !it.trim().equals("EP", true) && !it.trim().equals("Single", true)
            }?.trim() ?: album.title
            val tracks = PlaylistStore.fetchBrowseTracks(
                browseId = album.browseId,
                fallbackArtist = artistHint,
                fallbackThumb = album.thumbUrl
            )

            ui.post {
                if (isFinishing || activeTab != 0) return@post
                listBox.removeAllViews()

                val backRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(10))
                }
                val btnBackAlbums = actionPillBtn("Kembali ke Album", leadingGlyph = Glyph.BACK) {
                    if (lastFoundAlbums.isNotEmpty()) {
                        renderAlbumList(lastFoundAlbums)
                    } else {
                        searchYtAlbums(lastAlbumSearchQuery.ifEmpty { "Album Pop Indonesia Terbaik" })
                    }
                }
                backRow.addView(btnBackAlbums)
                listBox.addView(backRow)

                val headerCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xFF2B2620.toInt(), surfaceCard)
                    ).apply {
                        cornerRadius = dp(14).toFloat()
                    }
                }
                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val artBox = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(0xFF262626.toInt())
                    }
                    clipToOutline = true
                }
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                SafeArtLoader.load(album.thumbUrl, img)
                artBox.addView(img, FrameLayout.LayoutParams(-1, -1))
                topRow.addView(artBox, LinearLayout.LayoutParams(dp(74), dp(74)))

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                }
                infoCol.addView(tv(album.title, 16f, ivory, Typeface.DEFAULT_BOLD).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                infoCol.addView(tv("${album.subtitle} · ${tracks.size} Lagu", 12f, mute).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                topRow.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
                headerCard.addView(topRow)

                if (tracks.isNotEmpty()) {
                    val btnRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(12), 0, 0)
                    }
                    val btnPlayAlbum = actionPillBtn("Putar Semua", primary = true, leadingGlyph = Glyph.PLAY) {
                        MusicEngine.playTrack(this, tracks[0], tracks, 0)
                        openFullPlayer()
                    }
                    val btnSaveAlbum = actionPillBtn("Simpan", leadingGlyph = Glyph.PLUS) {
                        showSaveAlbumToPlaylistDialog(album.title, tracks)
                    }
                    btnRow.addView(btnPlayAlbum)
                    btnRow.addView(btnSaveAlbum, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                    headerCard.addView(btnRow)
                }
                listBox.addView(headerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                if (tracks.isEmpty()) {
                    listBox.addView(tv("Daftar lagu album ini tidak tersedia.", 13f, mute))
                } else {
                    renderTrackList(tracks, clearFirst = false, openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun showSaveAlbumToPlaylistDialog(albumTitle: String, tracks: List<Track>) {
        val all = PlaylistStore.getLocalPlaylists(this)
        val names = all.map { "${it.name} (${it.tracks.size})" }.toMutableList()
        names.add("Buat Playlist Baru \"$albumTitle\"")

        AlertDialog.Builder(this)
            .setTitle("Simpan ${tracks.size} lagu ke:")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == all.size) {
                    val newIdx = PlaylistStore.createPlaylist(this, albumTitle)
                    val added = PlaylistStore.addManyTracksToPlaylist(this, newIdx, tracks)
                    notice.show("Tersimpan", "$added lagu disimpan ke playlist \"$albumTitle\".", 3500L)
                } else {
                    val added = PlaylistStore.addManyTracksToPlaylist(this, which, tracks)
                    notice.show("Tersimpan", "$added lagu ditambahkan ke \"${all[which].name}\".", 3500L)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun extractMusicItems(node: Any?, out: MutableList<Track>) {
        if (out.size >= 25 || node == null) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    val vid = renderer.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
                    val cols = renderer.optJSONArray("flexColumns")
                    var title = ""
                    var artist = ""
                    if (cols != null && cols.length() > 0) {
                        title = readRunsText(cols.getJSONObject(0))
                        if (cols.length() > 1) artist = readRunsText(cols.getJSONObject(1))
                    }
                    if (vid.isNotEmpty() && title.isNotEmpty() && out.none { it.id == vid }) {
                        out.add(
                            Track(
                                id = vid,
                                title = title,
                                artist = artist.ifBlank { "Music" },
                                thumbUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                isLive = false,
                                isYtMusic = true
                            )
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractMusicItems(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractMusicItems(node.opt(i), out)
                }
            }
        }
    }

    private fun readRunsText(obj: JSONObject?): String {
        val runs = obj?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    // =========================================================================
    // TAB KOLEKSI / PLAYLIST SAYA
    // =========================================================================
    private fun loadUserPlaylists() {
        listBox.removeAllViews()
        listBox.addView(tv("Memuat koleksi playlist...", 13f, mute))

        Thread {
            val locals = PlaylistStore.getLocalPlaylists(this)
            val googlePls = if (GoogleMusicAuth.isLoggedIn()) {
                PlaylistStore.fetchGoogleAccountPlaylists()
            } else {
                emptyList()
            }
            val merged = ArrayList<UserPlaylist>()
            merged.addAll(locals)
            merged.addAll(googlePls)

            ui.post {
                if (isFinishing || activeTab != 1) return@post
                combinedPlaylists = merged
                if (selectedPlaylistIdx !in combinedPlaylists.indices) selectedPlaylistIdx = 0
                renderPlaylistChipsAndTracks()
            }
        }.start()
    }

    private fun renderPlaylistChipsAndTracks() {
        playlistChipRow.removeAllViews()
        for ((idx, pl) in combinedPlaylists.withIndex()) {
            val isSel = idx == selectedPlaylistIdx
            val label = if (pl.isGoogleSynced && pl.tracks.isEmpty()) pl.name else "${pl.name} (${pl.tracks.size})"
            val chip = tv(label, 12f, if (isSel) bgDark else ivory, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(13), dp(6), dp(13), dp(6))
                isSingleLine = true
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (isSel) brassLight else surfaceElevated)
                }
                setOnFocusChangeListener { _, f ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(if (f) Color.WHITE else if (isSel) brassLight else surfaceElevated)
                    }
                    setTextColor(if (f || isSel) bgDark else ivory)
                }
                setOnClickListener {
                    selectedPlaylistIdx = idx
                    renderPlaylistChipsAndTracks()
                }
            }
            playlistChipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }

        val currentPl = combinedPlaylists.getOrNull(selectedPlaylistIdx) ?: return
        listBox.removeAllViews()

        if (currentPl.isGoogleSynced && currentPl.tracks.isEmpty() && currentPl.browseId.isNotEmpty()) {
            listBox.addView(tv("Mengambil daftar lagu dari ${currentPl.name}...", 13f, mute))
            Thread {
                val fetched = PlaylistStore.fetchBrowseTracks(currentPl.browseId)
                currentPl.tracks.clear()
                currentPl.tracks.addAll(fetched)
                ui.post {
                    if (!isFinishing && activeTab == 1) {
                        renderPlaylistChipsAndTracks()
                    }
                }
            }.start()
            return
        }

        if (currentPl.tracks.isEmpty()) {
            val hintMsg = if (currentPl.isGoogleSynced) {
                "Tidak ada lagu ditemukan di \"${currentPl.name}\"."
            } else {
                "Playlist \"${currentPl.name}\" masih kosong.\n\nTekan tombol \"+\" pada lagu atau \"Simpan\" pada kurasi mix untuk menambahkannya ke sini."
            }
            listBox.addView(
                tv(hintMsg, 13f, mute).apply {
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                    setLineSpacing(0f, 1.3f)
                }
            )
            return
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        headerRow.addView(
            tv("${currentPl.name} · ${currentPl.tracks.size} Lagu", 14f, ivory, Typeface.DEFAULT_BOLD),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        headerRow.addView(
            actionPillBtn("Putar Semua", primary = true, leadingGlyph = Glyph.PLAY) {
                MusicEngine.playTrack(this@RadioMusicActivity, currentPl.tracks[0], currentPl.tracks, 0)
                openFullPlayer()
            }
        )
        listBox.addView(headerRow)

        renderTrackList(currentPl.tracks, clearFirst = false, isLocalPlaylistTab = !currentPl.isGoogleSynced, openFullOnPlay = true)
    }

    private fun promptCreatePlaylist() {
        val input = EditText(this).apply {
            hint = "Nama playlist baru (misal: Favorit Keluarga)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Buat Playlist Baru")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    selectedPlaylistIdx = PlaylistStore.createPlaylist(this, name)
                    if (activeTab == 1) loadUserPlaylists()
                    notice.show("Playlist Dibuat", "\"$name\" siap diisi lagu.", 3000L)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun promptDeleteCurrentPlaylist() {
        val target = combinedPlaylists.getOrNull(selectedPlaylistIdx) ?: return
        if (target.isGoogleSynced) {
            notice.show("Info", "Playlist dari akun Google tidak bisa dihapus dari sini.", 3000L)
            return
        }
        val locals = PlaylistStore.getLocalPlaylists(this)
        if (locals.size <= 1) {
            notice.show("Info", "Minimal harus ada 1 playlist lokal tersimpan.", 3000L)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus Playlist?")
            .setMessage("Hapus playlist \"${target.name}\" beserta seluruh isinya?")
            .setPositiveButton("Hapus") { _, _ ->
                PlaylistStore.deletePlaylist(this, selectedPlaylistIdx)
                selectedPlaylistIdx = 0
                loadUserPlaylists()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val all = PlaylistStore.getLocalPlaylists(this)
        val names = all.map { "${it.name} (${it.tracks.size})" }.toMutableList()
        names.add("Buat Playlist Baru...")

        AlertDialog.Builder(this)
            .setTitle("Simpan \"${track.title}\" ke:")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == all.size) {
                    val input = EditText(this).apply {
                        hint = "Nama playlist baru..."
                        setSingleLine(true)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Playlist Baru")
                        .setView(input)
                        .setPositiveButton("Simpan") { _, _ ->
                            val nm = input.text.toString().trim()
                            if (nm.isNotEmpty()) {
                                val newIdx = PlaylistStore.createPlaylist(this, nm)
                                PlaylistStore.addTrackToPlaylist(this, newIdx, track)
                                notice.show("Tersimpan", "Ditambahkan ke \"$nm\".", 3000L)
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else {
                    val added = PlaylistStore.addTrackToPlaylist(this, which, track)
                    if (added) {
                        notice.show("Tersimpan", "Ditambahkan ke \"${all[which].name}\".", 3000L)
                    } else {
                        notice.show("Sudah Ada", "Lagu ini sudah ada di \"${all[which].name}\".", 3000L)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // =========================================================================
    // TAB RADIO ID, MUROTTAL & MUSIK LOKAL
    // =========================================================================
    private fun promptAddCustomRadio() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val nameInput = EditText(this).apply {
            hint = "Nama Stasiun (misal: RRI Paser / Radio Lokal)"
            setSingleLine(true)
        }
        val cityInput = EditText(this).apply {
            hint = "Kota / Keterangan (misal: Tanah Grogot · FM)"
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = "URL Stream (https://...mp3 / .aac / .m3u8)"
            setSingleLine(true)
        }
        box.addView(nameInput)
        box.addView(cityInput)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Tambah Stasiun Radio Manual")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val nm = nameInput.text.toString().trim()
                val ct = cityInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (nm.isNotEmpty() && u.startsWith("http", ignoreCase = true)) {
                    CustomRadioStore.addRadio(this, nm, ct, u)
                    notice.show("Radio Ditambahkan", "\"$nm\" berhasil disimpan.", 3000L)
                    if (activeTab == 3) loadIndonesianRadios()
                } else {
                    notice.show("Format Tidak Valid", "Pastikan nama diisi dan URL diawali http:// atau https://", 3500L)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadIndonesianRadios() {
        val customRadios = CustomRadioStore.getRadios(this)
        val curated = listOf(
            Track("rri1", "RRI Pro 1 Nasional", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9014/rrijakartapro1.mp3", isLive = true),
            Track("rri2", "RRI Pro 2 Musik & Kreativitas", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9015/rrijakartapro2.mp3", isLive = true),
            Track("rri3", "RRI Pro 3 Berita Nasional", "Radio Republik Indonesia", "https://stream-node1.rri.co.id/streaming/14/9016/rrijakartapro3.mp3", isLive = true),
            Track("elshinta", "Elshinta News & Talk", "Jakarta · Berita & Lalu Lintas", "https://stream-ssl.arenastreaming.com:8000/jakarta", isLive = true),
            Track("prambors", "Prambors FM", "Hits Anak Muda Indonesia", "https://22283.live.streamtheworld.com/PRAMBORS_FMAAC.aac", isLive = true),
            Track("hardrock", "Hard Rock FM Jakarta", "Pop & Lifestyle", "https://n01.radiojar.com/7csmg90fuqruv", isLive = true),
            Track("iradio", "I-Radio Jakarta", "100% Musik Indonesia", "https://n02.radiojar.com/4ywdgup3bnzuv", isLive = true),
            Track("delta", "Delta FM", "Lagu Enak Terus", "https://s1.cloudmu.id/listen/delta_fm/radio.mp3", isLive = true)
        )
        val initialList = ArrayList<Track>().apply {
            addAll(customRadios)
            addAll(curated)
        }
        renderTrackList(initialList, openFullOnPlay = true)

        Thread {
            val fetched = ArrayList<Track>(initialList)
            try {
                val conn = URL("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/ID?limit=25&order=clickcount&reverse=true&hidebroken=true").openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 9000
                conn.setRequestProperty("User-Agent", "BBoxTV/1.0")
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name", "").trim()
                        val u = o.optString("url_resolved", o.optString("url", "")).trim()
                        val tags = o.optString("tags", "Radio Indonesia")
                        if (name.isNotEmpty() && u.startsWith("http") && fetched.none { it.title.equals(name, true) }) {
                            fetched.add(Track("rb_$i", name, tags.ifBlank { "Radio Indonesia" }, u, o.optString("favicon", "").ifBlank { null }, isLive = true))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            ui.post {
                if (!isFinishing && activeTab == 3 && fetched.size > initialList.size) {
                    renderTrackList(sortRadiosByScan(fetched), openFullOnPlay = true)
                }
            }
        }.start()
    }

    private fun startRadioScan() {
        if (isScanningRadios || currentRenderedTracks.isEmpty()) return
        val targets = currentRenderedTracks.toList()
        isScanningRadios = true
        btnScanRadio.text = "Memindai..."
        radioScanSummary.text = "Mengecek ${targets.size} stasiun..."

        for (t in targets) {
            radioScanMap[t.id] = RadioScanResult(1, "CEK", "Menguji koneksi")
        }
        renderTrackList(targets, openFullOnPlay = true)

        val doneCount = AtomicInteger(0)
        val activeCount = AtomicInteger(0)
        val deadCount = AtomicInteger(0)
        activeScanPool?.shutdownNow()
        val pool = Executors.newFixedThreadPool(4)
        activeScanPool = pool

        for (t in targets) {
            pool.execute {
                val res = probeRadioStation(t.streamUrl)
                radioScanMap[t.id] = res
                if (res.state == 2) activeCount.incrementAndGet() else deadCount.incrementAndGet()
                val done = doneCount.incrementAndGet()

                ui.post {
                    if (isFinishing) return@post
                    radioScanSummary.text = "Scan: $done/${targets.size} (${activeCount.get()} aktif · ${deadCount.get()} mati)"
                    if (done >= targets.size) {
                        isScanningRadios = false
                        btnScanRadio.text = "Scan Ulang"
                        radioScanSummary.text = "Selesai: ${activeCount.get()} Aktif · ${deadCount.get()} Mati"
                        if (activeTab == 3 && !isFullPlayerOpen) {
                            renderTrackList(sortRadiosByScan(targets), openFullOnPlay = true)
                        }
                    } else if (activeTab == 3 && !isFullPlayerOpen && done % 4 == 0) {
                        renderTrackList(targets, openFullOnPlay = true)
                    }
                }
            }
        }
        pool.shutdown()
    }

    private fun sortRadiosByScan(list: List<Track>): List<Track> {
        return list.sortedBy { t ->
            when (radioScanMap[t.id]?.state) {
                2 -> 0
                1, null -> 1
                else -> 2
            }
        }
    }

    private fun probeRadioStation(rawUrl: String): RadioScanResult {
        var cur = rawUrl.trim()
        if (!cur.startsWith("http", ignoreCase = true)) {
            return RadioScanResult(3, "MATI", "Alamat URL tidak valid")
        }
        val ua = GoogleMusicAuth.CHROME_UA
        for (hop in 0..3) {
            try {
                val conn = URL(cur).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4500
                conn.readTimeout = 4500
                conn.setRequestProperty("User-Agent", ua)
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location") ?: run {
                        conn.disconnect()
                        return RadioScanResult(3, "MATI", "Redirect kosong")
                    }
                    cur = URL(URL(cur), loc).toString()
                    conn.disconnect()
                    continue
                }
                if (code in 200..299) {
                    val ctype = (conn.contentType ?: "").lowercase(Locale.US)
                    val buf = ByteArray(256)
                    val readBytes = try {
                        conn.inputStream.use { it.read(buf) }
                    } catch (_: Exception) {
                        -1
                    }
                    conn.disconnect()
                    if (readBytes <= 0) {
                        return RadioScanResult(3, "MATI", "Tidak ada data audio")
                    }
                    if (ctype.contains("text/html")) {
                        return RadioScanResult(3, "MATI", "Server mengembalikan halaman HTML")
                    }
                    return RadioScanResult(2, "AKTIF", "Siap diputar")
                } else {
                    conn.disconnect()
                    return RadioScanResult(3, "MATI", "HTTP $code")
                }
            } catch (_: Exception) {
                return RadioScanResult(3, "MATI", "Server timeout / offline")
            }
        }
        return RadioScanResult(3, "MATI", "Terlalu banyak pengalihan")
    }

    private fun loadMurottalAndLofi() {
        val list = listOf(
            Track("murottal1", "Murottal Al-Qur'an 24 Jam", "Mishary Rashid Alafasy", "https://backup.qurango.net/radio/mishary_alafasi", isLive = true),
            Track("murottal2", "Radio Al-Qur'an Makkah", "Siaran Langsung Masjidil Haram", "https://backup.qurango.net/radio/tarateel", isLive = true),
            Track("murottal3", "Murottal Abdurrahman As-Sudais", "Imam Masjidil Haram", "https://backup.qurango.net/radio/abdulrahman_alsudaes", isLive = true),
            Track("lofi1", "Lofi Hip Hop & Belajar", "Musik Santai Tanpa Vokal", "https://streams.ilovemusic.de/iloveradio17.mp3", isLive = true),
            Track("piano1", "Instrumental Piano & Hujan", "Relaksasi & Pengantar Tidur", "https://strm112.1.fm/spa_mobile_mp3", isLive = true)
        )
        renderTrackList(list, openFullOnPlay = true)
    }

    private fun loadLocalMusic() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 202)
            listBox.addView(tv("Izinkan akses penyimpanan untuk memutar file MP3 lokal (0 kuota).", 13f, mute))
            return
        }
        Thread {
            val locals = ArrayList<Track>()
            try {
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA)
                contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
                    while (c.moveToNext() && locals.size < 100) {
                        val id = c.getLong(0).toString()
                        val title = c.getString(1) ?: "Audio"
                        val artist = c.getString(2) ?: "Lokal"
                        val path = c.getString(3) ?: continue
                        locals.add(Track("loc_$id", title, artist, path, isLive = false))
                    }
                }
            } catch (_: Exception) {
            }
            ui.post {
                if (isFinishing || activeTab != 5) return@post
                listBox.removeAllViews()
                if (locals.isEmpty()) {
                    listBox.addView(tv("Tidak ada file MP3 ditemukan di penyimpanan perangkat.", 13f, mute))
                } else {
                    renderTrackList(locals, openFullOnPlay = true)
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (activeTab == 5) {
                listBox.removeAllViews()
                loadLocalMusic()
            }
        }
    }

    // =========================================================================
    // RENDER DAFTAR LAGU ALA SPOTIFY
    // =========================================================================
    private fun renderTrackList(
        items: List<Track>,
        clearFirst: Boolean = true,
        isLocalPlaylistTab: Boolean = false,
        openFullOnPlay: Boolean = true
    ) {
        val previouslyFocusedId = (currentFocus?.tag as? String)
        currentRenderedTracks = items
        val curId = MusicEngine.currentTrack?.id
        lastRenderedPlayingTrackId = curId

        if (clearFirst) listBox.removeAllViews()
        var viewToRefocus: View? = null

        for ((index, item) in items.withIndex()) {
            val isPlayingThis = item.id == curId
            val scanInfo = if (activeTab == 3) radioScanMap[item.id] else null

            val outerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (isPlayingThis) 0xFF23201B.toInt() else Color.TRANSPARENT)
                }
            }

            val playArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = item.id
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, f ->
                    outerRow.background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(if (f) surfaceElevated else if (isPlayingThis) 0xFF23201B.toInt() else Color.TRANSPARENT)
                        if (f) setStroke(dp(1), brassLight)
                    }
                }
                setOnClickListener {
                    MusicEngine.playTrack(this@RadioMusicActivity, item, items, index)
                    if (openFullOnPlay) openFullPlayer()
                }
            }
            if (previouslyFocusedId == item.id) {
                viewToRefocus = playArea
            }

            val indexLabel = tv(
                if (isPlayingThis) "▶" else "${index + 1}",
                12f,
                if (isPlayingThis) spotifyGreen else mute,
                Typeface.DEFAULT_BOLD
            ).apply {
                gravity = Gravity.CENTER
            }
            playArea.addView(indexLabel, LinearLayout.LayoutParams(dp(24), -2).apply { rightMargin = dp(6) })

            val artFrame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(surfaceElevated)
                }
                clipToOutline = true
            }
            artFrame.addView(
                iconView(if (item.isLive) Glyph.RADIO else Glyph.DISC, mute, 18),
                FrameLayout.LayoutParams(-1, -1)
            )
            val artImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            SafeArtLoader.load(item.thumbUrl, artImg)
            artFrame.addView(artImg, FrameLayout.LayoutParams(-1, -1))
            playArea.addView(artFrame, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(8), 0)
            }
            col.addView(tv(item.title, 13.5f, if (isPlayingThis) brassLight else ivory, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val subText = when {
                scanInfo != null && scanInfo.state == 3 -> "${item.artist} · ${scanInfo.detail}"
                item.isLive -> "Siaran Langsung · ${item.artist}"
                else -> item.artist
            }
            col.addView(tv(subText, 11.5f, mute).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            playArea.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            if (isPlayingThis) {
                val playingPill = tv("DIPUTAR", 9.5f, bgDark, Typeface.DEFAULT_BOLD).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(spotifyGreen)
                    }
                }
                playArea.addView(playingPill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            } else if (scanInfo != null && scanInfo.state != 0) {
                val pillColor = when (scanInfo.state) {
                    2 -> spotifyGreen
                    3 -> redDead
                    else -> brassLight
                }
                val scanPill = tv(scanInfo.label, 9.5f, pillColor, Typeface.DEFAULT_BOLD).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor((pillColor and 0x00FFFFFF) or 0x24000000)
                    }
                }
                playArea.addView(scanPill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }

            outerRow.addView(playArea, LinearLayout.LayoutParams(0, -2, 1f))

            when {
                isLocalPlaylistTab -> {
                    outerRow.addView(actionPillBtn("Hapus") {
                        PlaylistStore.removeTrackFromPlaylist(this, selectedPlaylistIdx, index)
                        loadUserPlaylists()
                    })
                }
                activeTab == 3 && item.id.startsWith("custom_") -> {
                    outerRow.addView(actionPillBtn("Hapus") {
                        CustomRadioStore.deleteRadio(this, item.id)
                        loadIndonesianRadios()
                    })
                }
                else -> {
                    outerRow.addView(rowPlusButton {
                        showAddToPlaylistDialog(item)
                    })
                }
            }

            listBox.addView(outerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }

        if (!isFullPlayerOpen && viewToRefocus != null) {
            viewToRefocus?.requestFocus()
        }
    }

    private fun rowPlusButton(onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(GlyphDrawable(Glyph.PLUS, mute, dp(15)))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x14FFFFFF)
            }
            setOnFocusChangeListener { _, f ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f) brassLight else 0x14FFFFFF)
                }
                setImageDrawable(GlyphDrawable(Glyph.PLUS, if (f) bgDark else mute, dp(15)))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun syncAllPlayerViews() {
        val t = MusicEngine.currentTrack
        val st = MusicEngine.state
        val isActive = (st == PlayState.PLAYING || st == PlayState.BUFFERING)
        val isPhone = isPortraitPhone()
        val bigIconDp = if (isPhone) 22 else 25
        val smallIconDp = if (isPhone) 15 else 18

        if (t == null) {
            miniPlayerBar.visibility = View.GONE
            fullNowTitle.text = "Belum ada lagu diputar"
            fullNowArtist.text = "Pilih lagu atau album"
            miniBtnPlayPause.setImageDrawable(GlyphDrawable(Glyph.PLAY, bgDark, dp(15)))
            fullBtnPlayPause.setImageDrawable(GlyphDrawable(Glyph.PLAY, bgDark, dp(bigIconDp)))
        } else {
            miniPlayerBar.visibility = View.VISIBLE
            miniTitle.text = t.title
            miniSubtitle.text = "${t.artist} · ${MusicEngine.statusDetail}"
            SafeArtLoader.load(t.thumbUrl, miniArtView)
            miniBtnPlayPause.setImageDrawable(GlyphDrawable(if (isActive) Glyph.PAUSE else Glyph.PLAY, bgDark, dp(15)))

            fullNowTitle.text = t.title
            fullNowArtist.text = t.artist
            SafeArtLoader.load(t.thumbUrl, fullArtView)
            fullBtnPlayPause.setImageDrawable(GlyphDrawable(if (isActive) Glyph.PAUSE else Glyph.PLAY, bgDark, dp(bigIconDp)))

            lyricMiniTitle.text = t.title
            lyricMiniSub.text = "${t.artist} · ${MusicEngine.statusDetail}"
            SafeArtLoader.load(t.thumbUrl, lyricMiniArt)
        }

        fullBtnKaraoke.text = when (MusicEngine.vocalReduceLevel) {
            1 -> "Karaoke: NORMAL"
            2 -> "Karaoke: ULTRA"
            else -> "Karaoke: OFF"
        }
        styleKaraokeButton(fullBtnKaraoke.hasFocus())

        updateLyricOffsetLabel()

        val sleepRem = MusicEngine.getSleepRemainingText()
        fullBtnSleepTimer.text = if (sleepRem.isNotEmpty()) sleepRem else "Timer"
        styleSleepButton(fullBtnSleepTimer.hasFocus())

        fullBtnAutoDim.text = if (autoDimEnabled) "Auto Gelap: 20d" else "Auto Gelap: OFF"
        styleAutoDimButton(fullBtnAutoDim.hasFocus())

        fullBtnToggleLyrics.text = if (MusicEngine.lyricsEnabled) "Lirik: AKTIF" else "Lirik: OFF"
        styleLyricsToggleButton(fullBtnToggleLyrics.hasFocus())

        if (MusicEngine.lyricsEnabled) {
            if (fullLyricsSection.visibility != View.VISIBLE) {
                fullCoverSection.visibility = View.GONE
                fullLyricsSection.visibility = View.VISIBLE
                rebuildLyricViews()
            } else if (lyricViews.size != MusicEngine.lyrics.size) {
                rebuildLyricViews()
            }
        } else {
            fullLyricsSection.visibility = View.GONE
            fullCoverSection.visibility = View.VISIBLE
        }

        styleTransportToggleBtn(fullBtnShuffle, Glyph.SHUFFLE, MusicEngine.shuffleEnabled, fullBtnShuffle.hasFocus(), smallIconDp)
        val repGlyph = if (MusicEngine.repeatMode == 2) Glyph.REPEAT_ONE else Glyph.REPEAT
        styleTransportToggleBtn(fullBtnRepeat, repGlyph, MusicEngine.repeatMode > 0, fullBtnRepeat.hasFocus(), smallIconDp)

        val isBusy = (st == PlayState.RESOLVING || st == PlayState.BUFFERING)
        fullSpinner.visibility = if (isBusy) View.VISIBLE else View.GONE
        fullStatusText.text = MusicEngine.statusDetail
        fullQualityLabel.text = MusicEngine.statusDetail.uppercase(Locale.US)

        val pillBgColor = when (st) {
            PlayState.PLAYING -> 0x261DB954
            PlayState.ERROR -> 0x33E06C65
            PlayState.RESOLVING, PlayState.BUFFERING -> 0x28E6C88F
            else -> 0x1AFFFFFF
        }
        val pillTextColor = when (st) {
            PlayState.PLAYING -> spotifyGreen
            PlayState.ERROR -> redDead
            PlayState.RESOLVING, PlayState.BUFFERING -> brassLight
            else -> mute
        }
        fullStatusText.setTextColor(pillTextColor)
        fullStatusRow.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(pillBgColor)
        }
    }

    private fun animateEqualizer() {
        if (eqBars.isEmpty()) return
        val active = MusicEngine.state == PlayState.PLAYING
        if (!active) {
            for (bar in eqBars) {
                val lp = bar.layoutParams
                if (lp.height != dp(4)) {
                    lp.height = dp(4)
                    bar.layoutParams = lp
                }
            }
            return
        }
        eqPhase += 0.85
        for (i in eqBars.indices) {
            val wave = (sin(eqPhase + i * 1.4) + 1.0) * 0.5
            val h = dp(4 + (wave * 11).toInt())
            val lp = eqBars[i].layoutParams
            lp.height = h
            eqBars[i].layoutParams = lp
        }
    }

    private fun rebuildLyricViews() {
        lyricBox.removeAllViews()
        lyricViews.clear()
        lastHighlightedIdx = -1

        val lines = MusicEngine.lyrics
        if (lines.isEmpty()) {
            lyricBox.addView(tv(MusicEngine.lyricStatus, 14.5f, mute).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(36), dp(12), dp(36))
            })
            return
        }

        for ((idx, line) in lines.withIndex()) {
            val ltv = tv(line.text, 16f, 0xFF8C8C8C.toInt(), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setLineSpacing(0f, 1.2f)
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, f ->
                    val isCurrent = idx == lastHighlightedIdx
                    if (f) {
                        userBrowsingLyricsUntilMs = System.currentTimeMillis() + 3500L
                        v.background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(surfaceElevated)
                            setStroke(dp(1), brassLight)
                        }
                        setTextColor(Color.WHITE)
                        val targetY = (v.top - lyricScroll.height / 3).coerceAtLeast(0)
                        lyricScroll.smoothScrollTo(0, targetY)
                    } else {
                        v.background = null
                        setTextColor(if (isCurrent) brassLight else 0xFF8C8C8C.toInt())
                    }
                }
                setOnClickListener {
                    MusicEngine.player?.seekTo((line.timeMs - MusicEngine.lyricOffsetMs).coerceAtLeast(0L))
                    notice.show("Lirik", "Melompat ke: ${line.text.take(32)}", 1800L)
                }
            }
            lyricBox.addView(ltv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            lyricViews.add(ltv)
        }
    }

    private fun updatePlaybackProgress() {
        val exo = MusicEngine.player ?: return
        val track = MusicEngine.currentTrack ?: return
        try {
            if (track.isLive) {
                fullTimeLabel.text = "LIVE · HEMAT KUOTA"
                fullSeekBar.isEnabled = false
                fullSeekBar.progress = 1000
                miniProgressBar.progress = 1000
                return
            }
            fullSeekBar.isEnabled = true
            val pos = exo.currentPosition
            val dur = exo.duration
            if (dur > 0 && !isSeeking) {
                val permille = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                fullSeekBar.progress = permille
                miniProgressBar.progress = permille
                fullTimeLabel.text = "${fmtTime(pos)} / ${fmtTime(dur)}"
            }

            if (!MusicEngine.lyricsEnabled || !isFullPlayerOpen) return

            val lines = MusicEngine.lyrics
            val adjustedPos = pos + MusicEngine.lyricOffsetMs
            if (lines.isNotEmpty() && lyricViews.size == lines.size) {
                var activeIdx = -1
                for (i in lines.indices) {
                    if (adjustedPos >= lines[i].timeMs) activeIdx = i else break
                }
                if (activeIdx != lastHighlightedIdx && activeIdx >= 0) {
                    if (lastHighlightedIdx in lyricViews.indices) {
                        val prevView = lyricViews[lastHighlightedIdx]
                        if (!prevView.hasFocus()) {
                            prevView.setTextColor(0xFF8C8C8C.toInt())
                        }
                        prevView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 16f))
                    }
                    val activeView = lyricViews[activeIdx]
                    if (!activeView.hasFocus()) {
                        activeView.setTextColor(brassLight)
                    }
                    activeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, 20f))
                    lastHighlightedIdx = activeIdx

                    if (System.currentTimeMillis() > userBrowsingLyricsUntilMs) {
                        val targetY = (activeView.top - lyricScroll.height / 3).coerceAtLeast(0)
                        lyricScroll.smoothScrollTo(0, targetY)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun fmtTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun startIndonesianVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Sebutkan judul lagu atau nama penyanyi...")
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            notice.show("Pencarian Suara", "Layanan input suara tidak tersedia di perangkat ini.", 3000L)
        }
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        refreshGoogleAccountButton()
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        super.onStop()
    }
}
