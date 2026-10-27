package app.irremote.ir.ac

import app.irremote.ir.Transmission

/** Gree and its many rebadges (Cooper & Hunter, Sinclair, Tosot...). Layout and checksum from IRremoteESP8266. */
object Gree : AcProtocol {
    override val id = "gree"
    override val name = "Gree"
    override val minTemp = 16
    override val maxTemp = 30
    override val modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT)
    override val hasSwing = true

    fun bytes(
        power: Boolean, mode: Int, temp: Int, fan: Int, swingAuto: Boolean, swingV: Int,
        light: Boolean = true, displayTemp: Int = 0,
    ): IntArray {
        val d = IntArray(8)
        d[0] = (mode and 7) or ((if (power) 1 else 0) shl 3) or ((fan and 3) shl 4) or ((if (swingAuto) 1 else 0) shl 6)
        d[1] = (temp - 16) and 0xF
        d[2] = ((if (light) 1 else 0) shl 5) or ((if (power) 1 else 0) shl 6) // bit 6: "ModelA", set when on (YAW1F)
        d[3] = 0x50
        d[4] = swingV and 0xF
        d[5] = (displayTemp and 3) or (4 shl 3)
        var sum = 10
        for (i in 0..3) sum += d[i] and 0xF
        for (i in 4..6) sum += d[i] shr 4
        d[7] = (sum and 0xF) shl 4
        return d
    }

    override fun encode(state: AcState): Transmission {
        val mode = when (state.mode) { AcMode.AUTO -> 0; AcMode.COOL -> 1; AcMode.DRY -> 2; AcMode.FAN -> 3; AcMode.HEAT -> 4 }
        val temp = if (state.mode == AcMode.AUTO) 25 else state.temp.coerceIn(minTemp, maxTemp)
        val fan = if (state.mode == AcMode.DRY) 1 else when (state.fan) { AcFan.AUTO -> 0; AcFan.LOW -> 1; AcFan.MEDIUM -> 2; AcFan.HIGH -> 3 }
        val d = bytes(state.power, mode, temp, fan, state.swing, if (state.swing) 1 else 0)
        val p = Pulses()
        p.mark(9000); p.space(4500)
        fun block(from: Int) {
            for (i in from until from + 4) p.bits(d[i].toLong(), 8, false, 620, 1600, 540)
        }
        block(0)
        p.bits(0b010, 3, false, 620, 1600, 540) // mid-message footer
        p.mark(620); p.space(19980)
        block(4)
        p.mark(620); p.space(19980)
        return p.build(38000)
    }
}

/** Coolix (Beko, Midea-like, many OEM units). 24-bit state sent MSB first, each byte followed by its complement. */
object Coolix : AcProtocol {
    override val id = "coolix"
    override val name = "Coolix"
    override val minTemp = 17
    override val maxTemp = 30
    override val modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT)

    private const val OFF = 0xB27BE0
    private val tempMap = intArrayOf(0, 1, 3, 2, 6, 7, 5, 4, 12, 13, 9, 8, 10, 11) // 17..30 C

    fun code(state: AcState): Int {
        if (!state.power) return OFF
        val (modeBits, tempBits) = when (state.mode) {
            AcMode.COOL -> 0 to tempMap[state.temp.coerceIn(minTemp, maxTemp) - minTemp]
            AcMode.DRY -> 1 to tempMap[state.temp.coerceIn(minTemp, maxTemp) - minTemp]
            AcMode.AUTO -> 2 to tempMap[state.temp.coerceIn(minTemp, maxTemp) - minTemp]
            AcMode.HEAT -> 3 to tempMap[state.temp.coerceIn(minTemp, maxTemp) - minTemp]
            AcMode.FAN -> 1 to 0b1110 // "fan only" is Dry with a special temperature code
        }
        val fanBits = if (state.mode == AcMode.AUTO || state.mode == AcMode.DRY) 0 else when (state.fan) {
            AcFan.AUTO -> 0b101; AcFan.LOW -> 0b100; AcFan.MEDIUM -> 0b010; AcFan.HIGH -> 0b001
        }
        val sensorIgnore = 0x1F
        return (0xB shl 20) or (0b010 shl 16) or (fanBits shl 13) or (sensorIgnore shl 8) or (tempBits shl 4) or (modeBits shl 2)
    }

    override fun encode(state: AcState): Transmission {
        val c = code(state)
        val p = Pulses()
        p.mark(4692); p.space(4416)
        for (shift in intArrayOf(16, 8, 0)) {
            val b = (c shr shift) and 0xFF
            p.bits(b.toLong(), 8, true, 552, 1656, 552)
            p.bits((b xor 0xFF).toLong(), 8, true, 552, 1656, 552)
        }
        p.mark(552); p.space(5244)
        return p.build(38000)
    }
}

