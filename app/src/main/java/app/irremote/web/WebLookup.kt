package app.irremote.web

import java.io.File

class LookupResult(val votes: List<PartVote>, val confidence: PartVoting.Confidence, val provider: String, val cached: Boolean)

/** Finds the remote part number for a model with a [SearchProvider] and remembers answers on disk (30 days). */
class WebLookup(private val cacheFile: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val ttl = 30L * 24 * 3600 * 1000

    fun lookup(provider: SearchProvider, brand: String, model: String): LookupResult {
        val key = (brand.trim() + " " + model.trim()).lowercase().replace(Regex("\\s+"), " ")
        readCache(key)?.let { return LookupResult(it, PartVoting.confidence(it), "cache", true) }
        val subject = "$brand $model".trim()
        val objective = "Find the remote control part number (for example BN59-xxxxx) that ships with or replaces the remote of the TV model $subject. " +
            "Prefer shop product pages and manuals that name the model and the remote part number together."
        val queries = listOf("$subject replacement remote control part number", "$model TV remote control compatible part number")
        val hits = LinkedHashMap<String, SearchHit>()
        var failure: Exception? = null
        for (q in queries) {
            try { provider.search(q, objective, 6).forEach { hits.putIfAbsent(it.url, it) } } catch (e: Exception) { failure = e }
        }
        if (hits.isEmpty() && failure != null) throw failure
        val votes = PartVoting.vote(hits.values.toList(), model).filter { it.score >= 0.5 }.take(6)
        writeCache(key, votes)
        return LookupResult(votes, PartVoting.confidence(votes), provider.id, false)
    }

    // one line per entry: key \t time \t base|part|score|hits|rev,rev ; ...
    private fun readCache(key: String): List<PartVote>? {
        if (!cacheFile.exists()) return null
        for (line in cacheFile.readLines()) {
            val f = line.split('\t')
            if (f.size < 3 || f[0] != key) continue
            if (clock() - (f[1].toLongOrNull() ?: 0) > ttl) return null
            return f[2].split(';').filter { it.isNotBlank() }.mapNotNull { e ->
                val p = e.split('|')
                if (p.size < 5) null else PartVote(p[0], p[1], p[2].toDoubleOrNull() ?: 0.0, p[3].toIntOrNull() ?: 0, p[4].split(',').filter { it.isNotEmpty() })
            }
        }
        return null
    }

    private fun writeCache(key: String, votes: List<PartVote>) {
        val kept = if (cacheFile.exists()) cacheFile.readLines().filter { !it.startsWith("$key\t") }.takeLast(300) else emptyList()
        val entry = votes.joinToString(";") { "${it.base}|${it.part}|${it.score}|${it.hits}|${it.revisions.joinToString(",")}" }
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText((kept + "$key\t${clock()}\t$entry").joinToString("\n") + "\n")
    }
}
