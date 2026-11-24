package com.bayutv.hotel

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class FilmActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFF5F0E6.toInt()
    private val mute = 0xFFB5AEA0.toInt()
    private val bgDark = 0xFF0B0A09.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private val heroCache = LruCache<String, Bitmap>(4)
    private val resolvedBackdrops = HashMap<String, String>()
    private var resolvedImdbId: String = ""

    private val tabs = arrayOf(
        "Beranda", "Film", "Anime & Serial", "Indonesia",
        "YouTube Resmi", "Koleksi Saya", "Daftar Saya", "Cari"
    )
    private val searchIdx = 7
    private val tabViews = ArrayList<TextView>()
    private var tabIdx = -1
    private var token = 0
    private var jfTitles: Set<String> = emptySet()

    private lateinit var heroBackdrop: ImageView
    private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroOverview: TextView

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private var searchDebounce: Runnable? = null

    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout

    private lateinit var detailOverlay: FrameLayout
    private lateinit var detailBackdrop: ImageView
    private lateinit var detailPoster: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailOverview: TextView
    private lateinit var detailProviders: TextView
    private lateinit var detailActions: LinearLayout

    private lateinit var notice: Notice
    private var focusedMovie: Movie? = null

    private data class RankedStream(
        val source: StremioAddons.StreamSource,
        val score: Int,
        val isWebDl: Boolean
    )

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun buildBBoxLogo(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(22), 0)
        }
        val iconFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(brassLight, brass)
            ).apply {
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0x66FFFFFF)
            }
        }
        val iconLetter = tv(
            "B", 16f, 0xFF0E0C0A.toInt(),
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ).apply { gravity = Gravity.CENTER }
        iconFrame.addView(iconLetter, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        box.addView(icon
