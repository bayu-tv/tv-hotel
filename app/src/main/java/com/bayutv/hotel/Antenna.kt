package com.bayutv.hotel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.BaseColumns

/**
 * Jembatan ke tuner antena (DVB-T2) bawaan TV lewat Android TV Input Framework.
 *
 * Channel antena dibaca dari database TvContract. Sebagian merek TV (termasuk beberapa Xiaomi)
 * tidak mengizinkan aplikasi pihak ketiga membaca daftar ini; saat itu terjadi kita tetap
 * menyediakan pintasan untuk membuka aplikasi TV antena bawaan.
 */
object Antenna {
    const val PERM = "android.permission.READ_TV_LISTINGS"
    const val OPEN_KEY = "ant:open"

    class Result(val channels: List<Channel>, val info: String)

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun load(ctx: Context): Result {
        val sb = StringBuilder()
        val out = ArrayList<Channel>()
        try {
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return Result(out, "Perangkat ini bukan Android TV.")
            val tuners = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_TUNER }
            if (tuners.isEmpty()) return Result(out, "Tidak ada input tuner antena.")
            for (inp in tuners) {
                try {
                    val cur = ctx.contentResolver.query(
                        TvContract.buildChannelsUriForInput(inp.id),
                        arrayOf(
                            BaseColumns._ID,
                            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                            TvContract.Channels.COLUMN_DISPLAY_NAME,
                            TvContract.Channels.COLUMN_BROWSABLE
                        ),
                        null, null, null
                    ) ?: continue
                    cur.use {
                        while (it.moveToNext()) {
                            val browsable = it.isNull(3) || it.getInt(3) != 0
                            if (!browsable) continue
                            val num = it.getString(1) ?: ""
                            val nm = it.getString(2) ?: ("Channel " + num)
                            out.add(
                                Channel(
                                    nm, "tv://" + it.getLong(0), null, null, null, null,
                                    "Antena", "TV Digital", "ok", inp.id, it.getLong(0), num
                                )
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    sb.append("Daftar channel antena ditolak sistem TV. ")
                } catch (e: Exception) {
                    sb.append("Gagal membaca channel antena (" + e.javaClass.simpleName + "). ")
                }
            }
        } catch (e: Exception) {
            sb.append("Tuner tidak bisa diakses. ")
        }
        out.sortWith(compareBy({ numSort(it.number) }, { it.name.lowercase() }))
        return Result(out, sb.toString().trim())
    }

    private fun numSort(n: String): Double {
        return n.replace('-', '.').toDoubleOrNull() ?: 99999.0
    }

    /** Pintasan pengganti bila channel antena tidak bisa dibaca langsung. */
    fun shortcut(): Channel = Channel(
        "Buka aplikasi TV Antena", OPEN_KEY, null, null, null, null,
        "Antena", "TV Digital", "ok", null, -1L, ""
    )

    /** Buka aplikasi antena pilihan (disimpan dari menu TV Antena di Beranda). */
    fun openSaved(a: Activity): Boolean {
        val prefs = a.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val saved = prefs.getString("antenna", null) ?: "system"
        if (saved == "system") {
            return try {
                val i = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                a.startActivity(i)
                true
            } catch (e: Exception) {
                false
            }
        }
        return try {
            val cn = ComponentName.unflattenFromString(saved) ?: return false
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory("android.intent.category.LEANBACK_LAUNCHER")
            i.component = cn
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
