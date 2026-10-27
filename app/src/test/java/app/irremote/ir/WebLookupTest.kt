package app.irremote.ir

import app.irremote.data.*
import app.irremote.web.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WebLookupTest {
    private val sse get() = javaClass.getResource("/exa_cu7000.sse")!!.readText()

    @Test fun parsesARealExaResponse() {
        val hits = ExaMcpProvider.parseResponse(sse)
        assertEquals(5, hits.size)
        assertTrue(hits[0].url.startsWith("https://paulstv.com/"))
        assertTrue(hits[0].title.contains("BN59-01388A"))
        assertTrue(hits.any { it.title.isEmpty() }) // "Title: N/A" pages keep their text but get no title
    }

    @Test fun votesRankTheRealCu7000RemoteFirstAndIgnoreCatalogueNoise() {
        val votes = PartVoting.vote(ExaMcpProvider.parseResponse(sse), "UE43CU7000")
        assertEquals("BN5901388", votes[0].base)
        assertTrue(votes[0].hits >= 3)
        assertTrue(votes.map { it.base }.containsAll(listOf("BN5901393", "BN5901389")))
        // the shop catalogue lists a dozen unrelated remotes; they must not outrank real evidence
        val noise = votes.firstOrNull { it.base == "BN5901301" }
        assertTrue(noise == null || noise.score < votes[0].score / 3)
        // 01388, 01393 and 01389 are all genuine candidates for this series, so the answer is "likely", not "certain"
        assertEquals(PartVoting.Confidence.MEDIUM, PartVoting.confidence(votes))
    }

    @Test fun aClearWinnerIsHighConfidence() {
        val hits = listOf(
            SearchHit("Samsung BN59-01388A remote for CU7000", "https://a", "BN59-01388A fits CU7000"),
            SearchHit("BN59-01388H remote Samsung CU7000", "https://b", "BN59-01388H CU7000 series"),
            SearchHit("BN59-01388E for CU7000", "https://c", "works with CU7000"),
        )
        assertEquals(PartVoting.Confidence.HIGH, PartVoting.confidence(PartVoting.vote(hits, "CU7000")))
    }

    @Test fun modelCoreUsesTheSeriesNotTheSizeOrRegion() {
        assertEquals("CU7000", PartVoting.modelCore("UE43CU7000AUXXH"))
        assertEquals("CU7000", PartVoting.modelCore("UCU7000"))
    }

    @Test fun pagesNotTiedToTheModelAreNotEvidence() {
        val hits = listOf(SearchHit("Samsung remotes", "https://x/1", "BN59-01111A fits many models. " + "lorem ".repeat(200) + " unrelated CU7000 mention far away"))
        assertTrue(PartVoting.vote(hits, "UE43CU7000").isEmpty())
    }

    @Test fun otherProvidersParseTheirDocumentedFormats() {
        val tav = TavilyProvider.parse("""{"results":[{"title":"BN59-01388A remote","url":"https://a","content":"for CU7000","score":0.9}]}""")
        assertEquals("https://a", tav.single().url)
        val brave = BraveProvider.parse("""{"web":{"results":[{"title":"t","url":"https://b","description":"d","extra_snippets":["e1","e2"]}]}}""")
        assertTrue(brave.single().text.contains("e2"))
        val searx = SearxProvider.parse("""{"results":[{"title":"t","url":"https://c","content":"c"}]}""")
        assertEquals("https://c", searx.single().url)
        assertThrows(Exception::class.java) { ExaMcpProvider.parseResponse("""data: {"error":{"message":"rate limit"}}""") }
    }

    @Test fun lookupUsesTheProviderThenTheCache() {
        var calls = 0
        val fake = object : SearchProvider {
            override val id = "fake"
            override fun search(query: String, objective: String, limit: Int): List<SearchHit> { calls++; return ExaMcpProvider.parseResponse(sse) }
        }
        val f = File.createTempFile("weblookup", ".txt").also { it.delete() }
        var now = 1_000_000L
        val lookup = WebLookup(f, { now })
        val first = lookup.lookup(fake, "Samsung", "UCU7000")
        assertFalse(first.cached); assertEquals("BN5901388", first.votes[0].base); assertEquals(2, calls)
        val second = lookup.lookup(fake, "Samsung", "UCU7000")
        assertTrue(second.cached); assertEquals(2, calls); assertEquals(first.votes[0].part, second.votes[0].part)
        now += 31L * 24 * 3600 * 1000
        assertFalse(lookup.lookup(fake, "Samsung", "UCU7000").cached) // cache expires after 30 days
        f.delete()
    }

    @Test fun webEvidenceFindsTheDatabaseRemote() {
        // the database has "Samsung BN59-01388"; the web gave BN59-01388A for the CU7000 series
        val remote = IrRemote("Samsung", "Samsung BN59-01388", listOf(IrButton("Power", "P:Samsung32:7:e6")))
        val other = IrRemote("Samsung", "Samsung AA59-00443A", listOf(IrButton("Power", "P:Samsung32:7:2")))
        val engine = MatchEngine(listOf(Category.TV to other, Category.TV to remote))
        val votes = PartVoting.vote(ExaMcpProvider.parseResponse(sse), "UE43CU7000")
        val m = engine.match("Samsung UCU7000 " + votes.take(3).joinToString(" ") { it.part }).first()
        assertSame(remote, m.remote)
        assertEquals(Tier.SAME_REMOTE, m.tier)
    }
}
