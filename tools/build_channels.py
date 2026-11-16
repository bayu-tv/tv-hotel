#!/usr/bin/env python3
"""
Penyusun daftar channel Bayu TV.

Dijalankan oleh GitHub Actions (lihat .github/workflows/channels.yml) beberapa kali sehari:
  1. Mengambil daftar alamat siaran dari extra_playlists (dhanytv, tvdigital) dan iptv-org.
  2. Menggabungkan alamat cadangan untuk channel yang sama.
  3. Memeriksa setiap alamat (apakah benar mengirim video).
  4. Mengelompokkan per wilayah dan kategori (Indonesia / Internasional).
  5. Menulis channels.m3u yang bersih untuk diunduh TV.

Hanya memakai pustaka bawaan Python supaya tidak perlu pip install.
"""
import argparse
import concurrent.futures as cf
import csv
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

RAW = "https://raw.githubusercontent.com/iptv-org"
UA = "Mozilla/5.0 (Linux; Android 11; MiTV-MOOQ0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36"

# Urutan teratas untuk kategori Nasional (sisanya alfabetis)
NATIONAL_ORDER = ["tvri", "rcti", "sctv", "indosiar", "mnctv", "gtv", "antv", "trans tv", "trans7", "trans 7", "tvone", "tv one",
                  "metro tv", "kompas tv", "inews", "net", "rtv", "cnn indonesia", "cnbc indonesia", "beritasatu", "moji",
                  "mdtv", "nusantara tv", "garuda tv", "magna", "btv", "jak tv", "daai"]


def national_rank(name):
    low = key_of(name)
    for i, k in enumerate(NATIONAL_ORDER):
        if low == k or low.startswith(k + " "):
            return i
    return len(NATIONAL_ORDER)


def split_pipe(url):
    """Format komunitas: alamat|Referer=...&User-Agent=...&Origin=..."""
    if "|" not in url:
        return url, None, None, None
    base, _, rest = url.partition("|")
    ref = ua = org = None
    for part in rest.split("&"):
        k, _, v = part.partition("=")
        k = k.strip().lower()
        v = urllib.parse.unquote(v.strip())
        if k in ("referer", "referrer"):
            ref = v
        elif k in ("user-agent", "useragent"):
            ua = v
        elif k == "origin":
            org = v
    return base.strip(), ref, ua, org


def origin_of(url):
    try:
        u = urllib.parse.urlsplit(url)
        return u.scheme + "://" + u.netloc if u.scheme and u.netloc else None
    except Exception:
        return None


# Kategori iptv-org & bahasa Indonesia -> nama kategori di aplikasi (urutan = urutan tampil)
CATEGORY_MAP = [
    ("news", "Berita"),
    ("berita", "Berita"),
    ("general", "Nasional"),
    ("nasional", "Nasional"),
    ("lokal", "Nasional"),
    ("indonesia", "Nasional"),
    ("sports", "Olahraga"),
    ("sport", "Olahraga"),
    ("olahraga", "Olahraga"),
    ("kids", "Anak & Kartun"),
    ("anak", "Anak & Kartun"),
    ("animation", "Anak & Kartun"),
    ("kartun", "Anak & Kartun"),
    ("family", "Anak & Kartun"),
    ("movies", "Film & Serial"),
    ("movie", "Film & Serial"),
    ("film", "Film & Serial"),
    ("series", "Film & Serial"),
    ("drama", "Film & Serial"),
    ("classic", "Film & Serial"),
    ("comedy", "Hiburan"),
    ("entertainment", "Hiburan"),
    ("hiburan", "Hiburan"),
    ("lifestyle", "Hiburan"),
    ("cooking", "Hiburan"),
    ("travel", "Dokumenter"),
    ("outdoor", "Dokumenter"),
    ("documentary", "Dokumenter"),
    ("dokumenter", "Dokumenter"),
    ("science", "Dokumenter"),
    ("education", "Edukasi"),
    ("edukasi", "Edukasi"),
    ("culture", "Edukasi"),
    ("music", "Musik"),
    ("musik", "Musik"),
    ("religious", "Religi"),
    ("religi", "Religi"),
    ("islam", "Religi"),
    ("legislative", "Pemerintahan"),
    ("business", "Berita"),
    ("weather", "Berita"),
    ("shop", None),
    ("auto", "Hiburan"),
    ("relax", "Musik"),
]
CAT = {k: v for k, v in CATEGORY_MAP}

