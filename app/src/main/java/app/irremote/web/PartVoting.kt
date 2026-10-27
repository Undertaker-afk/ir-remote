package app.irremote.web

import app.irremote.data.ModelParser
import kotlin.math.sqrt

/** A remote part number candidate found on the web. [part] is the most common spelling ("BN59-01388A"). */
class PartVote(val base: String, val part: String, val score: Double, val hits: Int, val revisions: List<String>)

/**
 * Turns search results into ranked remote part numbers for a model. A part number only counts when the page ties it to the
 * model (it is in the title, or the model appears within a few hundred characters). Pages listing dozens of parts, such as
 * shop catalogues, are down-weighted, so one clean product page beats a long compatibility dump.
 */
object PartVoting {
    private val patterns = listOf(
        Regex("\\b(BN59|AA59|AA81|BN32)-?(\\d{5})([A-Z])?\\b"),                      // Samsung
        Regex("\\b(AKB)(\\d{8})\\b"),                                                   // LG
        Regex("\\b(RMT|RM)-?([A-Z]{1,3}\\d{3})([A-Z])?\\b"),                            // Sony
        Regex("\\b(N2QAY[A-Z]{2})(\\d{6})\\b"),                                         // Panasonic
        Regex("\\b(EUR)(\\d{6,8})\\b"),                                                 // Panasonic (older)
        Regex("\\b(ERF)([0-9A-Z]{4,6})\\b"),                                            // Hisense
    )

    /** Part-number-looking tokens in [text] as (base, full spelling). */
    fun extract(text: String): List<Triple<String, String, Int>> = patterns.flatMap { re ->
        re.findAll(text.uppercase()).map { m ->
            val base = m.groupValues[1] + m.groupValues[2]
            Triple(base, m.value, m.range.first)
        }.toList()
    }

    /** The model's identity for relevance checks: series core ("CU7000") or the plain model string. */
    fun modelCore(model: String): String {
        val tokens = model.split(Regex("[\\s,;()]+"))
        for (t in tokens) ModelParser.parse(t)?.let { return it.core }
        return ModelParser.norm(model)
    }

    /** Picks the model number out of free text ("43\" Crystal UHD Samsung UCU7000" -> "UCU7000"). */
    fun guessModel(text: String): String {
        val tokens = text.split(Regex("[\\s,;()\"']+")).filter { it.length >= 4 && it.any(Char::isDigit) && it.any(Char::isLetter) }
        return tokens.firstOrNull { ModelParser.parse(it) != null } ?: tokens.maxByOrNull { it.length } ?: text.trim()
    }

    fun vote(hits: List<SearchHit>, model: String): List<PartVote> {
        val core = modelCore(model)
        if (core.length < 4) return emptyList()
        class Agg(var score: Double = 0.0, val urls: HashSet<String> = HashSet(), val spellings: HashMap<String, Int> = HashMap())
        val agg = HashMap<String, Agg>()
        for (h in hits) {
            val title = h.title.uppercase()
            val body = h.text.uppercase()
            data class Occ(var title: Boolean = false, var near: Boolean = false, var far: Boolean = false, val spell: HashSet<String> = HashSet())
            val occ = HashMap<String, Occ>()
            for ((base, spelled, _) in extract(title)) occ.getOrPut(base) { Occ() }.apply { this.title = true; spell.add(spelled) }
            val compact = body
            for ((base, spelled, at) in extract(body)) {
                val window = compact.substring(maxOf(0, at - 250), minOf(compact.length, at + 250)).replace(Regex("[\\s-]"), "")
                val o = occ.getOrPut(base) { Occ() }
                if (core in window) o.near = true else o.far = true
                o.spell.add(spelled)
            }
            val n = occ.size
            for ((base, o) in occ) {
                if (!o.title && !o.near) continue                  // not tied to this model: not evidence
                val s = (if (o.title) 1.0 else 0.0) + (if (o.near) 0.6 else 0.0) + (if (o.far) 0.1 else 0.0)
                val a = agg.getOrPut(base) { Agg() }
                a.score += s / sqrt(n.toDouble())
                a.urls.add(h.url)
                o.spell.forEach { a.spellings.merge(it, 1, Int::plus) }
            }
        }
        return agg.map { (base, a) ->
            val best = a.spellings.maxByOrNull { it.value }?.key ?: base
            PartVote(base, best, a.score, a.urls.size, a.spellings.keys.sorted())
        }.sortedByDescending { it.score }
    }

    enum class Confidence { HIGH, MEDIUM, LOW }

    fun confidence(votes: List<PartVote>): Confidence = when {
        votes.isEmpty() -> Confidence.LOW
        votes[0].hits >= 2 && (votes.size == 1 || votes[0].score >= 1.3 * votes[1].score) -> Confidence.HIGH
        votes[0].hits >= 2 || votes[0].score >= 1.5 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }
}
