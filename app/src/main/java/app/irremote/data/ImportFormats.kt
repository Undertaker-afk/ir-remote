package app.irremote.data

import app.irremote.ir.IrSignal
import kotlin.math.roundToInt

/**
 * Reads remotes from text in the formats people actually find online: Flipper .ir, LIRC .conf, irdb CSV,
 * Pronto hex (one code per line, optionally "Name: code") and raw timing dumps ("9000, 4500, 560 ...").
 */
object ImportFormats {
    fun parse(text: String, name: String): List<IrRemote> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val found = when {
            t.contains("Filetype: IR signals file") -> listOf(FlipperIr.parse(t, "", name))
            Regex("begin\\s+remote", RegexOption.IGNORE_CASE).containsMatchIn(t) -> Lirc.parse(t)
            t.lineSequence().first().trim().lowercase().startsWith("functionname,protocol,device") -> listOfNotNull(irdbCsv(t, name))
            else -> listOfNotNull(pronto(t, name) ?: rawDump(t, name))
        }
        return found.filter { it.buttons.isNotEmpty() }
    }

    private fun remote(name: String, buttons: List<Pair<String, IrSignal>>) =
        IrRemote("", name, buttons.map { IrButton(it.first, IrSignal.encode(it.second)) })

    // ------------------------------------------------------------ irdb CSV

    private fun irdbCsv(text: String, name: String): IrRemote? {
        val out = ArrayList<Pair<String, IrSignal>>()
        val seen = HashSet<String>()
        for (line in text.lineSequence().drop(1)) {
            val c = splitCsv(line)
            if (c.size < 5) continue
            val d = c[2].trim().toIntOrNull() ?: continue
            val sd = c[3].trim().toIntOrNull() ?: continue
            val f = c[4].trim().toIntOrNull() ?: continue
            val sig = IrdbMapping.signal(c[1].trim(), d, sd, f) ?: continue
            val n = IrdbMapping.prettyName(c[0])
            if (n.isNotEmpty() && seen.add(n)) out.add(n to sig)
        }
        return remote(name, out).takeIf { it.buttons.isNotEmpty() }
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(ch)
        }
        out.add(sb.toString())
        return out
    }

    // ------------------------------------------------------------ Pronto

    private val prontoLine = Regex("^(?:(.*?)\\s*[:=\\t]\\s*)?((?:[0-9A-Fa-f]{4}[\\s]+){5,}[0-9A-Fa-f]{4})\\s*$")

    private fun pronto(text: String, name: String): IrRemote? {
        val buttons = ArrayList<Pair<String, IrSignal>>()
        var n = 1
        // codes may be wrapped over several lines when there is no name; try the whole text as one code first
        val whole = text.replace(Regex("\\s+"), " ")
        if (Regex("^([0-9A-Fa-f]{4} )+[0-9A-Fa-f]{4}$").matches(whole)) prontoSignal(whole)?.let { buttons.add("Button" to it) }
        else for (line in text.lineSequence()) {
            val m = prontoLine.find(line.trim()) ?: continue
            val sig = prontoSignal(m.groupValues[2]) ?: continue
            buttons.add((m.groupValues[1].ifBlank { "Button ${n}" }) to sig); n++
        }
        return remote(name, buttons).takeIf { it.buttons.isNotEmpty() }
    }

    /** Pronto "learned, modulated" (0000) code to timings. */
    fun prontoSignal(code: String): IrSignal.Raw? {
        val w = code.trim().split(Regex("\\s+")).map { it.toIntOrNull(16) ?: return null }
        if (w.size < 6 || w[0] != 0 || w[1] == 0) return null
        val period = w[1] * 0.241246 // microseconds per carrier cycle
        val once = w[2]; val rep = w[3]
        if (w.size < 4 + 2 * (once + rep)) return null
        val pairs = if (once > 0) once else rep
        val offset = 4 + if (once > 0) 0 else 2 * once
        val t = IntArray(pairs * 2) { (w[offset + it] * period).roundToInt().coerceAtLeast(1) }
        return IrSignal.Raw((1_000_000 / period).roundToInt(), 33, t)
    }

    // ------------------------------------------------------------ raw dumps

    private fun rawDump(text: String, name: String): IrRemote? {
        // e.g. Arduino "uint16_t rawData[67] = {9000, 4500, 560, ...};" or plain "+9000 -4500 +560"
        val body = text.substringAfter('{', text).substringBefore('}')
        val nums = Regex("\\d+").findAll(body.replace(Regex("\\[\\d+]"), "")).map { it.value.toInt() }.toList()
        if (nums.size < 6 || nums.any { it > 200_000 }) return null
        return remote(name, listOf("Button" to IrSignal.Raw(38000, 33, nums.toIntArray())))
    }
}

