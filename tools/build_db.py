#!/usr/bin/env python3
"""Convert a Flipper-IRDB checkout (CC0) into the compact per-category asset files used by the app.

usage: build_db.py <Flipper-IRDB dir> <output assets/db dir>

Output format (UTF-8 text, one file per app category):
  @<brand>\t<remote name>          starts a remote
  <button>\tP:<proto>:<addr>:<cmd>  parsed signal (addr/cmd as hex, little endian bytes already folded)
  <button>\tR:<freq>:<duty%>:<t1,t2,...>   raw signal (microsecond timings, mark first)
Plus index.tsv: category \t brand count \t remote count
"""
import os, re, sys, collections

CATEGORY_MAP = {
    "tv": ["TVs", "Universal_TV_Remotes", "Monitors", "Digital_Signs", "Touchscreen_Displays"],
    "settop": ["Cable_Boxes", "DVB-T", "TV_Tuner", "Converters"],
    "ac": ["ACs"],
    "fan": ["Fans", "Air_Purifiers", "Heaters", "Humidifiers", "Fireplaces"],
    "stream": ["Streaming_Devices", "Multimedia"],
    "avr": ["Audio_and_Video_Receivers", "SoundBars", "Speakers", "Head_Units", "Car_Multimedia", "CD_Players", "MiniDisc"],
    "dvd": ["DVD_Players", "Blu-Ray", "VCR", "Laserdisc"],
    "projector": ["Projectors"],
    "camera": ["Cameras", "CCTV"],
    "light": ["LED_Lighting"],
    "cleaner": ["Vacuum_Cleaners", "Window_cleaners", "Dust_Collectors"],
    "computer": ["Consoles", "Computers", "KVM", "Picture_Frames"],
}
MAX_RAW_SAMPLES = 700


def parse_ir(path):
    sigs, cur = [], {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line.startswith("#") or not line:
                if cur.get("name"):
                    sigs.append(cur)
                cur = {}
                continue
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            k, v = k.strip().lower(), v.strip()
            if k == "name" and cur.get("name"):
                sigs.append(cur)
                cur = {}
            cur[k] = v
    if cur.get("name"):
        sigs.append(cur)
    return sigs


def le(hexbytes):
    b = [int(x, 16) for x in hexbytes.split()]
    return sum(v << (8 * i) for i, v in enumerate(b))


def encode(sig):
    name = re.sub(r"[\t\r\n]+", " ", sig["name"]).strip()
    if sig.get("type") == "parsed" and "protocol" in sig and "address" in sig and "command" in sig:
        try:
            return name, "P:%s:%x:%x" % (sig["protocol"], le(sig["address"]), le(sig["command"]))
        except ValueError:
            return None
    if sig.get("type") == "raw" and "data" in sig:
        try:
            t = [int(x) for x in sig["data"].split()]
            if not 4 <= len(t) <= MAX_RAW_SAMPLES:
                return None
            freq = int(sig.get("frequency", 38000))
            duty = int(round(float(sig.get("duty_cycle", 0.33)) * 100))
            return name, "R:%d:%d:%s" % (freq, duty, ",".join(map(str, t)))
        except ValueError:
            return None
    return None


def brand_and_model(root, cat, path):
    rel = os.path.relpath(path, os.path.join(root, cat))
    parts = rel.split(os.sep)
    stem = os.path.splitext(parts[-1])[0]
    if len(parts) > 1:
        brand = parts[0].replace("_", " ")
        model = stem
    else:
        brand = stem.split("_")[0].replace("-", " ")
        model = stem
    model = model.replace("_", " ")
    return brand.strip() or "Unknown", model.strip()


def main(root, out):
    os.makedirs(out, exist_ok=True)
    index = []
    for app_cat, dirs in CATEGORY_MAP.items():
        by_brand = collections.defaultdict(list)
        for d in dirs:
            base = os.path.join(root, d)
            if not os.path.isdir(base):
                continue
            for dp, _, fs in os.walk(base):
                for fn in sorted(fs):
                    if not fn.lower().endswith(".ir"):
                        continue
                    p = os.path.join(dp, fn)
                    buttons, seen = [], set()
                    for s in parse_ir(p):
                        e = encode(s)
                        if e and e[0] not in seen:
                            seen.add(e[0])
                            buttons.append(e)
                    if buttons:
                        brand, model = brand_and_model(root, d, p)
                        by_brand[brand.title() if brand.islower() else brand].append((model, buttons))
        n = 0
        with open(os.path.join(out, app_cat + ".txt"), "w", encoding="utf-8") as o:
            for brand in sorted(by_brand, key=str.lower):
                for model, buttons in sorted(by_brand[brand]):
                    o.write("@%s\t%s\n" % (brand, model))
                    for name, enc in buttons:
                        o.write("%s\t%s\n" % (name, enc))
                    n += 1
        index.append((app_cat, len(by_brand), n))
        print(app_cat, len(by_brand), "brands", n, "remotes")
    with open(os.path.join(out, "index.tsv"), "w") as f:
        for r in index:
            f.write("%s\t%d\t%d\n" % r)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
