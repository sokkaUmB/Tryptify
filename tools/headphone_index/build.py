#!/usr/bin/env python3
"""Builds site/public/data/headphones.json: every headphone measurement the
website's AutoEQ try-out can load, the same library the app and SeapEngine
search. AutoEq's results come from its own index, results/INDEX.md (the
GitHub tree API the app uses has no CORS and a 60-an-hour limit, so the
browser can't use it); each squig.link site lists its own in
/data/phone_book.json. The measurements themselves are not
copied: the page fetches the one picked, from raw.githubusercontent.com or
the squig.link site, both of which allow it.

Run again to pick up new headphones:  python3 tools/headphone_index/build.py
"""
import json, os, re, sys, urllib.parse, urllib.request

SQUIG = [  # host, label, type (None: guess from the name)
    ("https://squig.link", "Super* Review", None),
    ("https://precog.squig.link", "Precogvision", "in-ear"),
    ("https://dhrme.squig.link", "DHRME", "in-ear"),
    ("https://aftersound.squig.link", "Aftersound", "in-ear"),
    ("https://eliseaudio.squig.link", "Elise Audio", "in-ear"),
    ("https://jaytiss.squig.link", "Jaytiss", None),
    ("https://csi-zone.squig.link", "CSI-Zone", "in-ear"),
    ("https://achoreviews.squig.link", "Acho Reviews", "in-ear"),
    ("https://kr0mka.squig.link", "kr0mka", "over-ear"),
    ("https://dchpgall.squig.link", "dcinside", "in-ear"),
    ("https://joycesreview.squig.link", "Joyce's Review", "in-ear"),
    ("https://listener.squig.link", "Listener (DMS)", None),
]
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "site", "public", "data", "headphones.json")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "tryptify-headphone-index"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def get(url):
    return json.loads(fetch(url))


def kind(path):
    p = path.lower()
    return "in-ear" if ("in-ear" in p or "iem" in p) else "earbud" if "earbud" in p else "over-ear"


def main():
    sources, phones = [], {}

    def source(label, base, target):
        key = (label, base, target)
        if key not in source.ids:
            source.ids[key] = len(sources)
            sources.append({"label": label, "base": base, "target": target})
        return source.ids[key]
    source.ids = {}

    def add(name, typ, src, file):
        name = re.sub(r"\s+", " ", name).strip()
        # One entry per headphone however a database punctuates it
        # ("Blessing 2 Dusk", "Blessing 2: Dusk", "Blessing 2:Dusk").
        key = re.sub(r"[^a-z0-9]+", " ", name.lower()).strip()
        entry = phones.setdefault(key, {"n": name, "t": typ, "m": []})
        entry["m"].append([src, file])

    raw = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    line = re.compile(r"^- \[(?P<name>.+?)\]\(\./(?P<path>.+)\) by ")
    for row in fetch(raw + "INDEX.md").splitlines():
        m = line.match(row)
        if not m:
            continue
        parts = [urllib.parse.unquote(x) for x in m["path"].split("/")]
        if len(parts) < 2:
            continue
        folder = parts[-1]
        reviewer, rig = parts[0], "/".join(parts[1:-1]) or parts[0]
        src = source(reviewer, raw + "/".join(urllib.parse.quote(x) for x in parts[:-1]) + "/", rig)
        add(m["name"], kind("/".join(parts)), src, f"{folder}/{folder}.csv")

    for host, label, typ in SQUIG:
        try:
            book = get(f"{host}/data/phone_book.json")
        except Exception as e:  # one site down shouldn't stop the rest
            print(f"skipped {host}: {e}", file=sys.stderr)
            continue
        src = source(label, f"{host}/data/", "squig.link")
        for brand in book if isinstance(book, list) else []:
            for phone in (brand or {}).get("phones", []) or []:
                if not phone or not phone.get("name"):
                    continue
                files = phone["file"] if isinstance(phone.get("file"), list) else [phone.get("file") or phone["name"]]
                suffixes = phone.get("suffix") if isinstance(phone.get("suffix"), list) else []
                for i, f in enumerate(files):
                    suffix = suffixes[i] if i < len(suffixes) else ""
                    name = " ".join(x for x in (brand.get("name"), phone["name"], suffix) if x)
                    add(name, typ or kind(name), src, f)

    out = {"built": __import__("datetime").date.today().isoformat(), "sources": sources,
           "phones": sorted(phones.values(), key=lambda p: p["n"].lower())}
    with open(OUT, "w") as fh:
        json.dump(out, fh, separators=(",", ":"), ensure_ascii=False)
    print(f"{len(out['phones'])} headphones, {sum(len(p['m']) for p in out['phones'])} measurements, {len(sources)} sources -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