/** LIRC `.conf` files: raw_codes directly, space-encoded codes (NEC/Samsung/Sony-like) via their timing parameters. */
object Lirc {
    fun parse(text: String): List<IrRemote> {
        val out = ArrayList<IrRemote>()
        val re = Regex("begin\\s+remote(.*?)end\\s+remote", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in re.findAll(text)) parseRemote(m.groupValues[1])?.let(out::add)
        return out
    }

    private fun words(line: String) = line.trim().split(Regex("\\s+"))

    private fun parseRemote(block: String): IrRemote? {
        val param = HashMap<String, String>()
        val flags: MutableSet<String> = HashSet()
        val coded = ArrayList<Pair<String, Long>>()
        val raw = ArrayList<Pair<String, IntArray>>()
        var mode = 0 // 0 params, 1 codes, 2 raw_codes
        var rawName: String? = null
        var rawNums = ArrayList<Int>()
        fun flushRaw() { rawName?.let { if (rawNums.size >= 2) raw.add(it to rawNums.toIntArray()) }; rawName = null; rawNums = ArrayList() }

        for (line in block.lineSequence()) {
            val l = line.substringBefore('#').trim()
            if (l.isEmpty()) continue
            val w = words(l)
            when {
                l.startsWith("begin") && l.contains("raw_codes") -> mode = 2
                l.startsWith("begin") && l.contains("codes") -> mode = 1
                l.startsWith("end") && l.contains("raw_codes") -> { flushRaw(); mode = 0 }
                l.startsWith("end") && l.contains("codes") -> mode = 0
                mode == 1 && w.size >= 2 -> parseNum(w[1])?.let { coded.add(w[0] to it) }
                mode == 2 -> if (w[0] == "name" && w.size >= 2) { flushRaw(); rawName = w[1] } else w.mapNotNull { it.toIntOrNull() }.let { rawNums.addAll(it) }
                w[0] == "flags" -> flags.addAll(l.removePrefix("flags").split('|', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() })
                w.size >= 2 -> param[w[0]] = w.drop(1).joinToString(" ")
            }
        }
        val name = param["name"]?.trim() ?: "LIRC remote"
        val freq = param["frequency"]?.trim()?.toIntOrNull() ?: 38000
        val buttons = ArrayList<IrButton>()
        for ((n, t) in raw) buttons.add(IrButton(n, IrSignal.encode(IrSignal.Raw(freq, 33, if (t.size % 2 == 1) t + 1000 else t))))
        if (coded.isNotEmpty() && "RC5" !in flags && "RC6" !in flags && "SHIFT_ENC" !in flags && "BO" !in flags) {
            for ((n, code) in coded) encode(param, flags, code)?.let { buttons.add(IrButton(n, IrSignal.encode(IrSignal.Raw(freq, 33, it)))) }
        }
        return if (buttons.isEmpty()) null else IrRemote("", name, buttons)
    }

    private fun parseNum(s: String): Long? = if (s.startsWith("0x", true)) s.substring(2).toLongOrNull(16) else s.toLongOrNull()

    private fun pair(s: String?): Pair<Int, Int>? = s?.let { words(it).mapNotNull { x -> x.toIntOrNull() } }?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }

    /** Space-encoded frame: [plead] [header] pre_data code post_data [foot] ptrail, padded to [gap] for CONST_LENGTH. */
    internal fun encode(p: Map<String, String>, flags: Set<String>, code: Long): IntArray? {
        val one = pair(p["one"]) ?: return null
        val zero = pair(p["zero"]) ?: return null
        val bits = p["bits"]?.trim()?.toIntOrNull() ?: return null
        val reverse = "REVERSE" in flags
        val t = ArrayList<Int>()
        fun add(v: Int) { if (v > 0) t.add(v) }
        p["plead"]?.trim()?.toIntOrNull()?.let { add(it); }
        pair(p["header"])?.let { add(it.first); add(it.second) }
        fun emit(value: Long, n: Int) {
            for (i in 0 until n) {
                val shift = if (reverse) i else n - 1 - i
                val b = (value ushr shift) and 1L == 1L
                val pr = if (b) one else zero
                add(pr.first); add(pr.second)
            }
        }
        val preBits = p["pre_data_bits"]?.trim()?.toIntOrNull() ?: 0
        if (preBits > 0) emit(parseNum(p["pre_data"]?.trim() ?: "0") ?: 0, preBits)
        emit(code, bits)
        val postBits = p["post_data_bits"]?.trim()?.toIntOrNull() ?: 0
        if (postBits > 0) emit(parseNum(p["post_data"]?.trim() ?: "0") ?: 0, postBits)
        pair(p["foot"])?.let { add(it.first); add(it.second) }
        p["ptrail"]?.trim()?.toIntOrNull()?.let { add(it) }
        if (t.size % 2 == 1) {
            val gap = p["gap"]?.let { words(it).firstOrNull()?.toIntOrNull() } ?: 20000
            val sum = t.sum()
            t.add(if ("CONST_LENGTH" in flags) (gap - sum).coerceAtLeast(5000) else gap)
        }
        return t.toIntArray().takeIf { it.size >= 4 }
    }
}
