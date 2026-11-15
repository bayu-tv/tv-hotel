package com.bayutv.hotel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Pintasan ke aplikasi resmi (RCTI+ dan Vidio) untuk channel yang siaran internetnya dikunci pemiliknya.
 * Bila aplikasi menerima alamat siaran langsung, channel dibuka langsung; bila tidak, aplikasi dibuka di halaman utama.
 */
object AppLinks {

    class Target(val name: String, val provider: String, val path: String)

    class Check(val target: Target, val pkg: String?, val appLabel: String?, val deepUrl: String?)

    // Alamat resmi halaman siaran langsung per channel
    val TARGETS = listOf(
        Target("RCTI", "RCTI+", "rctiplus.com/tv/rcti"),
        Target("MNCTV", "RCTI+", "rctiplus.com/tv/mnctv"),
        Target("GTV", "RCTI+", "rctiplus.com/tv/gtv"),
        Target("iNews", "RCTI+", "rctiplus.com/tv/inews"),
        Target("SCTV", "Vidio", "vidio.com/live/204-sctv"),
        Target("Indosiar", "Vidio", "vidio.com/live/205-indosiar")
    )

    private fun word(provider: String): String = if (provider == "Vidio") "vidio" else "rcti"

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

    private fun variants(path: String): List<String> =
        listOf("https://www." + path, "https://" + path, "http://www." + path)

    /** Cek tanpa membuka aplikasi: apakah aplikasi menerima alamat siaran langsung channel ini? */
    fun check(ctx: Context, t: Target): Check {
        val pm = ctx.packageManager
        val pkgs = packagesFor(ctx, t.provider)
        for ((pkg, label) in pkgs) {
            for (u in variants(t.path)) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                i.setPackage(pkg)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                val ok = try {
                    pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
                } catch (e: Exception) {
                    false
                }
                if (ok) return Check(t, pkg, label, u)
            }
        }
        val first = pkgs.firstOrNull()
        return Check(t, first?.first, first?.second, null)
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

    /** 2 = dibuka langsung ke siaran, 1 = dibuka di halaman utama aplikasi, 0 = aplikasi belum terpasang. */
    fun open(a: Activity, t: Target): Int {
        val c = check(a, t)
        val pkg = c.pkg ?: return 0
        val deep = c.deepUrl
        if (deep != null) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(deep))
                i.setPackage(pkg)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                a.startActivity(i)
                return 2
            } catch (e: Exception) {
            }
        }
        val pm = a.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return 0
        return try {
            a.startActivity(launch)
            1
        } catch (e: Exception) {
            0
        }
    }

    /** Ringkasan untuk menu Uji pintasan. */
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        for (prov in listOf("RCTI+", "Vidio")) {
            val pk = packagesFor(ctx, prov)
            sb.append(prov).append(": ")
            sb.append(if (pk.isEmpty()) "belum terpasang" else pk.joinToString { it.second + " (" + it.first + ")" })
            sb.append("\n")
        }
        sb.append("\n")
        for (t in TARGETS) {
            val c = check(ctx, t)
            sb.append(t.name).append(" → ")
            sb.append(
                when {
                    c.pkg == null -> "aplikasi " + t.provider + " belum terpasang"
                    c.deepUrl != null -> "✓ langsung ke siaran"
                    else -> "hanya halaman utama " + (c.appLabel ?: t.provider)
                }
            )
            sb.append("\n")
        }
        return sb.toString().trim()
    }
}
