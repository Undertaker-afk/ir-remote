package app.irremote.ir

import org.junit.Assert.*
import org.junit.Test

class IrEncoderTest {
    private fun enc(proto: String, a: Long, c: Long) = IrEncoder.encode(IrSignal.Parsed(proto, a, c))!!

    @Test fun necFrameLayout() {
        val t = enc("NEC", 0x04, 0x08)
        val p = t.pattern
        assertEquals(38000, t.frequency)
        assertEquals(9000, p[0]); assertEquals(4500, p[1])
        // address 0x04 sent LSB first: 0,0,1,0,0,0,0,0
        val bitSpaces = (0 until 8).map { p[3 + it * 2] }
        assertEquals(listOf(560, 560, 1690, 560, 560, 560, 560, 560), bitSpaces)
        assertEquals(2 + 64 + 1 + 1, p.size)
        assertEquals(110000, p.sum())
    }

    @Test fun necextSendsAllSixteenBitsWithoutInversion() {
        val p = enc("NECext", 0x87EE, 0x0000).pattern
        // 0xEE = 11101110 LSB first -> 0,1,1,1,0,1,1,1
        val first8 = (0 until 8).map { p[3 + it * 2] == 1690 }
        assertEquals(listOf(false, true, true, true, false, true, true, true), first8)
    }

    @Test fun samsungDuplicatesAddressAndInvertsCommand() {
        val p = enc("Samsung32", 0x07, 0x02).pattern
        assertEquals(4500, p[0]); assertEquals(4500, p[1])
        fun byteAt(n: Int) = (0 until 8).sumOf { i -> (if (p[3 + (n * 8 + i) * 2] == 1650) 1 else 0) shl i }
        assertEquals(listOf(0x07, 0x07, 0x02, 0xFD), (0..3).map { byteAt(it) })
    }

    @Test fun sircRepeatsThreeTimesAt45ms() {
        val p = enc("SIRC", 1, 21).pattern
        assertEquals(40000, enc("SIRC", 1, 21).frequency)
        assertEquals(3 * 45000, p.sum())
        assertEquals(3 * (2 + 24), p.size)
    }

    @Test fun rc5IsManchesterAndAlternates() {
        val t = enc("RC5", 0, 12)
        assertEquals(36000, t.frequency)
        assertEquals(0, t.pattern.size % 2)
        assertTrue(t.pattern.all { it > 0 })
        // 14 bits * 2 half-bits * 889 is the maximum frame; merging only shortens the count
        assertTrue(t.pattern.sum() in 14 * 1778 - 1778..14 * 1778 + 6000)
    }

    @Test fun rc6HasLeaderAndDoubleLengthTrailer() {
        val p = enc("RC6", 0x00, 0x0C).pattern
        assertEquals(2666, p[0]); assertEquals(889, p[1])
        assertEquals(0, p.size % 2)
    }

    @Test fun kaseikyoIs48BitsPlusPreambleAndStop() {
        val p = enc("Kaseikyo", 0x00200220, 0x3D).pattern // arbitrary Panasonic-style value
        assertEquals(2 + 96 + 1 + 1, p.size)
        assertEquals(130000, p.sum())
    }

    @Test fun everyDatabaseProtocolIsSupported() {
        for (proto in IrEncoder.PROTOCOLS) assertNotNull(proto, IrEncoder.encode(IrSignal.Parsed(proto, 1, 1)))
    }

    @Test fun rawTimingsPassThroughWithEvenLength() {
        val t = IrEncoder.encode(IrSignal.Raw(38000, 33, intArrayOf(9000, 4500, 560)))!!
        assertEquals(4, t.pattern.size)
    }

    @Test fun roundTripsCompactForm() {
        val s = IrSignal.decode("P:NECext:87ee:0")!!
        assertEquals("P:NECext:87ee:0", IrSignal.encode(s))
        val r = IrSignal.decode("R:38000:33:100,200,300,400")!!
        assertEquals("R:38000:33:100,200,300,400", IrSignal.encode(r))
    }

    @Test fun necxUsesFourAndHalfMsLeaderAndSeparateSubdevice() {
        val p = enc("NECx", 0x0507, 0x02).pattern // D=7, S=5
        assertEquals(4512, p[0]); assertEquals(4512, p[1])
        fun byteAt(n: Int) = (0 until 8).sumOf { i -> (if (p[3 + (n * 8 + i) * 2] == 1692) 1 else 0) shl i }
        assertEquals(listOf(0x07, 0x05, 0x02, 0xFD), (0..3).map { byteAt(it) })
    }

    @Test fun panasonicStartsWithVendorBytesAndChecksum() {
        val p = enc("Panasonic", 0x0080, 0x01).pattern // D=0x80, S=0, F=1
        fun byteAt(n: Int) = (0 until 8).sumOf { i -> (if (p[3 + (n * 8 + i) * 2] == 1296) 1 else 0) shl i }
        assertEquals(listOf(0x02, 0x20, 0x80, 0x00, 0x01, 0x81), (0..5).map { byteAt(it) })
    }

    @Test fun jvcIsSixteenBits() {
        assertEquals(2 + 32 + 1 + 1, enc("JVC", 3, 5).pattern.size)
    }

    @Test fun irdbProtocolsProduceWellFormedFrames() {
        for (proto in listOf("Mitsubishi", "Aiwa", "Proton", "Akai", "Fujitsu", "RCA38", "PanasonicOld", "Pioneer40", "DenonK", "Denon", "Sharp", "MCE")) {
            val t = enc(proto, 0x05, 0x12)
            assertTrue(proto, t.pattern.size % 2 == 0 && t.pattern.all { it > 0 } && t.pattern.size > 4)
        }
    }

    @Test fun denonSendsKeyThenInvertedCopyEvery67ms() {
        val p = enc("Denon", 0x02, 0x34).pattern
        assertEquals(2 * 67000, p.sum())
        assertEquals(2 * (15 * 2 + 2), p.size)
    }

    @Test fun mitsubishiHasNoHeaderAndSixteenBits() {
        val p = enc("Mitsubishi", 1, 2).pattern
        assertEquals(16 * 2 + 2, p.size)
        assertEquals(300, p[0])
    }

    @Test fun denonKChecksum() {
        // D=1 S=2 F=0x123: ((D<<4) ^ S ^ (F<<4) ^ (F>>4)) & 0xFF
        val expected = ((1 shl 4) xor 2 xor (0x123 shl 4) xor (0x123 shr 4)) and 0xFF
        val p = enc("DenonK", 1 or (2 shl 4), 0x123).pattern
        val bits = (0 until 48).map { i -> if (p[3 + i * 2] == 3 * 432) 1 else 0 }
        assertEquals(expected, (40 until 48).sumOf { bits[it] shl (it - 40) })
    }
}
