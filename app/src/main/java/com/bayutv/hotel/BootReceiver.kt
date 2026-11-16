package com.bayutv.hotel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Membuka Bayu TV setelah TV menyala dari kondisi mati total.
 * Android 10+ hanya mengizinkan ini bila izin "Tampilkan di atas aplikasi lain" sudah diberikan.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val mode = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).getString("autostart", "") ?: ""
        val target = when (mode) {
            "Beranda" -> HomeActivity::class.java
            "TV Live" -> MainActivity::class.java
            else -> return
        }
        try {
            val i = Intent(ctx, target)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
        }
    }
}
