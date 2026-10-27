package app.irremote.ir.ac

import app.irremote.ir.Transmission

enum class AcMode { AUTO, COOL, DRY, FAN, HEAT }
enum class AcFan { AUTO, LOW, MEDIUM, HIGH }

/** Complete A/C remote state; every key press on a real AC remote re-sends all of it. */
data class AcState(
    val power: Boolean = false,
    val mode: AcMode = AcMode.COOL,
    val temp: Int = 24,
    val fan: AcFan = AcFan.AUTO,
    val swing: Boolean = false,
) {
    fun serialize() = "${if (power) 1 else 0},${mode.name},$temp,${fan.name},${if (swing) 1 else 0}"

    companion object {
        fun parse(s: String?): AcState? = try {
            val p = s!!.split(',')
            AcState(p[0] == "1", AcMode.valueOf(p[1]), p[2].toInt(), AcFan.valueOf(p[3]), p[4] == "1")
        } catch (e: Exception) { null }
    }
}

interface AcProtocol {
    val id: String
    val name: String
    val minTemp: Int
    val maxTemp: Int
    val modes: List<AcMode>
    val hasSwing: Boolean get() = false
    fun encode(state: AcState): Transmission
}

object AcProtocols {
    val all: List<AcProtocol> = listOf(Gree, Coolix, Midea, LgAc)
    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Protocols most likely to fit a brand, tried first by the wizard. Everything else follows. */
    private val hints = mapOf(
        "gree" to "gree", "cooper" to "gree", "sinclair" to "gree", "tosot" to "gree", "kelon" to "gree", "ballu" to "gree", "daitsu" to "gree",
        "lg" to "lgac",
        "midea" to "midea", "comfee" to "midea", "carrier" to "midea", "electrolux" to "midea", "inventor" to "midea", "pioneer" to "midea", "kaysun" to "midea", "senville" to "midea",
        "beko" to "coolix", "tcl" to "coolix", "hisense" to "coolix", "airwell" to "coolix", "toshiba" to "coolix", "bosch" to "coolix", "majority" to "coolix", "kastron" to "coolix",
    )

    fun orderedFor(brand: String): List<AcProtocol> {
        val b = brand.lowercase()
        val first = hints.entries.filter { b.contains(it.key) }.mapNotNull { byId(it.value) }.distinct()
        return first + all.filter { it !in first }
    }
}

/** Accumulates mark/space durations; consecutive marks (or spaces) are merged. */
internal class Pulses {
    private val list = ArrayList<Int>()
    private var mark = true

    fun mark(us: Int) = put(true, us)
    fun space(us: Int) = put(false, us)

    private fun put(isMark: Boolean, us: Int) {
        if (us <= 0) return
        if (list.isEmpty()) {
            if (!isMark) return
        } else if (isMark == mark) {
            list[list.size - 1] = list.last() + us
            return
        }
        list.add(us); mark = isMark
    }

    /** Pulse-distance data: [bits] of [value], LSB first or MSB first. */
    fun bits(value: Long, bits: Int, msbFirst: Boolean, bitMark: Int, oneSpace: Int, zeroSpace: Int) {
        for (i in 0 until bits) {
            val shift = if (msbFirst) bits - 1 - i else i
            mark(bitMark)
            space(if ((value ushr shift) and 1L == 1L) oneSpace else zeroSpace)
        }
    }

    fun build(carrier: Int): Transmission {
        if (list.size % 2 == 1) list.add(5000)
        return Transmission(carrier, list.toIntArray())
    }
}