/** Midea family (Comfee, Carrier, Electrolux, Pioneer...). 48-bit state, sent twice, the second time inverted. */
object Midea : AcProtocol {
    override val id = "midea"
    override val name = "Midea"
    override val minTemp = 17
    override val maxTemp = 30
    override val modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT)

    private fun reverse8(v: Int): Int { var r = 0; for (i in 0 until 8) if ((v shr i) and 1 == 1) r = r or (1 shl (7 - i)); return r }

    /** State as 6 bytes, most significant (sent first) first. Temperature uses the Fahrenheit-native encoding the reference library defaults to. */
    fun bytes(state: AcState): IntArray {
        val mode = when (state.mode) { AcMode.COOL -> 0; AcMode.DRY -> 1; AcMode.AUTO -> 2; AcMode.HEAT -> 3; AcMode.FAN -> 4 }
        val fan = when (state.fan) { AcFan.AUTO -> 0; AcFan.LOW -> 1; AcFan.MEDIUM -> 2; AcFan.HIGH -> 3 }
        val c = state.temp.coerceIn(minTemp, maxTemp)
        val f = (c * 9.0 / 5.0 + 32).toInt().coerceIn(62, 86)
        val b = IntArray(6)
        b[0] = 0xA1
        b[1] = ((if (state.power) 1 else 0) shl 7) or (fan shl 3) or mode
        b[2] = 0x60 or (f - 62)
        b[3] = 0xFF
        b[4] = 0xFF
        var sum = 0
        for (i in 0..4) sum += reverse8(b[i])
        b[5] = reverse8((256 - (sum and 0xFF)) and 0xFF)
        return b
    }

    override fun encode(state: AcState): Transmission {
        val p = Pulses()
        val b = bytes(state)
        for (pass in 0..1) {
            p.mark(4480); p.space(4480)
            for (v in b) p.bits((if (pass == 0) v else v xor 0xFF).toLong(), 8, true, 560, 1680, 560)
            p.mark(560); p.space(5600)
        }
        return p.build(38000)
    }
}

/** LG air conditioners (28-bit protocol, e.g. GE6711AR2853M remotes). */
object LgAc : AcProtocol {
    override val id = "lgac"
    override val name = "LG"
    override val minTemp = 16
    override val maxTemp = 30
    override val modes = listOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN, AcMode.HEAT)

    private const val OFF = 0x88C0051

    fun code(state: AcState): Int {
        if (!state.power) return OFF
        val mode = when (state.mode) { AcMode.COOL -> 0; AcMode.DRY -> 1; AcMode.FAN -> 2; AcMode.AUTO -> 3; AcMode.HEAT -> 4 }
        val fan = when (state.fan) { AcFan.AUTO -> 5; AcFan.LOW -> 1; AcFan.MEDIUM -> 2; AcFan.HIGH -> 4 }
        val temp = state.temp.coerceIn(minTemp, maxTemp) - 15
        val body = (0x88 shl 20) or (mode shl 12) or (temp shl 8) or (fan shl 4)
        val sum = (0..3).sumOf { (body shr (4 + 4 * it)) and 0xF } and 0xF
        return body or sum
    }

    override fun encode(state: AcState): Transmission {
        val p = Pulses()
        p.mark(8500); p.space(4250)
        p.bits(code(state).toLong(), 28, true, 550, 1600, 550)
        p.mark(550); p.space(39750)
        return p.build(38000)
    }
}
