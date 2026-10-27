#!/usr/bin/env python3
"""Convert a Flipper-IRDB checkout (CC0) into the compact per-category asset files used by the app.

usage: build_db.py <Flipper-IRDB dir> <output assets/db dir> [<probonopd/irdb dir>]

The optional irdb checkout (codes/<manufacturer>/<device type>/<device>,<subdevice>.csv) is merged in as well.
irdb is NOT CC0: see its LICENSE.md; the app shows the notice it requires.

Output format (UTF-8 text, one file per app category):
  @<brand>\t<remote name>          starts a remote
  <button>\tP:<proto>:<addr>:<cmd>  parsed signal (addr/cmd as hex, little endian bytes already folded)
  <button>\tR:<freq>:<duty%>:<t1,t2,...>   raw signal (microsecond timings, mark first)
Plus index.tsv: category \t brand count \t remote count
"""
import csv, os, re, sys, collections

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


# ---------------------------------------------------------------- probonopd/irdb

def irdb_category(devtype):
    t = devtype.lower()
    if t.startswith("unknown"):
        t = t[len("unknown"):].lstrip("_ ") or "unknown"
    if "projector" in t:
        return "projector"
    if "air cond" in t:
        return "ac"
    if "light" in t:
        return "light"
    if any(w in t for w in ("satellite", "sat", "dss", "cable", "dvb", "tuner", "tivo", "dvr", "digital recorder", "set top")) and "radio" not in t \
            and "fm" not in t and "am " not in t and not t.startswith("tuner"):
        return "settop"
    if any(w in t for w in ("dvd", "blu", "vcr", "laser")):
        return "dvd"
    if any(w in t for w in ("cd", "cassette", "tape", "mp3", "ipod", "tuner", "receiver", "amplifier", "amp", "processor", "surround",
                            "mini system", "jukebox", "karaoke", "dat", "music", "audio", "hifi", "radio")):
        return "avr"
    if t in ("tv", "plasma", "lcd", "monitor", "hdtv", "led", "crt") or t.endswith(" tv") or "television" in t:
        return "tv"
    return None


def inv8(v):
    return (~v) & 0xFF


def irdb_signal(proto, d, sd, f):
    """Map an irdb (protocol, device, subdevice, function) row to our compact parsed form, or None if unsupported."""
    pl = proto.lower()
    if pl in ("nec", "nec1", "nec2"):
        s = sd if sd >= 0 else inv8(d)
        if not (0 <= d < 256 and 0 <= s < 256 and 0 <= f < 256):
            return None
        return "P:NECext:%x:%x" % (d | s << 8, f | inv8(f) << 8)
    if pl in ("necx1", "necx2"):
        s = sd if sd >= 0 else d
        if not (0 <= d < 256 and 0 <= s < 256 and 0 <= f < 256):
            return None
        return "P:NECx:%x:%x" % (d | s << 8, f)
    if pl == "sony12" and 0 <= d < 32 and 0 <= f < 128:
        return "P:SIRC:%x:%x" % (d, f)
    if pl == "sony15" and 0 <= d < 256 and 0 <= f < 128:
        return "P:SIRC15:%x:%x" % (d, f)
    if pl == "sony20" and 0 <= d < 32 and 0 <= sd < 256 and 0 <= f < 128:
        return "P:SIRC20:%x:%x" % (d | sd << 5, f)
    if pl == "rc5" and 0 <= d < 32 and 0 <= f < 128:
        return "P:RC5X:%x:%x" % (d, f)
    if pl == "panasonic" and 0 <= d < 256 and 0 <= sd < 256 and 0 <= f < 256:
        return "P:Panasonic:%x:%x" % (d | sd << 8, f)
    if pl == "jvc" and 0 <= d < 256 and 0 <= f < 256:
        return "P:JVC:%x:%x" % (d, f)
    if pl in ("nec1-f16", "nec2-f16"):
        s = sd if sd >= 0 else inv8(d)
        if 0 <= d < 256 and 0 <= s < 256 and 0 <= f < 256:
            return "P:NECext:%x:%x" % (d | s << 8, f | inv8(f) << 8)
    if pl == "mitsubishi" and 0 <= d < 128 and 0 <= f < 256:
        return "P:Mitsubishi:%x:%x" % (d, f)
    if pl == "aiwa" and 0 <= d < 256 and 0 <= f < 256:
        s = sd if sd >= 0 else 0
        if s < 32:
            return "P:Aiwa:%x:%x" % (d | s << 8, f)
    if pl == "rc6" and 0 <= d < 256 and 0 <= f < 256:
        return "P:RC6:%x:%x" % (d, f)
    if pl == "denon" and 0 <= d < 32 and 0 <= f < 256:
        return "P:Denon:%x:%x" % (d, f)
    if pl == "sharp" and 0 <= d < 32 and 0 <= f < 256:
        return "P:Sharp:%x:%x" % (d, f)
    if pl == "denon-k" and 0 <= d < 16 and 0 <= sd < 16 and 0 <= f < 4096:
        return "P:DenonK:%x:%x" % (d | sd << 4, f)
    if pl == "pioneer" and 0 <= d < 256 and 0 <= f < 256:
        s = sd if sd >= 0 else 255 - d
        if 0 <= s < 256:
            return "P:Pioneer40:%x:%x" % (d | s << 8, f)
    if pl == "proton" and 0 <= d < 256 and 0 <= f < 256:
        return "P:Proton:%x:%x" % (d, f)
    if pl == "akai" and 0 <= d < 8 and 0 <= f < 128:
        return "P:Akai:%x:%x" % (d, f)
    if pl == "fujitsu" and 0 <= d < 256 and 0 <= f < 256:
        s = sd if sd >= 0 else d
        if 0 <= s < 256:
            return "P:Fujitsu:%x:%x" % (d | s << 8, f)
    if pl == "rca-38" and 0 <= d < 16 and 0 <= f < 256:
        return "P:RCA38:%x:%x" % (d, f)
    if pl == "panasonic_old" and 0 <= d < 32 and 0 <= f < 64:
        return "P:PanasonicOld:%x:%x" % (d, f)
    if pl == "mce" and 0 <= d < 128 and 0 <= sd < 256 and 0 <= f < 256:
        return "P:MCE:%x:%x" % (d | sd << 8, f)
    return None


