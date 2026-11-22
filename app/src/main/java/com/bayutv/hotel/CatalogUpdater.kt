package com.bayutv.hotel

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CatalogUpdater {

    // Ganti URL ini dengan link Raw GitHub / Firebase Hosting JSON milik Anda
    private const val CATALOG_URL = "https://raw.githubusercontent.com/username/repo/main/katalog.json"

    fun syncCatalogAsync(context: Context, onUpdated: (() -> Unit)? = null) {
        val prefs = context.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val conn = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 9000
                    useCaches = false
                }
                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(raw)
                    val remoteVer = root.optInt("version", 1)
                    val localVer = prefs.getInt("catalog_version", 0)

                    if (remoteVer >= localVer) {
                        val movies: JSONArray = root.optJSONArray("movies") ?: JSONArray()
                        val radios: JSONArray = root.optJSONArray("radios") ?: JSONArray()

                        prefs.edit()
                            .putInt("catalog_version", remoteVer)
                            .putString("dynamic_movies", movies.toString())
                            .putString("custom_radios", radios.toString())
                            .apply()

                        mainHandler.post { onUpdated?.invoke() }
                    }
                }
            } catch (_: Exception) {
                // Tetap gunakan cache lokal jika offline
            }
        }.start()
    }
}
