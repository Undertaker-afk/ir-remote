package app.irremote.ir

import app.irremote.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MatchEngineTest {
    private fun remote(brand: String, model: String, power: String = "P:NEC:1:2") =
        IrRemote(brand, "$brand $model", listOf(IrButton("Power", power)))

    @Test fun parsesSamsungLgSonyPhilips() {
        val s = ModelParser.parse("UE43CU7000AUXXH", "SAMSUNG")!!
        assertEquals(43, s.size); assertEquals("CU7000", s.core); assertEquals("AUXXH", s.suffix); assertEquals(listOf(2023), s.years)
        assertEquals("CU7000", ModelParser.parse("UCU7000", "SAMSUNG")!!.core)      // what Samsung TVs announce over AirPlay
        assertEquals(listOf(2011), ModelParser.parse("UE40D5000", "SAMSUNG")!!.years)
        assertEquals(listOf(2015), ModelParser.parse("UN60JU6500", "SAMSUNG")!!.years)
        val lg = ModelParser.parse("43UP7500PSF", "LG")!!
        assertEquals("UP7500", lg.core); assertEquals(listOf(2021), lg.years)
        val sony = ModelParser.parse("KD-43X80J", "SONY")!!
        assertEquals(43, sony.size); assertEquals("X80", sony.core); assertEquals(listOf(2021), sony.years)
        assertEquals("PUS7906", ModelParser.parse("43PUS7906/12", "PHILIPS")!!.core)
        assertEquals("S446", ModelParser.parse("TCL 43S446".split(' ')[1], "TCL")!!.core)
    }

    @Test fun partNumbersIgnoreTheRevisionLetter() {
        assertEquals("BN5901315" to "B", ModelParser.partKey("BN59-01315B"))
        assertEquals(ModelParser.partKey("BN59-01315B")!!.first, ModelParser.partKey("BN59-01315J")!!.first)
        assertNull(ModelParser.partKey("UE43CU7000"))
    }

    private val engine = MatchEngine(listOf(
        Category.TV to remote("Samsung", "UE40D5000"),
        Category.TV to remote("Samsung", "UN60JU6500"),
        Category.TV to remote("Samsung", "AA59-00443A"),
        Category.TV to remote("Samsung", "BN59-01315B"),
        Category.TV to remote("Samsung", "Crystal UHD smart remote CU7000 AU7000 BU8000"),
        Category.TV to remote("LG", "43UP7500PSF"),
        Category.TV to remote("Sony", "KD-55X80CK"),
    ))

    @Test fun differentSizeAndRegionStillMatchesTheSeries() {
        val m = engine.match("Samsung UE55D5000XXC").first()
        assertEquals("UE40D5000", m.remote.model.removePrefix("Samsung "))
        assertEquals(Tier.SAME_SERIES, m.tier)
    }

    @Test fun airPlayModelFindsTheCrystalUhdRemote() {
        val m = engine.match("43\" Crystal UHD Samsung UCU7000").first()
        assertTrue(m.remote.model.contains("Crystal UHD"))
        assertEquals(Tier.SAME_SERIES, m.tier)
    }

    @Test fun remoteRevisionIsRecognised() {
        val m = engine.match("Samsung BN59-01315J").first()
        assertEquals("Samsung BN59-01315B", m.remote.model)
        assertEquals(Tier.SAME_REMOTE, m.tier)
        assertEquals(Tier.EXACT, engine.match("BN59-01315B").first().tier)
    }

    @Test fun otherBrandsAreNotOffered() {
        val top = engine.match("LG 50UP7500PSA").first()
        assertEquals("LG", top.remote.brand)
        assertTrue(engine.match("LG 50UP7500PSA").none { it.remote.brand == "Samsung" && it.tier <= Tier.SAME_SERIES })
    }

    @Test fun unknownModelFallsBackToBrandOnly() {
        val r = engine.match("Samsung QZ99ZZ9999")
        assertTrue(r.isNotEmpty() && r.all { it.tier == Tier.BRAND || it.tier == Tier.SAME_ERA || it.tier == Tier.SAME_FAMILY })
    }

    @Test fun generationFallbackUsesNearbyYearsPowerCodes() {
        val e = MatchEngine(listOf(
            Category.TV to remote("Samsung", "UN60JU6500", power = "P:Samsung32:7:e6"),
            Category.TV to remote("Samsung", "UE40D5000", power = "P:Samsung32:7:2"),
            Category.TV to remote("Samsung", "AA59-00001A", power = "P:Samsung32:7:e6"),
            Category.TV to remote("Samsung", "AA59-00002A", power = "P:Samsung32:7:2"),
        ))
        // a 2016 model (K) is closest to the 2015 JU6500, so the remote sharing its power code ranks first among brand-only matches
        val r = e.match("Samsung UE49KU6400")
        val era = r.filter { it.tier == Tier.SAME_ERA }
        assertEquals(setOf("Samsung UN60JU6500", "Samsung AA59-00001A"), era.map { it.remote.model }.toSet())
    }

    /** Measures the engine on the real database: a model typed with a different size/region must find its own series. */
    @Test fun accuracyOnTheBundledTvDatabase() {
        val f = File("src/main/assets/db/tv.txt")
        org.junit.Assume.assumeTrue(f.exists())
        val remotes = ArrayList<IrRemote>(); var brand = ""; var model = ""; var buttons = ArrayList<IrButton>()
        fun flush() { if (brand.isNotEmpty() && buttons.isNotEmpty()) remotes.add(IrRemote(brand, model, buttons)); buttons = ArrayList() }
        f.forEachLine { l -> if (l.startsWith("@")) { flush(); val t = l.substring(1).split('\t'); brand = t[0]; model = t.getOrElse(1) { "" } } else { val t = l.split('\t', limit = 2); if (t.size == 2) buttons.add(IrButton(t[0], t[1])) } }
        flush()
        val e = MatchEngine(remotes.map { Category.TV to it })
        var cases = 0; var top1 = 0; var top3 = 0
        for (r in remotes) {
            val bk = ModelParser.norm(r.brand)
            val target = r.model.removePrefix(r.brand).split(Regex("[\\s,;()]+")).mapNotNull { t -> ModelParser.parse(t, bk) }
                .firstOrNull { it.size != null && it.letters.isNotEmpty() } ?: continue
            val query = "${r.brand} ${target.size!! + 2}${target.letters}${target.digits}ZZ"
            val res = e.match(query, limit = 3)
            cases++
            fun sameSeries(m: Match) = m.remote.model.removePrefix(m.remote.brand).split(Regex("[\\s,;()]+")).any { ModelParser.parse(it, bk)?.core == target.core }
            if (res.firstOrNull()?.let(::sameSeries) == true) top1++
            if (res.any(::sameSeries)) top3++
        }
        println("MATCH ACCURACY: cases=$cases top1=$top1 top3=$top3")
        assertTrue("too few cases", cases >= 50)
        // part numbers: the same remote with another revision letter must come back first, flagged as the same remote
        var parts = 0; var partHits = 0
        for (r in remotes) {
            val pk = r.model.split(Regex("[\\s,;()]+")).mapNotNull { t -> ModelParser.partKey(t)?.let { t to it } }.firstOrNull() ?: continue
            if (pk.second.second.isEmpty()) continue
            val other = if (pk.second.second == "Z") "Y" else "Z"
            val res = e.match("${r.brand} ${pk.first.dropLast(1)}$other", limit = 5)
            parts++
            if (res.any { it.remote === r && it.tier == Tier.SAME_REMOTE }) partHits++
        }
        println("PART NUMBER ACCURACY: cases=$parts hits=$partHits")
        assertTrue("part-number cases", parts >= 10)
        assertTrue("part-number accuracy", partHits.toDouble() / parts >= 0.95)
        assertTrue("top-1 accuracy ${top1.toDouble() / cases}", top1.toDouble() / cases >= 0.95)
        assertTrue("top-3 accuracy ${top3.toDouble() / cases}", top3.toDouble() / cases >= 0.98)
    }

    private fun loadRemotes(vararg files: String): List<IrRemote> {
        val remotes = ArrayList<IrRemote>(); var brand = ""; var model = ""; var buttons = ArrayList<IrButton>()
        fun flush() { if (brand.isNotEmpty() && buttons.isNotEmpty()) remotes.add(IrRemote(brand, model, buttons)); buttons = ArrayList() }
        for (path in files) {
            File(path).forEachLine { l -> if (l.startsWith("@")) { flush(); val t = l.substring(1).split('\t'); brand = t[0]; model = t.getOrElse(1) { "" } } else { val t = l.split('\t', limit = 2); if (t.size == 2) buttons.add(IrButton(t[0], t[1])) } }
            flush()
        }
        return remotes
    }

    /** The real case: a Samsung 43" Crystal UHD announces model "UCU7000" over AirPlay. */
    @Test fun crystalUhdOverAirPlayFindsTheBn5901388RemoteFirst() {
        val f = File("src/main/assets/curated/tv.txt")
        org.junit.Assume.assumeTrue(f.exists())
        val e = MatchEngine(loadRemotes("src/main/assets/db/tv.txt", "src/main/assets/curated/tv.txt").map { Category.TV to it })
        val top = e.match("Samsung UCU7000 43\" Crystal UHD AirPlay", limit = 5)
        println("TOP: " + top.map { "${it.remote.model.take(40)} [${it.tier}]" })
        assertTrue(top.first().remote.model.contains("BN59-01388"))
        assertEquals(Tier.SAME_SERIES, top.first().tier)
        // the 2019-2022 Crystal UHD remote must not be offered as a CU7000 series match
        assertTrue(top.none { it.remote.model.contains("2019-2022") && it.tier <= Tier.SAME_SERIES })
        // the same TV typed with its full model number and region suffix
        assertTrue(e.match("UE43CU7000UXZG", limit = 3).first().remote.model.contains("BN59-01388"))
    }

    /** The Panasonic and MagentaTV remotes added as curated entries are found by part number / name. */
    @Test fun curatedPanasonicAndMagentaRemotesAreFindable() {
        val tv = File("src/main/assets/curated/tv.txt"); val st = File("src/main/assets/curated/settop.txt")
        org.junit.Assume.assumeTrue(tv.exists() && st.exists())
        val e = MatchEngine((loadRemotes("src/main/assets/db/tv.txt", "src/main/assets/curated/tv.txt").map { Category.TV to it }) +
            loadRemotes("src/main/assets/db/settop.txt", "src/main/assets/curated/settop.txt").map { Category.SETTOP to it })
        val pan = e.match("Panasonic N2QAYB000572 10704B", limit = 3).first()
        assertTrue(pan.remote.model.contains("N2QAYB000572")); assertEquals(Tier.EXACT, pan.tier)
        assertEquals("P:Kaseikyo:200280:3d0", pan.remote.find(Slot.POWER)!!.encoded)
        val mag = e.match("Telekom MagentaTV MR401", limit = 3).first()
        assertTrue(mag.remote.model.contains("MagentaTV"))
        assertTrue(mag.remote.find(Slot.OK) != null)                       // OK is present, unlike the old raw capture
        assertEquals(3, mag.remote.buttons.count { it.name in setOf("Up", "Down", "Left") })
    }

    /** The label inside the user's MagentaTV remote ("SRC-3512 1-B") resolves to the MagentaTV One remote entry. */
    @Test fun magentaTvOneRemoteIsFoundBySrc3512() {
        val st = File("src/main/assets/curated/settop.txt")
        org.junit.Assume.assumeTrue(st.exists())
        val e = MatchEngine(loadRemotes("src/main/assets/db/settop.txt", "src/main/assets/curated/settop.txt").map { Category.SETTOP to it })
        val top = e.match("SRC-3512 1-B", limit = 3).first()
        assertTrue(top.remote.model.contains("SRC-3512")); assertEquals(Tier.EXACT, top.tier)
        assertTrue(top.remote.find(Slot.OK) != null && top.remote.find(Slot.POWER) != null)
    }
}
