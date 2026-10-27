package app.irremote.data

import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Builds the per-category database files from GitHub zip snapshots of Flipper-IRDB and irdb. This mirrors
 * tools/build_db.py (which produces the bundled assets) so an in-app update yields the same data format.
 */
object DbBuilder {
    val categoryMap: Map<Category, List<String>> = linkedMapOf(
        Category.TV to listOf("TVs", "Universal_TV_Remotes", "Monitors", "Digital_Signs", "Touchscreen_Displays"),
        Category.SETTOP to listOf("Cable_Boxes", "DVB-T", "TV_Tuner", "Converters"),
        Category.AC to listOf("ACs"),
        Category.FAN to listOf("Fans", "Air_Purifiers", "Heaters", "Humidifiers", "Fireplaces"),
        Category.STREAM to listOf("Streaming_Devices", "Multimedia"),
        Category.AVR to listOf("Audio_and_Video_Receivers", "SoundBars", "Speakers", "Head_Units", "Car_Multimedia", "CD_Players", "MiniDisc"),
        Category.DVD to listOf("DVD_Players", "Blu-Ray", "VCR", "Laserdisc"),
        Category.PROJECTOR to listOf("Projectors"),
        Category.CAMERA to listOf("Cameras", "CCTV"),
        Category.LIGHT to listOf("LED_Lighting"),
        Category.CLEANER to listOf("Vacuum_Cleaners", "Window_cleaners", "Dust_Collectors"),
        Category.COMPUTER to listOf("Consoles", "Computers", "KVM", "Picture_Frames"),
    )
    private const val MAX_RAW_SAMPLES = 700
    private const val MAX_ENTRY_BYTES = 3_000_000
    private const val MAX_ENTRIES = 60_000

    class Remote(val model: String, val buttons: List<Pair<String, String>>)

    class Result(val files: Map<Category, String>, val remotes: Map<Category, Int>) {
        val total get() = remotes.values.sum()
    }

    fun build(flipperZip: InputStream?, irdbZip: InputStream?): Result {
        val byCat = HashMap<Category, HashMap<String, MutableList<Remote>>>()
        flipperZip?.let { readFlipper(it, byCat) }
        val extra = HashMap<Category, MutableList<Triple<String, String, List<Pair<String, String>>>>>()
        irdbZip?.let { readIrdb(it, extra) }

        val files = LinkedHashMap<Category, String>()
        val counts = LinkedHashMap<Category, Int>()
        for (cat in categoryMap.keys) {
            val byBrand = byCat[cat] ?: HashMap()
            val canon = HashMap<String, String>().apply { byBrand.keys.forEach { put(it.lowercase(), it) } }
            val have = HashSet<Pair<String, Set<String>>>()
            for ((b, rs) in byBrand) for (r in rs) have.add(b.lowercase() to r.buttons.map { it.second }.toSet())
            for ((brand, model, buttons) in extra[cat].orEmpty()) {
                val key = brand.lowercase() to buttons.map { it.second }.toSet()
                if (!have.add(key)) continue
                byBrand.getOrPut(canon.getOrPut(brand.lowercase()) { brand }) { ArrayList() }.add(Remote(model, buttons))
            }
            val sb = StringBuilder()
            var n = 0
            for (brand in byBrand.keys.sortedBy { it.lowercase() }) {
                for (r in byBrand.getValue(brand).sortedBy { it.model }) {
                    sb.append('@').append(brand).append('\t').append(r.model).append('\n')
                    for ((name, enc) in r.buttons) sb.append(name).append('\t').append(enc).append('\n')
                    n++
                }
            }
            files[cat] = sb.toString()
            counts[cat] = n
        }
        return Result(files, counts)
    }

    // ------------------------------------------------------------ Flipper-IRDB

    private fun readFlipper(input: InputStream, out: HashMap<Category, HashMap<String, MutableList<Remote>>>) {
        val dirToCat = HashMap<String, Category>().apply { categoryMap.forEach { (c, dirs) -> dirs.forEach { put(it, c) } } }
        forEachEntry(input) { path, read ->
            val parts = path.split('/').drop(1) // drop "Flipper-IRDB-main/"
            if (parts.size < 2 || !parts.last().endsWith(".ir")) return@forEachEntry
            val cat = dirToCat[parts[0]] ?: return@forEachEntry
            val buttons = flipperButtons(read())
            if (buttons.isEmpty()) return@forEachEntry
            val rel = parts.drop(1)
            val stem = rel.last().removeSuffix(".ir")
            val brand = (if (rel.size > 1) rel[0].replace('_', ' ') else stem.split('_')[0].replace('-', ' ')).trim().ifEmpty { "Unknown" }
            val model = stem.replace('_', ' ').trim()
            val key = if (isLowerCased(brand)) pyTitle(brand) else brand
            out.getOrPut(cat) { HashMap() }.getOrPut(key) { ArrayList() }.add(Remote(model, buttons))
        }
    }

