package app.irremote.ir

/**
 * Turns database signals into mark/space timings. Protocol framing follows the Flipper Zero
 * firmware encoders so that the Flipper-IRDB "parsed" entries replay correctly.
 */
object IrEncoder {
    fun supports(protocol: String) = protocol in PROTOCOLS

    val PROTOCOLS = setOf(
        "NEC", "NECext", "NEC42", "NEC42ext", "Samsung32", "RC5", "RC5X", "RC6",
        "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "RCA", "Pioneer",
        // irdb-only framings
        "NECx", "Panasonic", "JVC",
    )

    fun encode(signal: IrSignal): Transmission? = when (signal) {
        is IrSignal.Raw -> encodeRaw(signal)
        is IrSignal.Parsed -> encodeParsed(signal)
    }

    private fun encodeRaw(s: IrSignal.Raw): Transmission? {
        if (s.timings.size < 2) return null
        // The pattern must alternate on/off and end with an "on"+"off" pair, so drop a dangling mark.
        val t = if (s.timings.size % 2 == 1) s.timings + 1000 else s.timings
        return Transmission(s.frequency.coerceIn(20000, 60000), t)
    }

    private fun encodeParsed(s: IrSignal.Parsed): Transmission? {
        val a = s.address
        val c = s.command
        return when (s.protocol) {
            "NEC" -> pulseDistance(
                38000, 9000, 4500, 560, 1690, 560, 560, 560, 110000,
                bits = (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24),
                count = 32,
            )
            "NECext" -> pulseDistance(
                38000, 9000, 4500, 560, 1690, 560, 560, 560, 110000,
                bits = (a and 0xFFFF) or ((c and 0xFFFF) shl 16), count = 32,
            )
            "NEC42" -> pulseDistance(
                38000, 9000, 4500, 560, 1690, 560, 560, 560, 110000,
                bits = (a and 0x1FFF) or ((a.inv() and 0x1FFF) shl 13) or ((c and 0xFF) shl 26) or ((c.inv() and 0xFF) shl 34),
                count = 42,
            )
            "NEC42ext" -> pulseDistance(
                38000, 9000, 4500, 560, 1690, 560, 560, 560, 110000,
                bits = (a and 0x3FFFFFF) or ((c and 0xFFFF) shl 26), count = 42,
            )
            "Samsung32" -> pulseDistance(
                38000, 4500, 4500, 550, 1650, 550, 550, 550, 108000,
                bits = (a and 0xFF) or ((a and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24),
                count = 32,
            )
            // NEC with the 4.5 ms leader used by Samsung and friends: D, S, F, ~F (address = D | S << 8)
            "NECx" -> pulseDistance(
                38000, 4512, 4512, 564, 1692, 564, 564, 564, 108000,
                bits = (a and 0xFFFF) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24), count = 32,
            )
            "JVC" -> pulseDistance(
                38000, 8416, 4208, 526, 1578, 526, 526, 526, 60000,
                bits = (a and 0xFF) or ((c and 0xFF) shl 8), count = 16,
            )
            // Panasonic: vendor bytes 02 20, then D, S, F and their XOR (address = D | S << 8)
            "Panasonic" -> {
                val d = (a and 0xFF).toInt(); val sd = ((a shr 8) and 0xFF).toInt(); val f = (c and 0xFF).toInt()
                kaseikyoFrame(intArrayOf(0x02, 0x20, d, sd, f, d xor sd xor f))
            }
            "SIRC" -> sirc((c and 0x7F) or ((a and 0x1F) shl 7), 12)
            "SIRC15" -> sirc((c and 0x7F) or ((a and 0xFF) shl 7), 15)
            "SIRC20" -> sirc((c and 0x7F) or ((a and 0x1FFF) shl 7), 20)
            "Kaseikyo" -> kaseikyo(a, c)
            "RCA" -> pulseDistance(
                38000, 4000, 4000, 500, 2000, 500, 1000, 500, 8000,
                bits = (a and 0xF) or ((c and 0xFF) shl 4) or ((a.inv() and 0xF) shl 12) or ((c.inv() and 0xFF) shl 16),
                count = 24,
            )
            "Pioneer" -> {
                // 32 data bits, a trailing 0 bit and a stop mark; Pioneer needs the frame sent twice.
                val bits = (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                pulseDistance(40000, 8500, 4225, 500, 1500, 500, 500, 500, 26000, bits = bits, count = 33, repeats = 2)
            }
            "RC5", "RC5X" -> rc5(s.protocol == "RC5X", a, c)
            "RC6" -> rc6(a, c)
            else -> null
        }
    }

    /** Generic pulse-distance frame: preamble, [count] bits LSB first, stop mark, then a gap padding the frame to [period]. */
    private fun pulseDistance(
        freq: Int, preMark: Int, preSpace: Int, b1Mark: Int, b1Space: Int, b0Mark: Int, b0Space: Int,
        stopMark: Int, period: Int, bits: Long, count: Int, repeats: Int = 1,
    ): Transmission {
        val p = IntList()
        repeat(repeats) {
            val start = p.size
            p.add(preMark); p.add(preSpace)
            for (i in 0 until count) {
                if ((bits ushr i) and 1L == 1L) { p.add(b1Mark); p.add(b1Space) } else { p.add(b0Mark); p.add(b0Space) }
            }
            p.add(stopMark)
            val used = p.sumFrom(start)
            p.add((period - used).coerceAtLeast(5000))
        }
        return Transmission(freq, p.toArray())
    }

    private fun sirc(bits: Long, count: Int): Transmission {
        val p = IntList()
        repeat(3) { // Sony devices expect the frame at least three times, 45 ms apart
            val start = p.size
            p.add(2400); p.add(600)
            for (i in 0 until count) {
                p.add(if ((bits ushr i) and 1L == 1L) 1200 else 600)
                p.add(600)
            }
            p.addToLast((45000 - p.sumFrom(start)).coerceAtLeast(0)) // stretch the trailing space to the 45 ms period
        }
        return Transmission(40000, p.toArray())
    }

    private fun kaseikyo(address: Long, command: Long): Transmission {
        val id = ((address shr 24) and 3).toInt()
        val vendor = ((address shr 8) and 0xFFFF).toInt()
        val genre1 = ((address shr 4) and 0xF).toInt()
        val genre2 = (address and 0xF).toInt()
        val d = IntArray(6)
        d[0] = vendor and 0xFF
        d[1] = vendor ushr 8
        var vp = d[0] xor d[1]
        vp = (vp and 0xF) xor (vp ushr 4)
        d[2] = (vp and 0xF) or (genre1 shl 4)
        d[3] = (genre2 and 0xF) or ((command.toInt() and 0xF) shl 4)
        d[4] = (id shl 6) or ((command.toInt() ushr 4) and 0x3F)
        d[5] = (d[2] xor d[3] xor d[4]) and 0xFF
        return kaseikyoFrame(d)
    }

    private fun kaseikyoFrame(d: IntArray): Transmission {
        var bits = 0L
        for (i in 0 until 6) bits = bits or ((d[i].toLong() and 0xFF) shl (8 * i))
        val u = 432
        return pulseDistance(37000, 8 * u, 4 * u, u, 3 * u, u, u, u, 130000, bits, 48)
    }

    /** Manchester helper: accumulates half-bit levels and merges equal neighbours into single durations. */
    private class Manchester(val unit: Int) {
        private val levels = ArrayList<Pair<Boolean, Int>>()
        fun half(mark: Boolean, units: Int = 1) {
            val last = levels.lastOrNull()
            if (last != null && last.first == mark) levels[levels.size - 1] = mark to (last.second + units * unit)
            else levels.add(mark to units * unit)
        }
        fun toPattern(leadMark: Int = 0, leadSpace: Int = 0): IntArray {
            val p = IntList()
            if (leadMark > 0) { p.add(leadMark); p.add(leadSpace) }
            var list = levels.toList()
            // pattern must start with a mark: drop a leading space into the previous gap
            if (list.isNotEmpty() && !list.first().first) list = list.drop(1)
            if (list.isNotEmpty() && list.last().first) list = list + (false to 5000)
            // ensure strict alternation starting with mark
            var expectMark = true
            for ((m, d) in list) {
                if (m == expectMark) { p.add(d); expectMark = !expectMark } else p.addToLast(d)
            }
            if (p.size % 2 == 1) p.add(5000)
            return p.toArray()
        }
    }

    private fun rc5(extended: Boolean, address: Long, command: Long): Transmission {
        val field = if (extended && (command and 0x40L) != 0L) 0 else 1 // RC5X: field bit is the inverted 7th command bit
        val bits = ArrayList<Int>()
        bits.add(1); bits.add(field); bits.add(0)
        for (i in 4 downTo 0) bits.add(((address shr i) and 1L).toInt())
        for (i in 5 downTo 0) bits.add(((command shr i) and 1L).toInt())
        val m = Manchester(889)
        for (b in bits) if (b == 1) { m.half(false); m.half(true) } else { m.half(true); m.half(false) }
        return Transmission(36000, m.toPattern())
    }

    private fun rc6(address: Long, command: Long): Transmission {
        val m = Manchester(444)
        fun bit(b: Int, units: Int = 1) { if (b == 1) { m.half(true, units); m.half(false, units) } else { m.half(false, units); m.half(true, units) } }
        bit(1)                       // start
        bit(0); bit(0); bit(0)       // mode 0
        bit(0, 2)                    // trailer (toggle) bit is double length
        for (i in 7 downTo 0) bit(((address shr i) and 1L).toInt())
        for (i in 7 downTo 0) bit(((command shr i) and 1L).toInt())
        return Transmission(36000, m.toPattern(leadMark = 2666, leadSpace = 889))
    }

    private class IntList {
        private var a = IntArray(256)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun addToLast(v: Int) { a[size - 1] += v }
        fun sumFrom(start: Int): Int { var s = 0; for (i in start until size) s += a[i]; return s }
        fun toArray() = a.copyOf(size)
    }
}
