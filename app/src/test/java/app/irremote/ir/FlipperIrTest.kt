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

class ImportFormatsTest {
    private fun parse(text: String) = app.irremote.data.ImportFormats.parse(text, "Test")

    @Test fun prontoConvertsToRawTimings() {
        // classic NEC-ish Pronto: 0000 006D = 38.0 kHz, 22 once-pairs
        val code = "0000 006D 001F 0000 0157 00AC 0015 0015 0015 0040 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0040 0015 0040 0015 0015 0015 0015 0015 0040 0015 0040 0015 0040 0015 0015 0015 0015 0015 0015 0015 0040 0015 0015 0015 0015 0015 0015 0015 0040 0015 0040 0015 0015 0015 0040 0015 0040 0015 0040 0015 0040 0015 0689"
        val r = parse("Power: $code\nVolume Up: $code")
        assertEquals(listOf("Power", "Volume Up"), r.single().buttons.map { it.name })
        val raw = r.single().buttons[0].signal as IrSignal.Raw
        assertEquals(38000.0, raw.frequency.toDouble(), 200.0)
        assertEquals(62, raw.timings.size)
        assertEquals(9000.0, raw.timings[0].toDouble(), 150.0)   // 0x157 cycles of 26.2 us
        assertEquals(4500.0, raw.timings[1].toDouble(), 100.0)
    }

    @Test fun bareProntoCodeBecomesOneButton() {
        val r = parse("0000 006D 0002 0000 0157 00AC 0015 0015")
        assertEquals("Button", r.single().buttons.single().name)
    }

    @Test fun rawDumpFromArduino() {
        val r = parse("uint16_t rawData[7] = {9000, 4500, 560, 560, 560, 1690, 560};  // NEC")
        assertEquals(7, (r.single().buttons.single().signal as IrSignal.Raw).timings.size)
    }

    @Test fun irdbCsvUsesTheSharedMapping() {
        val csv = "functionname,protocol,device,subdevice,function\nPOWER,NEC1,7,-1,2\nVOL+,Sony12,1,-1,18\nBAD,Foo,1,1,1"
        val r = parse(csv).single()
        assertEquals(listOf("Power", "Vol+"), r.buttons.map { it.name })
        assertEquals(IrSignal.Parsed("NECext", 0xF807, 0xFD02), r.buttons[0].signal)
        assertEquals(IrSignal.Parsed("SIRC", 1, 18), r.buttons[1].signal)
    }

    @Test fun lircSpaceEncodedBecomesRawFrame() {
        val conf = """
            begin remote
              name  samsung_tv
              bits 16
              flags SPACE_ENC|CONST_LENGTH
              header 4500 4500
              one 550 1650
              zero 550 550
              ptrail 550
              pre_data_bits 16
              pre_data 0xE0E0
              gap 108000
              begin codes
                KEY_POWER 0x40BF
              end codes
            end remote
        """.trimIndent()
        val r = parse(conf).single()
        assertEquals("samsung_tv", r.model)
        val raw = r.buttons.single().signal as IrSignal.Raw
        assertEquals(4500, raw.timings[0]); assertEquals(4500, raw.timings[1])
        assertEquals(108000, raw.timings.sum())          // CONST_LENGTH pads the frame
        assertEquals(2 + 64 + 1 + 1, raw.timings.size)   // header + 32 bits + trail + gap
        // 0xE0 = 11100000 MSB first -> 1,1,1,0,0,0,0,0
        val firstBits = (0 until 8).map { raw.timings[3 + it * 2] == 1650 }
        assertEquals(listOf(true, true, true, false, false, false, false, false), firstBits)
    }

    @Test fun lircRawCodes() {
        val conf = "begin remote\n name x\n begin raw_codes\n name POWER\n 9000 4500 560 560 560 1690 560\n end raw_codes\nend remote"
        val r = parse(conf).single()
        assertEquals("POWER", r.buttons.single().name)
    }

    @Test fun unknownTextIsRejected() {
        assertTrue(parse("hello world, nothing to see").isEmpty())
    }
}
