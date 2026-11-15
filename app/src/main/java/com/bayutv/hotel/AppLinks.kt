package com.bayutv.hotel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Pintasan ke aplikasi resmi (RCTI+ dan Vidio) untuk channel yang siaran internetnya dikunci pemiliknya.
 * Banyak format alamat dicoba; yang diterima aplikasi dipakai untuk membuka siaran langsung.
 * Bila tidak ada yang cocok, aplikasi dibuka di halaman utama.
 */
object AppLinks {

    // slug = nama di alamat web, id = nomor siaran di layanan tersebut
    class Target(val name: String, val provider: String, val slug: String, val id: String)

    class Check(val target: Target, val pkg: String?, val appLabel: String?, val deepUrl: String?)

    val TARGETS = listOf(
        Target("RCTI", "RCTI+", "rcti", "1"),
        Target("MNCTV", "RCTI+", "mnctv", "2"),
        Target("GTV", "RCTI+", "gtv", "3"),
        Target("iNews", "RCTI+", "inews", "4"),
        Target("SCTV", "Vidio", "sctv", "204"),
        Target("Indosiar", "Vidio", "indosiar", "205")
    )

    private const val PREF_KEY = "applink_"

    private fun word(provider: String): String = if (provider == "Vidio") "vidio" else "rcti"

    /** Format alamat yang dicoba, dari yang paling spesifik. */
    fun variants(t: Target): List<String> {
        val s = t.slug
        val i = t.id
        return if (t.provider == "Vidio") listOf(
            "https://www.vidio.com/live/$i-$s",
            "https://vidio.com/live/$i-$s",
            "https://m.vidio.com/live/$i-$s",
            "https://www.vidio.com/live/$i",
            "vidio://live/$i-$s",
            "vidio://live/$i",
            "vidio://livestreaming/$i",
            "vidio://watch/$i",
            "vidiotv://live/$i",
            "vidio://www.vidio.com/live/$i-$s"
        ) else listOf(
            "https://www.rctiplus.com/tv/$s",
            "https://rctiplus.com/tv/$s",
            "https://m.rctiplus.com/tv/$s",
            "https://www.rctiplus.com/live-event/$i/$s",
            "rctiplus://tv/$s",
            "rctiplus://live-event/$i/$s",
            "rctiplus://live/$i",
            "rctiplus://livetv/$i",
            "rctiplus://www.rctiplus.com/tv/$s",
            "rctiplusatv://tv/$s"
        )
    }