def pretty(name):
    name = re.sub(r"[\t\r\n]+", " ", name).strip()
    return name.title() if name.isupper() else name


def load_irdb(irdb_dir):
    """Yield (app category, brand, model, [(button, enc)]) for every usable irdb file."""
    codes = os.path.join(irdb_dir, "codes")
    for mfr in sorted(os.listdir(codes)):
        if mfr.lower().startswith("unknown") or not os.path.isdir(os.path.join(codes, mfr)):
            continue
        for devtype in sorted(os.listdir(os.path.join(codes, mfr))):
            cat = irdb_category(devtype)
            dtp = os.path.join(codes, mfr, devtype)
            if cat is None or not os.path.isdir(dtp):
                continue
            for fn in sorted(os.listdir(dtp)):
                if not fn.endswith(".csv"):
                    continue
                buttons, seen = [], set()
                with open(os.path.join(dtp, fn), encoding="utf-8", errors="replace", newline="") as f:
                    for row in csv.DictReader(f):
                        try:
                            enc = irdb_signal(row["protocol"].strip(), int(row["device"]), int(row["subdevice"]), int(row["function"]))
                        except (KeyError, ValueError, AttributeError):
                            continue
                        name = pretty(row.get("functionname") or "")
                        if enc and name and name not in seen:
                            seen.add(name)
                            buttons.append((name, enc))
                if buttons:
                    yield cat, mfr, "%s %s" % (devtype, fn[:-4]), buttons


def main(root, out, irdb_dir=None):
    os.makedirs(out, exist_ok=True)
    index = []
    extra = collections.defaultdict(list)
    if irdb_dir:
        for cat, brand, model, buttons in load_irdb(irdb_dir):
            extra[cat].append((brand, model, buttons))
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
        # merge irdb: same brand (case-insensitive) joins the Flipper entry; identical button sets are dropped
        canon = {b.lower(): b for b in by_brand}
        have = {(b.lower(), frozenset(e for _, e in btns)) for b, rs in by_brand.items() for _, btns in rs}
        for brand, model, buttons in extra.get(app_cat, []):
            key = (brand.lower(), frozenset(e for _, e in buttons))
            if key in have:
                continue
            have.add(key)
            by_brand[canon.setdefault(brand.lower(), brand)].append((model, buttons))
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
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
