#!/usr/bin/env python3
"""
Ringkas file XMLTV (hasil pengambil iptv-org/epg) menjadi epg.json kecil untuk TV.

Keluaran: {"generated": <menit UTC>, "k": {"sctv": [[mulai, selesai, "judul"], ...]}}
Kunci = nama channel yang dinormalkan (sama dengan AppLinks.mergeKey di aplikasi).
Waktu dalam menit sejak 1970 (UTC). Hanya acara dari sekarang sampai 30 jam ke depan.
"""
import argparse
import datetime as dt
import glob
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET


def merge_key(name):
    k = re.sub(r"\(.*?\)|\[.*?\]", " ", name.lower())
    k = re.sub(r"\s+", " ", k).strip() or name.lower()
    k = "".join(ch for ch in k if ch.isalnum())
    if k.endswith("hd") and len(k) > 4:
        k = k[:-2]
    return k


def parse_time(s):
    # contoh: 20261116190000 +0700
    s = s.strip()
    base = dt.datetime.strptime(s[:14], "%Y%m%d%H%M%S")
    off = 0
    m = re.search(r"([+-])(\d{2})(\d{2})$", s)
    if m:
        off = (int(m.group(2)) * 60 + int(m.group(3))) * (1 if m.group(1) == "+" else -1)
    epoch = (base - dt.datetime(1970, 1, 1)).total_seconds() / 60 - off
    return int(epoch)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default="epg")
    ap.add_argument("--out", default="out")
    ap.add_argument("--hours", type=int, default=30)
    a = ap.parse_args()

    now = int(time.time() // 60)
    until = now + a.hours * 60
    result = {}
    files = sorted(glob.glob(os.path.join(a.src, "*.xml")))
    if not files:
        print("Tidak ada file XMLTV", file=sys.stderr)
    for fn in files:
        try:
            names = {}
            shows = {}
            for ev, el in ET.iterparse(fn, events=("end",)):
                if el.tag == "channel":
                    cid = el.get("id", "")
                    keys = set()
                    for d in el.findall("display-name"):
                        if d.text:
                            keys.add(merge_key(d.text))
                    if cid:
                        keys.add(merge_key(cid.split(".")[0]))
                    names[cid] = [k for k in keys if k]
                    el.clear()
                elif el.tag == "programme":
                    try:
                        st = parse_time(el.get("start", ""))
                        en = parse_time(el.get("stop", ""))
                    except Exception:
                        el.clear()
                        continue
                    if en > now and st < until:
                        t = el.find("title")
                        title = (t.text or "").strip() if t is not None else ""
                        if title:
                            shows.setdefault(el.get("channel", ""), []).append([st, en, title[:80]])
                    el.clear()
            added = 0
            for cid, lst in shows.items():
                lst.sort()
                for k in names.get(cid, [merge_key(cid.split(".")[0])]):
                    if k and k not in result:
                        result[k] = lst[:40]
                        added += 1
            print("%s: %d kunci channel" % (os.path.basename(fn), added), file=sys.stderr)
        except Exception as e:
            print("Gagal membaca %s: %s" % (fn, e), file=sys.stderr)

    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "epg.json"), "w", encoding="utf-8") as f:
        json.dump({"generated": now, "k": result}, f, ensure_ascii=False, separators=(",", ":"))
    print("epg.json: %d channel" % len(result), file=sys.stderr)


if __name__ == "__main__":
    main()
