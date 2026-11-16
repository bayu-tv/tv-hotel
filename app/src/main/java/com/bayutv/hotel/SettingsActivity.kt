package com.bayutv.hotel

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val brassLight = 0xFFE6C88F.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bayutv", MODE_PRIVATE) }

    private var server: SettingsServer? = null
    private var isPhoneLayout = false
    private var pendingTrial: AppLinks.Trial? = null

    private lateinit var status: TextView
    private lateinit var tvAntennaSubtitle: TextView
    private lateinit var notice: Notice

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun tv(t: String, sp: Float, color: Int, face: Typeface): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(this, sp))
        v.setTextColor(color)
        v.typeface = face
        return v
    }

    private fun localIp(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in en) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun qr(text: String): Bitmap {
        val size = 512
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun cardBg(focused: Boolean, highlight: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(
                when {
                    focused -> ((brass and 0x00FFFFFF) or 0x36000000)
                    highlight -> ((brass and 0x00FFFFFF) or 0x22000000)
                    else -> 0x16FFFFFF
                }
            )
            setStroke(
                dp(if (focused) 2 else 1),
                when {
                    focused -> brassLight
                    highlight -> brass
                    else -> 0x28FFFFFF
                }
            )
        }
    }

    private fun actionButton(
        title: String,
        subtitle: String? = null,
        highlight: Boolean = false,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView?> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(13))
            isFocusable = true
            isClickable = true
            background = cardBg(false, highlight)
        }
        val tView = tv(title, if (isPhoneLayout) 15.5f else 17f, if (highlight) brassLight else ivory, Typeface.SERIF).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(tView)

        var subView: TextView? = null
        if (subtitle != null) {
            subView = tv(subtitle, if (isPhoneLayout) 12.5f else 13.5f, mute, Typeface.SANS_SERIF).apply {
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            box.addView(subView)
        }

        box.setOnClickListener { onClick() }
        box.setOnFocusChangeListener { _, f ->
            box.background = cardBg(f, highlight)
            tView.setTextColor(if (f || highlight) brassLight else ivory)
            val s = if (f) 1.02f else 1f
            box.animate().scaleX(s).scaleY(s).setDuration(160).start()
        }

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        box.layoutParams = lp
        return Pair(box, subView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isPhoneLayout = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            resources.configuration.screenWidthDp < 600

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121110.toInt())

        val ip = localIp()
        val pin = (SecureRandom().nextInt(9000) + 1000).toString()
        val srv = SettingsServer(this, pin) {
            ui.post {
                status.text = "Tersimpan dari HP pukul " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            }
        }
        val started = srv.start()
        server = srv

        // Blok QR Code & Info PIN
        val qrSize = dp(if (isPhoneLayout) 180 else 220)
        val qrBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        qrBox.addView(img, FrameLayout.LayoutParams(-1, -1))

        val leftQrCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        leftQrCol.addView(qrBox, LinearLayout.LayoutParams(qrSize, qrSize))

        if (ip != null && started) {
            val url = "http://" + ip + ":" + srv.port
            img.setImageBitmap(qr(url))
            val urlTv = tv(url, if (isPhoneLayout) 17f else 20f, brassLight, Typeface.SANS_SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            val pinTv = tv("PIN  " + pin, if (isPhoneLayout) 22f else 26f, ivory, Typeface.SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            leftQrCol.addView(urlTv)
            leftQrCol.addView(pinTv)
        } else if (started && isPhoneLayout) {
            val localUrl = "http://127.0.0.1:" + srv.port
            img.setImageBitmap(qr(localUrl))
            val pinTv = tv("PIN  " + pin, 22f, ivory, Typeface.SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            leftQrCol.addView(pinTv)
        } else {
            val errTv = tv("Server belum aktif.", 18f, 0xFFC9706A.toInt(), Typeface.SANS_SERIF).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            leftQrCol.addView(errTv)
        }

        status = tv("Menunggu koneksi pengaturan...", if (isPhoneLayout) 13.5f else 15f, mute, Typeface.SANS_SERIF).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        leftQrCol.addView(status)

        // Kolom Kanan / Bawah: Panduan Web + Menu Antena & Diagnostik
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        rightCol.addView(tv("Pengaturan lewat HP", if (isPhoneLayout) 24f else 28f, brass, Typeface.SERIF))
        val steps = tv(
            if (isPhoneLayout) {
                "Atur nama keluarga, kota jadwal sholat, tema, playlist, dan catatan langsung dari browser HP dengan PIN di atas."
            } else {
                "1. Sambungkan HP ke Wi-Fi yang sama dengan TV.\n" +
                    "2. Pindai kode QR di samping atau buka alamatnya di browser HP.\n" +
                    "3. Masukkan PIN lalu simpan perubahan."
            },
            if (isPhoneLayout) 14f else 16f,
            ivory,
            Typeface.SANS_SERIF
        ).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(8), 0, dp(14))
        }
        rightCol.addView(steps)

        var firstFocusable: View? = null

        if (isPhoneLayout && started) {
            val targetUrl = "http://" + (ip ?: "127.0.0.1") + ":" + srv.port
            val (btnWeb, _) = actionButton(
                "Buka Halaman Pengaturan di Browser",
                "Langsung buka " + targetUrl + " (PIN: " + pin + ")",
                highlight = true
            ) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                } catch (e: Exception) {
                    notice.show("Gagal membuka browser", "Buka manual di Chrome: " + targetUrl, 5000L)
                }
            }
            rightCol.addView(btnWeb)
            firstFocusable = btnWeb
        }

        val divider = View(this).apply { setBackgroundColor(0xFF34302A.toInt()) }
        val dlp = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(14)
        }
        rightCol.addView(divider, dlp)

        val secHead = tv("TV ANTENA & DIAGNOSTIK", 12.5f, brass, Typeface.SANS_SERIF).apply {
            letterSpacing = 0.16f
            setPadding(0, 0, 0, dp(10))
        }
        rightCol.addView(secHead)

        val (btnAntennaApp, subAntenna) = actionButton(
            "Aplikasi TV Antena",
            savedLabel() ?: "Belum dipilih (ketuk untuk memilih)"
        ) {
            chooseAntennaApp()
        }
        if (subAntenna != null) tvAntennaSubtitle = subAntenna
        rightCol.addView(btnAntennaApp)
        if (firstFocusable == null) firstFocusable = btnAntennaApp

        val (btnCompass, _) = actionButton(
            "Kompas MUX & Arah Antena",
            "Panduan arah pemancar TV Digital sesuai lokasi"
        ) {
            startActivity(Intent(this, AntennaAimActivity::class.java))
        }
        rightCol.addView(btnCompass)

        val (btnDiag, _) = actionButton(
            "Uji Tuner & Pintasan Siaran",
            "Antena hybrid di aplikasi, info tuner, & uji pintasan RCTI+ / Vidio"
        ) {
            openDiagnosticMenu()
        }
        rightCol.addView(btnDiag)

        if (isPhoneLayout) {
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(22), dp(20), dp(28))
            }
            val qrLp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
            container.addView(leftQrCol, qrLp)
            container.addView(rightCol, LinearLayout.LayoutParams(-1, -2))
            scroll.addView(container, ViewGroup.LayoutParams(-1, -2))
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(48), dp(28), dp(48), dp(28))
            }
            row.addView(leftQrCol, LinearLayout.LayoutParams(dp(290), -2))
            val rlp = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(40) }
            row.addView(rightCol, rlp)
            root.addView(row, FrameLayout.LayoutParams(-1, -1))
        }

        notice = Notice(this, root)
        setContentView(root)
        firstFocusable?.requestFocus()

        if (ip == null && !isPhoneLayout) {
            notice.show("TV belum terhubung", "Sambungkan TV ke Wi-Fi atau jaringan rumah, lalu buka lagi menu Pengaturan.")
        } else if (!started) {
            notice.show("Server gagal dibuka", "Tutup lalu buka lagi menu Pengaturan.")
        }
    }

    private fun savedLabel(): String? {
        val saved = prefs.getString("antenna", null) ?: return null
        if (saved == "system") return "Aktif: Live TV bawaan sistem"
        return try {
            val cn = ComponentName.unflattenFromString(saved) ?: return null
            "Aktif: " + packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            "Aktif: Aplikasi antena pilihan"
        }
    }

    private fun refreshAntennaLabel() {
        if (::tvAntennaSubtitle.isInitialized) {
            tvAntennaSubtitle.text = savedLabel() ?: "Belum dipilih (ketuk untuk memilih)"
        }
    }

    private fun tryLiveTv(): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchComponent(flat: String): Boolean {
        return try {
            val cn = ComponentName.unflattenFromString(flat) ?: return false
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
            i.component = cn
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun chooseAntennaApp() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val names = ArrayList<String>()
        names.add("Live TV bawaan sistem")
        names.add("Antena di dalam aplikasi (hybrid)")
        for (a in apps) names.add(a.loadLabel(pm).toString())

        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi TV Antena")
            .setItems(names.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putString("antenna", "system").apply()
                        refreshAntennaLabel()
                        if (!tryLiveTv()) {
                            notice.show("Disimpan", "Live TV bawaan dipilih. Jika tidak otomatis terbuka, pilih aplikasi antena lain.", 5000L)
                        }
                    }
                    1 -> startActivity(Intent(this, AntennaActivity::class.java))
                    else -> {
                        val ri = apps[which - 2]
                        val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                        prefs.edit().putString("antenna", cn.flattenToString()).apply()
                        refreshAntennaLabel()
                        launchComponent(cn.flattenToString())
                    }
                }
            }
            .show()
    }

    private fun openDiagnosticMenu() {
        AlertDialog.Builder(this)
            .setTitle("Uji Tuner & Pintasan Siaran")
            .setItems(
                arrayOf(
                    "Antena di dalam aplikasi (uji hybrid)",
                    "Uji tuner antena (cek input & daftar siaran)",
                    "Uji pintasan RCTI+ / Vidio"
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AntennaActivity::class.java))
                    1 -> runTunerTest()
                    else -> testAppLinks()
                }
            }
            .show()
    }

    private fun runTunerTest() {
        val sb = StringBuilder()
        try {
            val granted = checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED
            sb.append("Izin baca daftar TV: " + (if (granted) "diberikan" else "TIDAK diberikan") + "\n")
            if (!granted) {
                try {
                    requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), 77)
                } catch (e: Exception) {
                }
            }
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            val inputs = tim.tvInputList
            sb.append("Input TV terlihat: " + inputs.size + "\n")
            for (i in inputs) {
                val type = when (i.type) {
                    TvInputInfo.TYPE_TUNER -> "TUNER"
                    TvInputInfo.TYPE_HDMI -> "HDMI"
                    TvInputInfo.TYPE_OTHER -> "LAINNYA"
                    else -> "tipe " + i.type
                }
                sb.append("- " + type + ": " + i.loadLabel(this) + "\n")
            }
            val tuner = inputs.firstOrNull { it.type == TvInputInfo.TYPE_TUNER }
            if (tuner == null) {
                sb.append("\nTidak ada input bertipe TUNER yang terlihat.\n")
            } else {
                try {
                    val cur = contentResolver.query(
                        TvContract.buildChannelsUriForInput(tuner.id),
                        arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_DISPLAY_NAME),
                        null, null, null
                    )
                    if (cur == null) {
                        sb.append("\nDaftar channel antena: tidak bisa dibaca.\n")
                    } else {
                        sb.append("\nChannel antena terbaca: " + cur.count + "\n")
                        var n = 0
                        while (cur.moveToNext() && n < 8) {
                            sb.append("  " + cur.getString(1) + "\n")
                            n++
                        }
                        cur.close()
                    }
                } catch (e: SecurityException) {
                    sb.append("\nDaftar channel antena DITOLAK sistem (SecurityException).\n")
                } catch (e: Exception) {
                    sb.append("\nGagal membaca channel: " + e.javaClass.simpleName + "\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Gagal: " + e.javaClass.simpleName)
        }
        AlertDialog.Builder(this)
            .setTitle("Hasil uji tuner")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun testAppLinks() {
        val res = try {
            AppLinks.diagnose(this)
        } catch (e: Exception) {
            Pair("Gagal memeriksa: " + e.javaClass.simpleName, emptyList<AppLinks.Trial>())
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Uji pintasan RCTI+ / Vidio")
            .setMessage(res.first + "\n\nPilih \"Coba satu per satu\" untuk mencari cara yang langsung membuka siaran.")
            .setNegativeButton("Tutup", null)
        if (res.second.isNotEmpty()) b.setPositiveButton("Coba satu per satu") { _, _ -> chooseTrial(res.second) }
        b.show()
    }

    private fun chooseTrial(trials: List<AppLinks.Trial>) {
        AlertDialog.Builder(this)
            .setTitle("Pilih lalu lihat hasilnya")
            .setItems(trials.map { it.label }.toTypedArray()) { _, w ->
                val t = trials[w]
                prefs.edit().putInt("trial_last", w).apply()
                val ok = if (t.url != null) AppLinks.openUrl(this, t.pkg, t.url) else AppLinks.openActivity(this, t.pkg, t.cls ?: "")
                if (!ok) {
                    notice.show("Tidak bisa dibuka", t.label, 4000L)
                } else if (t.url != null && t.target != null) {
                    pendingTrial = t
                }
            }
            .show()
    }

    private fun confirmTrial() {
        val t = pendingTrial ?: return
        pendingTrial = null
        val target = t.target ?: return
        val url = t.url ?: return
        AlertDialog.Builder(this)
            .setTitle("Hasil percobaan")
            .setMessage("Tadi " + target.provider + " langsung menampilkan siaran " + target.name + "?\n\n" + url)
            .setPositiveButton("Ya, pakai cara ini") { _, _ ->
                AppLinks.savePattern(this, target, url)
                notice.show(null, "Disimpan. Channel " + target.provider + " akan langsung membuka siarannya.", 4000L)
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshAntennaLabel()
        if (pendingTrial != null) ui.postDelayed({ confirmTrial() }, 600L)
    }

    override fun onDestroy() {
        server?.stop()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
