package app.irremote.data

import app.irremote.ir.IrSignal

/** Maps probonopd/irdb rows (protocol, device, subdevice, function) to signals the encoder understands. */
object IrdbMapping {
    private fun inv8(v: Int) = v.inv() and 0xFF

    fun signal(protocol: String, d: Int, sd: Int, f: Int): IrSignal.Parsed? {
        fun p(name: String, a: Int, c: Int) = IrSignal.Parsed(name, a.toLong(), c.toLong())
        return when (protocol.lowercase()) {
            "nec", "nec1", "nec2", "nec1-f16", "nec2-f16" -> {
                val s = if (sd >= 0) sd else inv8(d)
                if (d in 0..255 && s in 0..255 && f in 0..255) p("NECext", d or (s shl 8), f or (inv8(f) shl 8)) else null
            }
            "necx1", "necx2" -> {
                val s = if (sd >= 0) sd else d
                if (d in 0..255 && s in 0..255 && f in 0..255) p("NECx", d or (s shl 8), f) else null
            }
            "sony12" -> if (d in 0..31 && f in 0..127) p("SIRC", d, f) else null
            "sony15" -> if (d in 0..255 && f in 0..127) p("SIRC15", d, f) else null
            "sony20" -> if (d in 0..31 && sd in 0..255 && f in 0..127) p("SIRC20", d or (sd shl 5), f) else null
            "rc5" -> if (d in 0..31 && f in 0..127) p("RC5X", d, f) else null
            "rc6" -> if (d in 0..255 && f in 0..255) p("RC6", d, f) else null
            "panasonic" -> if (d in 0..255 && sd in 0..255 && f in 0..255) p("Panasonic", d or (sd shl 8), f) else null
            "jvc" -> if (d in 0..255 && f in 0..255) p("JVC", d, f) else null
            "mitsubishi" -> if (d in 0..127 && f in 0..255) p("Mitsubishi", d, f) else null
            "aiwa" -> { val s = if (sd >= 0) sd else 0; if (d in 0..255 && f in 0..255 && s < 32) p("Aiwa", d or (s shl 8), f) else null }
            "denon" -> if (d in 0..31 && f in 0..255) p("Denon", d, f) else null
            "sharp" -> if (d in 0..31 && f in 0..255) p("Sharp", d, f) else null
            "denon-k" -> if (d in 0..15 && sd in 0..15 && f in 0..4095) p("DenonK", d or (sd shl 4), f) else null
            "pioneer" -> { val s = if (sd >= 0) sd else 255 - d; if (d in 0..255 && s in 0..255 && f in 0..255) p("Pioneer40", d or (s shl 8), f) else null }
            "proton" -> if (d in 0..255 && f in 0..255) p("Proton", d, f) else null
            "akai" -> if (d in 0..7 && f in 0..127) p("Akai", d, f) else null
            "fujitsu" -> { val s = if (sd >= 0) sd else d; if (d in 0..255 && s in 0..255 && f in 0..255) p("Fujitsu", d or (s shl 8), f) else null }
            "rca-38" -> if (d in 0..15 && f in 0..255) p("RCA38", d, f) else null
            "panasonic_old" -> if (d in 0..31 && f in 0..63) p("PanasonicOld", d, f) else null
            "mce" -> if (d in 0..127 && sd in 0..255 && f in 0..255) p("MCE", d or (sd shl 8), f) else null
            else -> null
        }
    }

    /** irdb function names are shouty ("VOL+", "INPUT SOURCE"); Title Case them (like Python's str.title) when all caps. */
    fun prettyName(name: String): String {
        val n = name.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        return if (isUpperCased(n)) pyTitle(n) else n
    }

    /** Maps an irdb device-type folder name to one of our categories, or null if we don't cover it. */
    fun category(devtype: String): Category? {
        var t = devtype.lowercase()
        if (t.startsWith("unknown")) t = t.removePrefix("unknown").trimStart('_', ' ').ifEmpty { "unknown" }
        if ("projector" in t) return Category.PROJECTOR
        if ("air cond" in t) return Category.AC
        if ("light" in t) return Category.LIGHT
        val settop = listOf("satellite", "sat", "dss", "cable", "dvb", "tuner", "tivo", "dvr", "digital recorder", "set top").any { it in t } &&
            "radio" !in t && "fm" !in t && "am " !in t && !t.startsWith("tuner")
        if (settop) return Category.SETTOP
        if (listOf("dvd", "blu", "vcr", "laser").any { it in t }) return Category.DVD
        if (listOf("cd", "cassette", "tape", "mp3", "ipod", "tuner", "receiver", "amplifier", "amp", "processor", "surround",
                "mini system", "jukebox", "karaoke", "dat", "music", "audio", "hifi", "radio").any { it in t }) return Category.AVR
        if (t in setOf("tv", "plasma", "lcd", "monitor", "hdtv", "led", "crt") || t.endsWith(" tv") || "television" in t) return Category.TV
        return null
    }
}

/** Python's str.isupper(): has a cased letter and no lower-case letters. */
internal fun isUpperCased(s: String) = s.any { it.isLetter() } && s.none { it.isLowerCase() }

/** Python's str.islower(). */
internal fun isLowerCased(s: String) = s.any { it.isLetter() } && s.none { it.isUpperCase() }

/** Python's str.title(): first letter of every run of letters upper-case, the rest lower-case. */
internal fun pyTitle(s: String): String {
    val sb = StringBuilder(s.length)
    var prevLetter = false
    for (ch in s) {
        sb.append(if (ch.isLetter()) (if (prevLetter) ch.lowercaseChar() else ch.uppercaseChar()) else ch)
        prevLetter = ch.isLetter()
    }
    return sb.toString()
}

/** One CSV line, following Python's csv module defaults: quotes only open a field when they start it, "" is a literal quote. */
internal fun splitCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    var atStart = true
    var quoted = false
    while (i < line.length) {
        val ch = line[i]
        when {
            quoted -> when {
                ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = false
                else -> sb.append(ch)
            }
            ch == '"' && atStart -> quoted = true
            ch == ',' -> { out.add(sb.toString()); sb.clear(); atStart = true; i++; continue }
            else -> sb.append(ch)
        }
        atStart = false
        i++
    }
    out.add(sb.toString())
    return out
}