    /** Parses a Flipper .ir file into (button, compact signal) pairs, mirroring parse_ir/encode in build_db.py. */
    fun flipperButtons(text: String): List<Pair<String, String>> {
        val sigs = ArrayList<Map<String, String>>()
        var cur = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.isEmpty()) {
                if (cur["name"].orEmpty().isNotEmpty()) sigs.add(cur)
                cur = HashMap()
                continue
            }
            val i = line.indexOf(':')
            if (i < 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k == "name" && cur["name"].orEmpty().isNotEmpty()) { sigs.add(cur); cur = HashMap() }
            cur[k] = v
        }
        if (cur["name"].orEmpty().isNotEmpty()) sigs.add(cur)

        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (s in sigs) {
            val enc = encodeSignal(s) ?: continue
            if (seen.add(enc.first)) out.add(enc)
        }
        return out
    }

    private fun le(hex: String): Long = hex.trim().split(Regex("\\s+")).foldIndexed(0L) { i, acc, b -> acc or (b.toLong(16) shl (8 * i)) }

    private fun encodeSignal(s: Map<String, String>): Pair<String, String>? {
        val name = s.getValue("name").replace(Regex("[\\t\\r\\n]+"), " ").trim()
        if (s["type"] == "parsed" && s.containsKey("protocol") && s.containsKey("address") && s.containsKey("command")) {
            return try { name to "P:${s["protocol"]}:${le(s.getValue("address")).toString(16)}:${le(s.getValue("command")).toString(16)}" } catch (e: Exception) { null }
        }
        if (s["type"] == "raw" && s.containsKey("data")) {
            return try {
                val t = s.getValue("data").trim().split(Regex("\\s+")).map { it.toInt() }
                if (t.size !in 4..MAX_RAW_SAMPLES) return null
                val freq = (s["frequency"] ?: "38000").trim().toInt()
                val duty = Math.rint((s["duty_cycle"] ?: "0.33").trim().toDouble() * 100).toInt()
                name to "R:$freq:$duty:${t.joinToString(",")}"
            } catch (e: Exception) { null }
        }
        return null
    }

    // ------------------------------------------------------------ irdb

    private fun readIrdb(input: InputStream, out: HashMap<Category, MutableList<Triple<String, String, List<Pair<String, String>>>>>) {
        // collect first so the result order matches build_db.py (sorted manufacturer, device type, file)
        class Item(val mfr: String, val devtype: String, val file: String, val cat: Category, val buttons: List<Pair<String, String>>)
        val items = ArrayList<Item>()
        forEachEntry(input) { path, read ->
            val parts = path.split('/').drop(1) // drop "irdb-master/"
            if (parts.size != 4 || parts[0] != "codes" || !parts[3].endsWith(".csv")) return@forEachEntry
            val mfr = parts[1]; val devtype = parts[2]
            if (mfr.lowercase().startsWith("unknown")) return@forEachEntry
            val cat = IrdbMapping.category(devtype) ?: return@forEachEntry
            val buttons = irdbButtons(read())
            if (buttons.isNotEmpty()) items.add(Item(mfr, devtype, parts[3].removeSuffix(".csv"), cat, buttons))
        }
        items.sortWith(compareBy<Item>({ it.mfr }, { it.devtype }, { it.file }))
        for (i in items) out.getOrPut(i.cat) { ArrayList() }.add(Triple(i.mfr, "${i.devtype} ${i.file}", i.buttons))
    }

    private fun irdbButtons(text: String): List<Pair<String, String>> {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val header = splitCsv(lines.next()).map { it.trim().lowercase() }
        val iName = header.indexOf("functionname"); val iProto = header.indexOf("protocol")
        val iDev = header.indexOf("device"); val iSub = header.indexOf("subdevice"); val iFn = header.indexOf("function")
        if (listOf(iProto, iDev, iSub, iFn).any { it < 0 }) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        while (lines.hasNext()) {
            val c = splitCsv(lines.next())
            fun col(i: Int) = c.getOrNull(i)
            val d = col(iDev)?.trim()?.toIntOrNull() ?: continue
            val sd = col(iSub)?.trim()?.toIntOrNull() ?: continue
            val f = col(iFn)?.trim()?.toIntOrNull() ?: continue
            val sig = IrdbMapping.signal(col(iProto)?.trim() ?: continue, d, sd, f) ?: continue
            val name = IrdbMapping.prettyName(if (iName >= 0) col(iName).orEmpty() else "")
            if (name.isNotEmpty() && seen.add(name)) out.add(name to "P:${sig.protocol}:${sig.address.toString(16)}:${sig.command.toString(16)}")
        }
        return out
    }

    private fun splitCsv(line: String) = splitCsvLine(line)

    // ------------------------------------------------------------ zip walking with limits

    private fun forEachEntry(input: InputStream, action: (path: String, read: () -> String) -> Unit) {
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (++count > MAX_ENTRIES) throw IllegalStateException("archive has too many files")
                val name = e.name
                if (name.split('/').any { it == ".." }) continue
                action(name) {
                    val buf = java.io.ByteArrayOutputStream()
                    val b = ByteArray(8192)
                    while (true) {
                        val n = zip.read(b)
                        if (n < 0) break
                        if (buf.size() + n > MAX_ENTRY_BYTES) { buf.reset(); break } // oversize file: ignore its content
                        buf.write(b, 0, n)
                    }
                    buf.toString("UTF-8")
                }
            }
        }
    }
}
