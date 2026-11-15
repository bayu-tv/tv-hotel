package com.bayutv.hotel

import android.app.Application
import java.io.File

// Mencatat penyebab aplikasi tertutup (sekali untuk seluruh aplikasi), ditampilkan saat TV Live dibuka lagi.
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (x: Exception) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
