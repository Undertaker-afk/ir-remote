package app.irremote.ir

import app.irremote.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KeyRepairTest {
    private fun r(model: String, vararg k: Pair<String, String>) =
        IrRemote("Samsung", "Samsung $model", k.map { IrButton(it.first, "P:Samsung32:7:${it.second}") })

    private val healthy = (1..6).map { i ->
        r("PEER$i", "Ok" to "68", "Up" to "60", "Down" to "61", "Left" to "65", "Right" to "62", "Vol_up" to "7", "Vol_dn" to "b", "Ch_next" to "12", "Ch_prev" to "10")
    }

    @Test fun suggestsTheCodeOtherRemotesAgreeOn() {
        val broken = r("BROKEN", "Ok" to "62", "Right" to "62", "Up" to "60")
        val s = KeyRepair.suggest(broken, "Ok", healthy)
        assertEquals("P:Samsung32:7:68", s.first().encoded)
        assertEquals(6, s.first().votes)
        assertNull(s.first().swapWith)
    }

    @Test fun detectsASwappedVolumeAndChannelPair() {
        val broken = r("BROKEN", "Vol_up" to "12", "Ch_next" to "7", "Vol_dn" to "10", "Ch_prev" to "b")
        val s = KeyRepair.suggest(broken, "Vol_up", healthy)
        assertEquals("P:Samsung32:7:7", s.first().encoded)
        assertEquals("Ch_next", s.first().swapWith)
    }

    @Test fun staysQuietWithTooFewSimilarRemotes() {
        assertTrue(KeyRepair.suggest(r("X", "Ok" to "62"), "Ok", healthy.take(2)).isEmpty())
        assertTrue(KeyRepair.suggest(r("X", "Ok" to "68"), "Ok", healthy).isEmpty()) // already the consensus code
    }

    @Test fun reportsWhatWasWrongAndWhatWorked() {
        val broken = r("BROKEN", "Ok" to "62")
        val text = KeyRepair.report(broken, broken.buttons[0], KeyRepair.suggest(broken, "Ok", healthy).first())
        assertTrue(text.contains("Ok") && text.contains("P:Samsung32:7:62") && text.contains("P:Samsung32:7:68") && text.contains("6 other remotes"))
    }

    /** The real broken database entry: OK and the volume/channel pair are found and fixed from the other Samsung remotes. */
    @Test fun fixesTheRealBn5901388Entry() {
        val f = File("src/main/assets/db/tv.txt")
        org.junit.Assume.assumeTrue(f.exists())
        val all = parseRemotes(f.readLines())
        val broken = all.first { it.brand == "Samsung" && it.model.endsWith("BN59-01388") }
        val ok = KeyRepair.suggest(broken, "Enter", all)
        println("REAL FIX Enter -> " + ok.map { "${it.encoded} x${it.votes}" })
        assertEquals("P:Samsung32:7:68", ok.first().encoded)
        val vol = KeyRepair.suggest(broken, "Vol_up", all)
        println("REAL FIX Vol_up -> " + vol.map { "${it.encoded} x${it.votes} swap=${it.swapWith}" })
        assertEquals("P:Samsung32:7:7", vol.first().encoded)
        assertEquals("Ch_next", vol.first().swapWith)
    }
}
