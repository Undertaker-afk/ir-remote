package app.irremote.ir

import app.irremote.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemoteLintTest {
    private fun remote(model: String, vararg keys: Pair<String, String>) = IrRemote("Samsung", "Samsung $model", keys.map { IrButton(it.first, "P:Samsung32:7:${it.second}") })

    @Test fun flagsCollapsedDirectionKeysAndIdenticalUpDown() {
        assertTrue(RemoteLint.broken(remote("AA59-00741A", "Up" to "60", "Down" to "60", "Left" to "60", "Right" to "60")))
        assertTrue(RemoteLint.broken(remote("X", "Ch_next" to "10", "Ch_prev" to "10")))
        assertTrue(RemoteLint.broken(remote("X", "Vol_up" to "7", "Vol_dn" to "7")))
    }

    @Test fun keepsDualFunctionKeysAndHealthyRemotes() {
        // arrows that double as volume/channel share codes with Vol/Ch on many TVs; that is legitimate
        assertFalse(RemoteLint.broken(remote("TV", "Up" to "60", "Down" to "61", "Left" to "65", "Right" to "62", "Vol_up" to "62", "Vol_dn" to "65", "Ch_next" to "60", "Ch_prev" to "61")))
    }

    @Test fun curatedRemoteReplacesTheDatabaseEntryWithTheSamePartNumber() {
        val broken = remote("BN59-01388", "Ok" to "62", "Right" to "62", "Vol_up" to "12", "Ch_next" to "7")
        val other = remote("AA59-00443A", "Ok" to "68")
        val curated = remote("BN59-01388 TM2240A Crystal UHD (CU7000)", "Ok" to "68", "Vol_up" to "7", "Ch_next" to "12")
        val merged = DbMerge.merge(listOf(broken, other), listOf(curated))
        assertEquals(listOf(other, curated), merged)
    }

    /** On the real data: the corrected BN59-01388 wins, obviously corrupt entries are gone, healthy ones are kept. */
    @Test fun realDatabaseAfterMerge() {
        val db = File("src/main/assets/db/tv.txt"); val cur = File("src/main/assets/curated/tv.txt")
        org.junit.Assume.assumeTrue(db.exists() && cur.exists())
        val raw = parseRemotes(db.readLines())
        val merged = DbMerge.merge(raw, parseRemotes(cur.readLines()))
        val bn = merged.filter { it.model.contains("BN59-01388") }
        assertEquals(1, bn.size)
        assertEquals("P:Samsung32:7:68", bn[0].find(Slot.OK)!!.encoded)       // OK is no longer a copy of Right
        assertEquals("P:Samsung32:7:7", bn[0].find(Slot.VOL_UP)!!.encoded)    // volume and channel are the right way round
        assertEquals("P:Samsung32:7:12", bn[0].find(Slot.CH_UP)!!.encoded)
        val dropped = raw.filter { RemoteLint.broken(it) }
        println("LINT: dropped ${dropped.size} of ${raw.size}: " + dropped.joinToString { it.model.take(30) })
        assertTrue(dropped.size in 1..40)
        assertTrue(merged.none { RemoteLint.broken(it) })
    }
}
