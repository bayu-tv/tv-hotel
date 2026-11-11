package com.bayutv.hotel

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var server: SettingsServer? = null
    private lateinit var status: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF121110.toInt())
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(64), dp(32), dp(64), dp(32))
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        val ip = localIp()
        val pin = (SecureRandom().nextInt(9000) + 1000).toString()
        val srv = SettingsServer(this, pin) {
            ui.post { status.text = "Tersimpan dari HP pukul " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) }
        }
        val started = srv.start()
        server = srv

        val qrBox = FrameLayout(this)
        val g = GradientDrawable()
        g.cornerRadius = dp(18).toFloat()
        g.setColor(0xFFFFFFFF.toInt())
        qrBox.background = g
        qrBox.setPadding(dp(14), dp(14), dp(14), dp(14))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        qrBox.addView(img, FrameLayout.LayoutParams(-1, -1))
        row.addView(qrBox, LinearLayout.LayoutParams(dp(250), dp(250)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(48), 0, 0, 0)
        col.addView(tv("Pengaturan lewat HP", 32f, brass, Typeface.SERIF))
        val steps = tv(
            "1.  Sambungkan HP ke Wi-Fi yang sama dengan TV.\n" +
                "2.  Pindai kode QR di samping, atau buka alamat di bawah.\n" +
                "3.  Masukkan PIN, lalu isi dan simpan.",
            19f, ivory, Typeface.SERIF
        )
        steps.setLineSpacing(0f, 1.3f)
        steps.setPadding(0, dp(14), 0, dp(14))
        col.addView(steps)

        if (ip != null && started) {
            val url = "http://" + ip + ":" + srv.port
            img.setImageBitmap(qr(url))
            col.addView(tv(url, 26f, brassLight, Typeface.SANS_SERIF))
            val pinView = tv("PIN  " + pin, 30f, ivory, Typeface.SERIF)
            pinView.setPadding(0, dp(10), 0, 0)
            col.addView(pinView)
        } else {
            col.addView(tv("Belum bisa dibuka.", 24f, 0xFFC9706A.toInt(), Typeface.SANS_SERIF))
        }
        status = tv("Menunggu HP terhubung...", 16f, mute, Typeface.SANS_SERIF)
        status.setPadding(0, dp(18), 0, 0)
        col.addView(status)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        notice = Notice(this, root)
        setContentView(root)
        if (ip == null) notice.show("TV belum terhubung", "Sambungkan TV ke Wi-Fi atau jaringan rumah, lalu buka lagi menu Pengaturan.")
        else if (!started) notice.show("Server gagal dibuka", "Tutup lalu buka lagi menu Pengaturan.")
    }

    override fun onDestroy() {
        server?.stop()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