# Stasiun jaringan nasional Indonesia (sisanya dianggap TV daerah)
NATIONAL = re.compile(r"\b(tvri( nasional| world| sport)?|rcti|sctv|indosiar|antv|trans ?tv|trans ?7|gtv|mnctv|inews|"
                      r"metro ?tv|tv ?one|kompas ?tv|net\.? ?tv|net\b|rtv|rajawali|moji|mdtv|btv|nusantara ?tv|garuda ?tv|"
                      r"magna|jak ?tv|daai|cnn indonesia|cnbc indonesia|beritasatu|o channel|elshinta|idtv|sea today)\b", re.I)
RELIGI = re.compile(r"\b(rodja|surau|salam tv|tv ?mu|tv9|ajwa|al-?bahjah|ahsan|madani|aswaja|insan|wesal|dai tv|tv al|"
                    r"nabawi|risalah|ummat|quran|muslim|muhammadiyah|nu channel)\b", re.I)
ID_HINTS = re.compile(r"\b(indonesia|nasional|daerah|lokal|jakarta|jawa|sumatra|kalimantan|sulawesi|bali|papua|"
                      r"rcti|sctv|indosiar|mnctv|gtv|trans|tvri|kompas|metro|antv|net|rtv|moji|vidio|vision)\b", re.I)


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def http_get(url, timeout=30, headers=None):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def load_csv(name):
    data = http_get(RAW + "/database/master/data/" + name).decode("utf-8")
    return list(csv.DictReader(io.StringIO(data)))


def list_stream_files(token):
    """Daftar file di iptv-org/iptv/streams (butuh API GitHub). Gagal = None."""
    url = "https://api.github.com/repos/iptv-org/iptv/contents/streams"
    h = {"User-Agent": "BayuTV", "Accept": "application/vnd.github+json"}
    if token:
        h["Authorization"] = "Bearer " + token
    try:
        items = json.loads(http_get(url, headers=h))
        return [i["name"] for i in items if i.get("name", "").endswith(".m3u")]
    except Exception as e:
        log("Tidak bisa membaca daftar file streams:", e)
        return None


def files_for(country, all_files):
    if all_files is None:
        return [country + ".m3u"]
    pat = re.compile("^" + re.escape(country) + r"(_[a-z0-9_]+)?\.m3u$")
    return sorted(f for f in all_files if pat.match(f))


ATTR = re.compile(r'([a-zA-Z0-9-]+)="([^"]*)"')


def parse_m3u(text, origin):
    out = []
    cur = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            attrs = dict(ATTR.findall(line.split(",", 1)[0]))
            name = line.rsplit(",", 1)[-1].strip() if "," in line else "?"
            cur = {"name": name, "tvg_id": attrs.get("tvg-id", ""), "logo": attrs.get("tvg-logo", ""),
                   "group": attrs.get("group-title", ""), "ref": None, "ua": None, "org": None, "origin": origin}
        elif line.startswith("#EXTVLCOPT:http-referrer="):
            if cur is not None:
                cur["ref"] = line.split("=", 1)[1]
        elif line.startswith("#EXTVLCOPT:http-user-agent="):
            if cur is not None:
                cur["ua"] = line.split("=", 1)[1]
        elif line.startswith("#EXTVLCOPT:http-origin="):
            if cur is not None:
                cur["org"] = line.split("=", 1)[1]
        elif not line.startswith("#") and cur is not None:
            url, ref, ua, org = split_pipe(line)
            cur["url"] = url
            cur["ref"] = ref or cur["ref"]
            cur["ua"] = ua or cur["ua"]
            cur["org"] = org or cur["org"]
            out.append(cur)
            cur = None
    return out


def clean_name(n):
    n = re.sub(r"\s*\[(Not 24/7|Geo-blocked)\]", "", n, flags=re.I)
    return n.strip()


def key_of(n):
    k = re.sub(r"\(.*?\)|\[.*?\]", " ", n.lower())
    k = re.sub(r"\s+", " ", k).strip()
    return k or n.lower()


def quality(n):
    m = re.search(r"\((\d{3,4})p\)", n)
    return int(m.group(1)) if m else 0


# ---------- Pemeriksa alamat ----------

def fetch(url, ref, ua, limit, timeout, org=None):
    h = {"User-Agent": ua or UA}
    if ref:
        h["Referer"] = ref
    o = org or (origin_of(ref) if ref else None)
    if o:
        h["Origin"] = o
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.geturl(), r.read(limit)


