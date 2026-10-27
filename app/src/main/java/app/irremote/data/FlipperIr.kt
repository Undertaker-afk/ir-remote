package app.irremote.data

import app.irremote.ir.IrSignal

/** Parser for Flipper Zero `.ir` files (used by the Flipper-IRDB and for user imports). */
object FlipperIr {
    fun parse(text: String, brand: String, model: String): IrRemote {
        val buttons = LinkedHashMap<String, IrButton>()
        var cur = HashMap<String, String>()
        fun flush() {
            val name = cur["name"]
            if (name != null && name !in buttons) toSignal(cur)?.let { buttons[name] = IrButton(name, IrSignal.encode(it)) }
            cur = HashMap()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) { flush(); continue }
            val i = line.indexOf(':')
            if (i < 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k == "name" && cur.containsKey("name")) flush()
            cur[k] = v
        }
        flush()
        return IrRemote(brand, model, buttons.values.toList())
    }

    private fun toSignal(m: Map<String, String>): IrSignal? = try {
        when (m["type"]) {
            "parsed" -> IrSignal.Parsed(m.getValue("protocol"), le(m.getValue("address")), le(m.getValue("command")))
            "raw" -> IrSignal.Raw(
                m["frequency"]?.toInt() ?: 38000,
                ((m["duty_cycle"]?.toDouble() ?: 0.33) * 100).toInt(),
                m.getValue("data").trim().split(Regex("\\s+")).map { it.toInt() }.toIntArray(),
            )
            else -> null
        }
    } catch (e: Exception) { null }

    /** "EE 87 00 00" (little endian bytes) -> 0x87EE */
    fun le(bytes: String): Long = bytes.trim().split(Regex("\\s+")).foldIndexed(0L) { i, acc, b -> acc or (b.toLong(16) shl (8 * i)) }
}
