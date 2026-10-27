package app.irremote.ir

import app.irremote.data.FlipperIr
import app.irremote.data.Slot
import org.junit.Assert.*
import org.junit.Test

class FlipperIrTest {
    private val sample = """
        Filetype: IR signals file
        Version: 1
        #
        name: Power
        type: parsed
        protocol: Samsung32
        address: 07 00 00 00
        command: 02 00 00 00
        #
        name: Vol_up
        type: parsed
        protocol: NECext
        address: EE 87 00 00
        command: 0B F4 00 00
        #
        name: Cool_hi
        type: raw
        frequency: 38000
        duty_cycle: 0.330000
        data: 3500 1700 400 1300
    """.trimIndent()

    @Test fun parsesParsedAndRawSignals() {
        val r = FlipperIr.parse(sample, "Samsung", "Test")
        assertEquals(3, r.buttons.size)
        assertEquals(IrSignal.Parsed("Samsung32", 7, 2), r.buttons[0].signal)
        assertEquals(IrSignal.Parsed("NECext", 0x87EE, 0xF40B), r.buttons[1].signal)
        val raw = r.buttons[2].signal as IrSignal.Raw
        assertEquals(33, raw.dutyPercent)
        assertEquals(4, raw.timings.size)
    }

    @Test fun mapsNamesToRemoteSlots() {
        val r = FlipperIr.parse(sample, "Samsung", "Test")
        assertEquals("Power", r.find(Slot.POWER)?.name)
        assertEquals("Vol_up", r.find(Slot.VOL_UP)?.name)
        assertNull(r.find(Slot.MUTE))
        assertEquals(listOf("Cool_hi"), r.extraButtons().map { it.name })
    }
}

class ModelMatcherTest {
    private val t = { s: String -> app.irremote.data.ModelMatcher.tokens(s) }
    private val score = { b: String, m: String, s: String -> app.irremote.data.ModelMatcher.score(b, m, t(s)) }

    @Test fun readsStickerTokens() {
        assertEquals(listOf("SAMSUNG", "AA5900443A", "UE40D5000"), t("Samsung\nAA59-00443A  UE40D5000 ..").take(3))
    }

    @Test fun modelCodeOutranksBrand() {
        val exact = score("Samsung", "Samsung AA59-00443A", "AA59-00443A SAMSUNG")
        val brandOnly = score("Samsung", "Samsung UE40D5000", "AA59-00443A SAMSUNG")
        assertTrue(exact > brandOnly)
        assertEquals(0, score("LG", "LG 42LB", "AA59-00443A SAMSUNG"))
    }

    @Test fun longerStickerCodeContainsDatabaseModel() {
        assertTrue(score("Samsung", "Samsung UE40D5000", "UE40D5000AWXXC") > 0)
    }
}