def check(stream, timeout):
    """Hasil: 'ok', 'geo' (ditolak, mungkin hanya untuk IP Indonesia) atau 'dead'."""
    url, ref, ua, org = stream["url"], stream["ref"], stream["ua"], stream.get("org")
    if not url.startswith("http"):
        return "dead"
    try:
        final, body = fetch(url, ref, ua, 65536, timeout, org)
        text = body.decode("latin-1", "replace")
        for _ in range(3):
            if not text.lstrip().startswith("#EXTM3U"):
                if text.lstrip().startswith("<") and "MPD" in text[:600]:
                    return "ok"
                return "ok" if len(body) >= 188 else "dead"
            lines = [l.strip() for l in text.splitlines()]
            nxt = next((l for l in lines if l and not l.startswith("#")), None)
            if nxt is None:
                return "dead"
            master = "#EXT-X-STREAM-INF" in text
            full = urllib.parse.urljoin(final, nxt)
            final, body = fetch(full, ref, ua, 65536 if master else 4096, timeout, org)
            if not master:
                return "ok" if len(body) > 0 else "dead"
            text = body.decode("latin-1", "replace")
        return "dead"
    except urllib.error.HTTPError as e:
        return "geo" if e.code in (401, 403, 451) else "dead"
    except Exception:
        return "dead"


