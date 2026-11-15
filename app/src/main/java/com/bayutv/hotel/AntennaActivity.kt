package com.bayutv.hotel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.provider.BaseColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// Uji hybrid: membaca channel tuner antena dan memutarnya di dalam aplikasi lewat TvView.
class AntennaActivity : AppCompatActivity() {

    private val brass = 0xFFB8975A.toInt()
    private val ivory = 0xFFEFE8DA.toInt()
    private val mute = 0xFFA79F90.toInt()
    private val perm = "android.permission.READ_TV_LISTINGS"

    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var list: ListView
    private lateinit var tvView: TvView
    private lateinit var adapter: ArrayAdapter<String>
    private val ids = ArrayList<Long>()
    private val labels = ArrayList<String>()
    private var inputId: String? = null

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
        root.setBackgroundColor(0xFF000000.toInt())

        tvView = TvView(this)
        root.addView(tvView, FrameLayout.LayoutParams(-1, -1))
        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String?) {
                status.text = "Gagal tersambung ke input antena (ditolak sistem)."
                showPanel(true)
            }

            override fun onDisconnected(inputId: String?) {
                status.text = "Terputus dari input antena."
            }

            override fun onVideoAvailable(inputId: String?) {
                status.text = ""
                showPanel(false)
            }

            override fun onVideoUnavailable(inputId: String?, reason: Int) {
                val why = when (reason) {
                    1 -> "sedang menyetel"
                    2 -> "sinyal antena lemah"
                    3 -> "buffering"
                    4 -> "hanya audio"
                    else -> "alasan tidak diketahui (" + reason + ")"
                }
                status.text = "Video belum tampil: " + why
            }
        })

        status = tv("", 20f, ivory, Typeface.SANS_SERIF)
        status.setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        status.setPadding(dp(32), dp(20), dp(32), dp(24))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xEE1A1816.toInt())
        panel.setPadding(dp(16), dp(16), dp(16), dp(16))
        panel.addView(tv("Antena di dalam aplikasi", 22f, brass, Typeface.SERIF))
        info = tv("Memeriksa...", 14f, mute, Typeface.SANS_SERIF)
        info.setPadding(0, dp(8), 0, dp(8))
        panel.addView(info)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list = ListView(this)
        list.divider = null
        list.adapter = adapter
        list.isFocusable = true
        list.isFocusableInTouchMode = true
        list.setOnItemClickListener { _, _, pos, _ -> tune(pos) }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(dp(380), -1, Gravity.START))

        setContentView(root)
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            load()
        } else {
            info.text = "Meminta izin baca daftar TV... Pilih Izinkan pada jendela yang muncul."
            requestPermissions(arrayOf(perm), 11)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load()
    }

    private fun load() {
        val sb = StringBuilder()
        val granted = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        sb.append("Izin baca daftar TV: " + (if (granted) "DIBERIKAN" else "DITOLAK") + "\n")
        try {
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            val tuners = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_TUNER }
            sb.append("Input antena (TUNER): " + tuners.size + "\n")
            if (tuners.isEmpty()) {
                info.text = sb.toString()
                return
            }
            val inp = tuners[0]
            inputId = inp.id
            sb.append("Dipakai: " + inp.loadLabel(this) + "\n")
            try {
                val cur = contentResolver.query(
                    TvContract.buildChannelsUriForInput(inp.id),
                    arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME),
                    null, null, null
                )
                ids.clear()
                labels.clear()
                if (cur != null) {
                    while (cur.moveToNext()) {
                        ids.add(cur.getLong(0))
                        labels.add(" " + (cur.getString(1) ?: "") + "   " + (cur.getString(2) ?: "?"))
                    }
                    cur.close()
                }
                sb.append("Channel antena terbaca: " + ids.size + "\n")
                if (ids.isEmpty()) sb.append("Daftar kosong atau tidak diizinkan dibaca.\n") else sb.append("Pilih channel lalu tekan OK.\n")
                adapter.notifyDataSetChanged()
                if (ids.isNotEmpty()) list.requestFocus()
            } catch (e: SecurityException) {
                sb.append("Daftar channel DITOLAK sistem (SecurityException).\n")
            } catch (e: Exception) {
                sb.append("Gagal membaca channel: " + e.javaClass.simpleName + "\n")
            }
        } catch (e: Exception) {
            sb.append("Gagal: " + e.javaClass.simpleName + "\n")
        }
        info.text = sb.toString()
    }

    private fun tune(pos: Int) {
        val id = inputId ?: return
        if (pos >= ids.size) return
        status.text = "Menyetel " + labels[pos].trim() + "..."
        try {
            tvView.tune(id, TvContract.buildChannelUri(ids[pos]))
        } catch (e: Exception) {
            status.text = "Gagal menyetel: " + e.javaClass.simpleName
        }
    }

    private fun showPanel(show: Boolean) {
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) list.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (panel.visibility != View.VISIBLE) {
                showPanel(true)
            } else {
                finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try {
            tvView.reset()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
