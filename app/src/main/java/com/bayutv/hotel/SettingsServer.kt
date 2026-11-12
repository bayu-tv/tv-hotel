package com.bayutv.hotel

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

// Server kecil di TV. Hanya aktif selama layar Pengaturan terbuka dan dilindungi PIN.
class SettingsServer(private val ctx: Context, private val pin: String, private val onSaved: () -> Unit) {

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var fails = 0
    var port = 0
    private val prefs = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
    private val keys = arrayOf("family", "city", "lat", "lon", "tz", "wifi", "wifipass", "rules", "note", "country", "playlist", "bg", "theme")

    fun start(): Boolean {
        for (p in 8080..8090) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
            }
        }
        val s = server ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
        return true
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == 10) break
            if (c != 13) b.write(c)
            if (b.size() > 8192) return null
        }
        return b.toString("UTF-8")
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 10000
            val inp = s.getInputStream()
            val line = readLine(inp) ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 3000000) {
                respond(s, 413, "text/plain", "Terlalu besar".toByteArray())
                return
            }
            var body = ByteArray(0)
            if (len > 0) {
                body = ByteArray(len)
                DataInputStream(inp).readFully(body)
            }
            route(s, method, path, headers, body)
        } catch (e: Exception) {
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun respond(s: Socket, code: Int, type: String, data: ByteArray) {
        val msg = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val head = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " +
            data.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val o = s.getOutputStream()
        o.write(head.toByteArray())
        o.write(data)
        o.flush()
    }

    private fun route(s: Socket, m: String, path: String, h: Map<String, String>, body: ByteArray) {
        if (m == "GET" && path == "/") {
            respond(s, 200, "text/html", SettingsPage.HTML.toByteArray())
            return
        }
        if (!path.startsWith("/api/")) {
            respond(s, 404, "text/plain", "Tidak ada".toByteArray())
            return
        }
        if (fails >= 6) {
            respond(s, 429, "text/plain", "Terkunci".toByteArray())
            return
        }
        if (h["x-pin"] != pin) {
            fails++
            respond(s, 403, "text/plain", "PIN salah".toByteArray())
            return
        }
        fails = 0
        if (m == "GET" && path == "/api/get") {
            val o = JSONObject()
            for (k in keys) o.put(k, prefs.getString(k, "") ?: "")
            if ((o.getString("family")).isEmpty()) o.put("family", "Keluarga Anda")
            if ((o.getString("city")).isEmpty()) o.put("city", "Pontianak")
            if ((o.getString("country")).isEmpty()) o.put("country", "id")
            if ((o.getString("bg")).isEmpty()) o.put("bg", "none")
            if ((o.getString("theme")).isEmpty()) o.put("theme", "auto")
            respond(s, 200, "application/json", o.toString().toByteArray())
        } else if (m == "POST" && path == "/api/save") {
            save(JSONObject(String(body, Charsets.UTF_8)))
            respond(s, 200, "text/plain", "ok".toByteArray())
            onSaved()
        } else if (m == "POST" && path == "/api/bg") {
            if (body.size > 4 && body[0] == 0xFF.toByte() && body[1] == 0xD8.toByte()) {
                File(ctx.filesDir, "bg.jpg").writeBytes(body)
                prefs.edit().putString("bg", "photo").commit()
                respond(s, 200, "text/plain", "ok".toByteArray())
                onSaved()
            } else {
                respond(s, 403, "text/plain", "Bukan foto JPEG".toByteArray())
            }
        } else {
            respond(s, 404, "text/plain", "Tidak ada".toByteArray())
        }
    }

    private fun save(o: JSONObject) {
        val oldLoc = prefs.getString("lat", "") + prefs.getString("lon", "") + prefs.getString("tz", "")
        val oldSrc = prefs.getString("playlist", "") + prefs.getString("country", "")
        val e = prefs.edit()
        for (k in keys) {
            if (!o.has(k)) continue
            var v = o.getString(k).trim().take(500)
            if (k == "lat" || k == "lon") {
                val d = v.toDoubleOrNull() ?: continue
                if (k == "lat" && (d < -90.0 || d > 90.0)) continue
                if (k == "lon" && (d < -180.0 || d > 180.0)) continue
            }
            if (k == "tz" && !Regex("^[A-Za-z_/+-]{3,40}$").matches(v)) continue
            if (k == "country") v = v.lowercase().filter { it in 'a'..'z' }.take(2)
            if (k == "playlist" && v.isNotEmpty() && !(v.startsWith("http://") || v.startsWith("https://"))) v = ""
            if (k == "theme" && v != "auto" && v != "default" && v != "ramadan" && v != "newyear") v = "auto"
            if (k == "bg") {
                if (v != "none" && v != "plain" && v != "senja" && v != "photo") v = "none"
                if (v == "photo" && !File(ctx.filesDir, "bg.jpg").exists()) v = "none"
            }
            e.putString(k, v)
        }
        e.commit()
        val newLoc = prefs.getString("lat", "") + prefs.getString("lon", "") + prefs.getString("tz", "")
        val newSrc = prefs.getString("playlist", "") + prefs.getString("country", "")
        if (oldLoc != newLoc) prefs.edit().remove("wx").remove("aq").remove("pray").remove("pray_date").commit()
        if (oldSrc != newSrc) File(ctx.filesDir, "channels.m3u").delete()
    }
}