# ---------- Utama ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=os.path.join(os.path.dirname(__file__), "sources.json"))
    ap.add_argument("--out", default="out")
    ap.add_argument("--no-check", action="store_true", help="lewati pemeriksaan (untuk uji cepat)")
    args = ap.parse_args()

    cfg = json.load(open(args.config, encoding="utf-8"))
    os.makedirs(args.out, exist_ok=True)
    token = os.environ.get("GITHUB_TOKEN")

    log("Membaca database iptv-org...")
    chans = {r["id"]: r for r in load_csv("channels.csv")}
    logos = {}
    for r in load_csv("logos.csv"):
        if r.get("in_use", "TRUE").upper() != "TRUE":
            continue
        cid = r["channel"]
        if cid not in logos or not r.get("feed"):
            logos[cid] = r["url"]

    all_files = list_stream_files(token)
    local = [c.lower() for c in cfg.get("local_countries", ["id"])]
    intl = [c.lower() for c in cfg.get("international_countries", [])]
    intl_cats = set(cfg.get("international_categories", []))
    blocked = [w.lower() for w in cfg.get("exclude_keywords", [])]

    streams = []

    custom = os.path.join(os.path.dirname(args.config), "custom.m3u")
    if os.path.exists(custom):
        items = parse_m3u(open(custom, encoding="utf-8").read(), "Tambahan")
        log("  custom.m3u: %d" % len(items))
        streams.extend(items)

    for url in cfg.get("extra_playlists", []):
        try:
            text = http_get(url).decode("utf-8", "replace")
            items = parse_m3u(text, "Tambahan")
            log("  tambahan %s: %d" % (url, len(items)))
            streams.extend(items)
        except Exception as e:
            log("  playlist tambahan gagal:", url, e)

    for region, countries in (("Indonesia", local), ("Internasional", intl)):
        for cc in countries:
            for fn in files_for(cc, all_files):
                try:
                    text = http_get(RAW + "/iptv/master/streams/" + fn).decode("utf-8", "replace")
                except Exception as e:
                    log("  lewati", fn, e)
                    continue
                items = parse_m3u(text, region)
                log("  %-22s %4d alamat" % (fn, len(items)))
                streams.extend(items)

    # Lengkapi metadata dari database
    entries = []
    seen_urls = set()
    for s in streams:
        if s["url"] in seen_urls:
            continue
        seen_urls.add(s["url"])
        cid = s["tvg_id"].split("@")[0]
        meta = chans.get(cid)
        if meta is not None:
            if meta.get("is_nsfw", "").upper() == "TRUE" or meta.get("closed"):
                continue
        name = clean_name(s["name"])
        if any(b in name.lower() for b in blocked):
            continue
        cats = (meta.get("categories", "") if meta else "").split(";")
        cats = [c for c in cats if c]
        if s["group"] and not cats:
            cats = [g.strip().lower() for g in s["group"].split(";")]
        if "shop" in cats and len(cats) == 1:
            continue

        is_extra = (s["origin"] == "Tambahan")
        region = s["origin"]
        if is_extra:
            country = (meta.get("country", "") if meta else "").upper()
            combined_hint = name + " " + s["group"] + " " + s["tvg_id"]
            if country == "ID" or s["tvg_id"].lower().endswith(".id") or ID_HINTS.search(combined_hint):
                region = "Indonesia"
            else:
                region = "Internasional"

        if region == "Internasional" and not is_extra and intl_cats and not (set(cats) & intl_cats):
            continue

        cat = None
        for c in cats:
            if CAT.get(c):
                cat = CAT[c]
                break
        if region == "Indonesia":
            low = clean_name(name).lower()
            if "tvri" in low and not any(x in low for x in ("nasional", "world", "sport")):
                cat = "Daerah"
            elif RELIGI.search(low):
                cat = "Religi"
            elif cat in (None, "Nasional", "Hiburan", "Lainnya") and NATIONAL.search(low):
                cat = "Nasional"
            elif cat in (None, "Nasional", "Lainnya"):
                cat = "Daerah"
        if cat is None:
            cat = "Lainnya"

        s["name"] = name
        s["cat"] = cat
        s["region"] = region
        s["is_extra"] = is_extra
        s["logo"] = s["logo"] or logos.get(cid, "")
        s["key"] = key_of(name)
        s["q"] = quality(s["name"])
        entries.append(s)
    log("Alamat unik: %d" % len(entries))

    # Periksa
    if args.no_check:
        for e in entries:
            e["status"] = "ok"
    else:
        t0 = time.time()
        workers = int(cfg.get("workers", 48))
        timeout = float(cfg.get("timeout", 10))
        with cf.ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(check, e, timeout): e for e in entries}
            done = 0
            for f in cf.as_completed(futs):
                futs[f]["status"] = f.result()
                done += 1
                if done % 200 == 0:
                    log("  diperiksa %d/%d" % (done, len(entries)))
        log("Pemeriksaan selesai dalam %ds" % (time.time() - t0))

    keep_geo = cfg.get("keep_geo_blocked", True)
    alive = [e for e in entries if e["status"] == "ok" or (keep_geo and e["status"] == "geo")]

    # Kelompokkan per channel, urutkan sumber terbaik dulu (extra_playlists/dhanytv diprioritaskan)
    groups = {}
    for e in alive:
        groups.setdefault((e["region"], e["key"]), []).append(e)
    rank = {"ok": 0, "geo": 1}
    for g in groups.values():
        g.sort(key=lambda e: (0 if e.get("is_extra") else 1, rank.get(e["status"], 2), -e["q"]))

    # Batasi jumlah internasional per kategori agar ringan di TV RAM 2 GB
    limit = int(cfg.get("max_international_per_category", 120))
    by_cat = {}
    for (region, k), g in groups.items():
        by_cat.setdefault((region, g[0]["cat"]), []).append(g)
    order = [v for _, v in CATEGORY_MAP if v] + ["Daerah", "Lainnya"]
    seen_order = []
    for o in ["Nasional", "Berita", "Daerah"] + order:
        if o not in seen_order:
            seen_order.append(o)

    lines = ["#EXTM3U"]
    stats = []
    for region in ("Indonesia", "Internasional"):
        cats = sorted({c for (r, c) in by_cat if r == region}, key=lambda c: seen_order.index(c) if c in seen_order else 99)
        for c in cats:
            gs = by_cat[(region, c)]
            if region == "Indonesia":
                gs.sort(key=lambda g: (national_rank(g[0]["name"]), rank.get(g[0]["status"], 2), g[0]["name"].lower()))
            else:
                gs.sort(key=lambda g: (rank.get(g[0]["status"], 2), g[0]["name"].lower()))
            if region == "Internasional":
                gs = gs[:limit]
            stats.append((region, c, len(gs), sum(1 for g in gs if g[0]["status"] == "ok")))
            for g in gs:
                for e in g[:5]:
                    attrs = 'tvg-id="%s" tvg-logo="%s" group-title="%s/%s" bayu-status="%s"' % (
                        e["tvg_id"], e["logo"], region, c, e["status"])
                    lines.append("#EXTINF:-1 %s,%s" % (attrs, g[0]["name"]))
                    if e["ref"]:
                        lines.append("#EXTVLCOPT:http-referrer=" + e["ref"])
                    if e["ua"]:
                        lines.append("#EXTVLCOPT:http-user-agent=" + e["ua"])
                    if e.get("org"):
                        lines.append("#EXTVLCOPT:http-origin=" + e["org"])
                    lines.append(e["url"])

    with open(os.path.join(args.out, "channels.m3u"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    rep = ["# Laporan daftar channel Bayu TV", "",
           "Dibuat: " + time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), "",
           "Alamat diperiksa: %d, aktif: %d, kemungkinan khusus IP Indonesia: %d, mati: %d" % (
               len(entries), sum(1 for e in entries if e["status"] == "ok"),
               sum(1 for e in entries if e["status"] == "geo"),
               sum(1 for e in entries if e["status"] == "dead")), "",
           "| Wilayah | Kategori | Channel | Terverifikasi |", "|---|---|---|---|"]
    for r, c, n, ok in stats:
        rep.append("| %s | %s | %d | %d |" % (r, c, n, ok))
    with open(os.path.join(args.out, "README.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(rep) + "\n")
    log("\n".join(rep))


if __name__ == "__main__":
    main()
