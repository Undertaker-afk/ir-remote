package app.irremote.ir

import app.irremote.ir.ac.*
import org.junit.Assert.*
import org.junit.Test

/** Expected values come from IRremoteESP8266's unit tests (real remote captures). */
class AcTest {
    @Test fun greeMatchesRealCapture() {
        // Cool, 26C, fan low, swing position 2, display temp "outside", light on
        val d = Gree.bytes(power = true, mode = 1, temp = 26, fan = 1, swingAuto = false, swingV = 2, displayTemp = 3)
        assertArrayEquals(intArrayOf(0x19, 0x0A, 0x60, 0x50, 0x02, 0x23, 0x00, 0xF0), d)
    }

    @Test fun greeFrameShape() {
        val p = Gree.encode(AcState(true, AcMode.COOL, 24, AcFan.AUTO, false)).pattern
        assertEquals(9000, p[0]); assertEquals(4500, p[1])
        // header(2) + 32 bits*2 + footer 3 bits*2 + mark/space(2) + 32 bits*2 + mark/space(2)
        assertEquals(2 + 64 + 6 + 2 + 64 + 2, p.size)
    }

    @Test fun coolixKnownCodes() {
        assertEquals(0xB2BF40, Coolix.code(AcState(true, AcMode.COOL, 24, AcFan.AUTO)))
        assertEquals(0xB27BE0, Coolix.code(AcState(false)))
        assertEquals(0xB21FC8, Coolix.code(AcState(true, AcMode.AUTO, 25, AcFan.AUTO)))
    }

    @Test fun coolixSendsEachByteThenItsComplement() {
        val p = Coolix.encode(AcState(true, AcMode.COOL, 24, AcFan.AUTO)).pattern
        assertEquals(4692, p[0]); assertEquals(4416, p[1])
        assertEquals(2 + 96 + 2, p.size) // header + 48 bits + footer
    }

    @Test fun mideaDefaultStateAndChecksum() {
        // 77F/25C, auto mode, fan auto, power on is the reference library's reset state 0xA1826FFFFF62
        val b = Midea.bytes(AcState(true, AcMode.AUTO, 25, AcFan.AUTO))
        assertArrayEquals(intArrayOf(0xA1, 0x82, 0x6F, 0xFF, 0xFF, 0x62), b)
        assertArrayEquals(intArrayOf(0xA1, 0x02, 0x6F, 0xFF, 0xFF, 0xE2), Midea.bytes(AcState(false, AcMode.AUTO, 25, AcFan.AUTO)))
        for ((mode, expected) in listOf(AcMode.COOL to 0xA1806FFFFF61, AcMode.HEAT to 0xA1836FFFFF63, AcMode.DRY to 0xA1816FFFFF60, AcMode.FAN to 0xA1846FFFFF66)) {
            val bytes = Midea.bytes(AcState(true, mode, 25, AcFan.AUTO))
            assertEquals(mode.name, expected, bytes.fold(0L) { acc, v -> (acc shl 8) or v.toLong() })
        }
    }

    @Test fun mideaSendsInvertedCopy() {
        val p = Midea.encode(AcState(true, AcMode.COOL, 24, AcFan.AUTO)).pattern
        assertEquals(2 * (2 + 96 + 2), p.size)
    }

    @Test fun lgKnownCodes() {
        assertEquals(0x8800A4E, LgAc.code(AcState(true, AcMode.COOL, 25, AcFan.HIGH)))
        assertEquals(0x880094D, LgAc.code(AcState(true, AcMode.COOL, 24, AcFan.HIGH)))
        assertEquals(0x88C0051, LgAc.code(AcState(false)))
    }

    @Test fun stateSerializesAndOrdersProtocolsByBrand() {
        val s = AcState(true, AcMode.HEAT, 21, AcFan.MEDIUM, true)
        assertEquals(s, AcState.parse(s.serialize()))
        assertEquals("lgac", AcProtocols.orderedFor("LG").first().id)
        assertEquals(AcProtocols.all.size, AcProtocols.orderedFor("Unknown brand").size)
    }

    @Test fun allProtocolsProducePositiveAlternatingPatterns() {
        for (p in AcProtocols.all) for (m in p.modes) for (on in listOf(true, false)) {
            val t = p.encode(AcState(on, m, p.minTemp, AcFan.HIGH, true)).pattern
            assertTrue(p.id, t.size % 2 == 0 && t.all { it > 0 })
        }
    }
}