    /** Aplikasi terpasang untuk penyedia ini; versi Android TV didahulukan. */
    fun packagesFor(ctx: Context, provider: String): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val w = word(provider)
        val out = LinkedHashMap<String, String>()
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN)
            q.addCategory(cat)
            try {
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm).toString()
                    if ((pkg.lowercase().contains(w) || label.lowercase().contains(w)) && !out.containsKey(pkg)) out[pkg] = label
                }
            } catch (e: Exception) {
            }
        }
        return out.entries.map { Pair(it.key, it.value) }
    }

    /** Nama layar (activity) yang menerima alamat ini, atau null. */
    fun resolves(ctx: Context, pkg: String, url: String): String? {
        val pm = ctx.packageManager
        for (browsable in listOf(true, false)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.setPackage(pkg)
                if (browsable) i.addCategory(Intent.CATEGORY_BROWSABLE)
                val ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                if (ri != null) return ri.activityInfo.name
            } catch (e: Exception) {
            }
        }
        return null
    }

    /** Cek tanpa membuka aplikasi. Alamat yang pernah dipilih lewat Uji pintasan didahulukan. */
    fun check(ctx: Context, t: Target): Check {
        val pkgs = packagesFor(ctx, t.provider)
        val saved = ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).getInt(PREF_KEY + t.provider, -1)
        val vs = variants(t)
        for ((pkg, label) in pkgs) {
            if (saved in vs.indices) {
                val u = vs[saved]
                if (resolves(ctx, pkg, u) != null) return Check(t, pkg, label, u)
            }
            for (u in variants(t)) {
                if (resolves(ctx, pkg, u) != null) return Check(t, pkg, label, u)
            }
        }
        val first = pkgs.firstOrNull()
        return Check(t, first?.first, first?.second, null)
    }

    // Format yang dipilih disimpan sebagai nomor urut, sehingga berlaku untuk semua channel penyedia itu
    fun savePattern(ctx: Context, t: Target, url: String) {
        val idx = variants(t).indexOf(url)
        if (idx >= 0) ctx.getSharedPreferences("bayutv", Context.MODE_PRIVATE).edit().putInt(PREF_KEY + t.provider, idx).apply()
    }

    fun targetFor(name: String): Target? {
        val k = mergeKey(name)
        return TARGETS.firstOrNull { mergeKey(it.name) == k }
    }

    /** Kunci pencocokan antar sumber: "RCTI HD", "RCTI (720p)" dan "rcti" dianggap sama. */
    fun mergeKey(name: String): String {
        var k = Playlist.key(name).filter { it.isLetterOrDigit() }
        if (k.endsWith("hd") && k.length > 4) k = k.substring(0, k.length - 2)
        return k
    }

    fun openUrl(a: Activity, pkg: String, url: String): Boolean {
        for (browsable in listOf(true, false)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.setPackage(pkg)
                if (browsable) i.addCategory(Intent.CATEGORY_BROWSABLE)
                a.startActivity(i)
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    /** 2 = dibuka lewat alamat siaran, 1 = dibuka di halaman utama aplikasi, 0 = aplikasi belum terpasang. */
    fun open(a: Activity, t: Target): Int {
        val c = check(a, t)
        val pkg = c.pkg ?: return 0
        val deep = c.deepUrl
        if (deep != null && openUrl(a, pkg, deep)) return 2
        val pm = a.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return 0
        return try {
            a.startActivity(launch)
            1
        } catch (e: Exception) {
            0
        }
    }

    /** Layar aplikasi yang boleh dibuka dari luar (untuk percobaan). */
    fun exportedActivities(ctx: Context, pkg: String): List<String> {
        return try {
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            (info.activities ?: emptyArray()).filter { it.exported }.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun openActivity(a: Activity, pkg: String, cls: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_MAIN)
            i.component = ComponentName(pkg, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Satu percobaan yang bisa dipilih di menu Uji pintasan. */
    class Trial(val label: String, val pkg: String, val url: String?, val cls: String?, val target: Target?)

    /** Ringkasan + daftar percobaan untuk menu Uji pintasan. */
    fun diagnose(ctx: Context): Pair<String, List<Trial>> {
        val sb = StringBuilder()
        val trials = ArrayList<Trial>()
        for (prov in listOf("RCTI+", "Vidio")) {
            val pk = packagesFor(ctx, prov)
            sb.append(prov).append(": ")
            if (pk.isEmpty()) {
                sb.append("belum terpasang\n")
                continue
            }
            sb.append(pk.joinToString { it.second + " (" + it.first + ")" }).append("\n")
            val t = TARGETS.first { it.provider == prov }
            for ((pkg, _) in pk) {
                var hits = 0
                for (u in variants(t)) {
                    val act = resolves(ctx, pkg, u)
                    if (act != null) {
                        hits++
                        trials.add(Trial("Alamat: " + u, pkg, u, null, t))
                    }
                }
                sb.append("  Format alamat diterima: ").append(hits).append(" dari ").append(variants(t).size).append("\n")
                val acts = exportedActivities(ctx, pkg)
                sb.append("  Layar terbuka: ").append(acts.size).append("\n")
                for (cls in acts) trials.add(Trial("Layar: " + cls.substringAfterLast('.'), pkg, null, cls, null))
            }
        }
        sb.append("\n")
        for (t in TARGETS) {
            val c = check(ctx, t)
            sb.append(t.name).append(" → ")
            sb.append(
                when {
                    c.pkg == null -> "aplikasi " + t.provider + " belum terpasang"
                    c.deepUrl != null -> "✓ " + c.deepUrl
                    else -> "hanya halaman utama"
                }
            )
            sb.append("\n")
        }
        return Pair(sb.toString().trim(), trials)
    }
}
