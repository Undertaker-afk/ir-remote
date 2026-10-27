package app.irremote.ir

import app.irremote.web.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real keyless Exa endpoint; only runs with LIVE_EXA=1. */
class LiveExaTest {
    @Test fun liveLookup() {
        assumeTrue(System.getenv("LIVE_EXA") == "1")
        val f = File.createTempFile("live", ".txt").also { it.delete() }
        val r = WebLookup(f).lookup(ExaMcpProvider(), "Samsung", "UCU7000")
        println("LIVE RESULT [${r.provider}] confidence=${r.confidence}: " + r.votes.take(4).joinToString { "${it.part} x${it.hits} (${"%.2f".format(it.score)})" })
        f.delete()
    }
}
