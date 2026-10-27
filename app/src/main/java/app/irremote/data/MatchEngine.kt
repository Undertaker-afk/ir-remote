package app.irremote.data

import kotlin.math.abs

/**
 * Model-number matching: turns "UE43CU7000AUXXH", "UCU7000", "43UP7500PSF", "KD-43X80J" or a remote part number such as
 * "BN59-01315B" into structured fields and ranks bundled remotes by how strongly they belong to the same series,
 * generation or remote. TV serial numbers carry no remote information; the model number and the part number on the
 * remote do.
 */
object ModelParser {
    /** [letters]+[digits] is the series identity ("CU7000"); [size] and [suffix] (region, colour, revision) are not. */
    data class Parsed(
        val norm: String,
        val size: Int?,
        val letters: String,
        val digits: String,
        val suffix: String,
        val years: List<Int>,
    ) {
        val core get() = letters + digits
    }

    private val samsungYear = mapOf('E' to 2012, 'F' to 2013, 'H' to 2014, 'J' to 2015, 'K' to 2016, 'M' to 2017, 'N' to 2018,
        'R' to 2019, 'T' to 2020, 'A' to 2021, 'B' to 2022, 'C' to 2023)
    private val lgYear = mapOf('A' to listOf(2013), 'B' to listOf(2014), 'F' to listOf(2015), 'H' to listOf(2016), 'J' to listOf(2017),
        'K' to listOf(2018), 'M' to listOf(2012, 2019), 'N' to listOf(2013, 2020), 'P' to listOf(2021), 'Q' to listOf(2022),
        'R' to listOf(2023), 'T' to listOf(2024))
    private val sonyYear = mapOf('A' to 2013, 'B' to 2014, 'C' to 2015, 'D' to 2016, 'E' to 2017, 'F' to 2018, 'G' to 2019, 'H' to 2020,
        'J' to 2021, 'K' to 2022, 'L' to 2023)

    private val prefixes = listOf("UE", "UN", "UA", "QE", "QN", "LE", "PS", "KDL", "KD", "XBR", "XR", "FW", "TX", "TH", "LC", "LED", "OLED")

    fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Strips region/variant after '/', e.g. "43PUS7906/12" -> "43PUS7906". */
    fun parse(token: String, brandKey: String? = null): Parsed? {
        val s = norm(token.substringBefore('/'))
        if (s.length < 4 || s.none { it.isDigit() } || s.none { it.isLetter() }) return null
        var rest = s
        // optional product-line prefix (UE, KDL, TX...), only if followed by the size
        for (p in prefixes.sortedByDescending { it.length }) if (rest.startsWith(p) && rest.drop(p.length).firstOrNull()?.isDigit() == true) { rest = rest.drop(p.length); break }
        var size: Int? = null
        Regex("^(\\d{2,3})(?=[A-Z])").find(rest)?.let { m ->
            val v = m.value.toInt()
            if (v in 19..110) { size = v; rest = rest.drop(m.value.length) }
        }
        val m = Regex("^([A-Z]{1,4})(\\d{1,4})([A-Z0-9]*)$").find(rest) ?: return null
        var letters = m.groupValues[1]
        val digits = m.groupValues[2]
        val suffix = m.groupValues[3]
        // Samsung's AirPlay/Cast model drops "E"/"N" from "UE"/"UN": "UCU7000" is "CU7000"
        if (letters.length == 3 && letters[0] in "UQL" && size == null) letters = letters.drop(1)
        val brand = brandKey.orEmpty()
        val years: List<Int> = when {
            brand.startsWith("SAMSUNG") && letters.length == 1 && letters[0] == 'Q' && suffix.isNotEmpty() -> listOfNotNull(samsungYear[suffix[0]])
            brand.startsWith("SAMSUNG") && letters.isNotEmpty() ->
                if (letters == "D" && digits.length == 4) listOf(2011) else if (letters[0] == 'D' && letters.length == 2) listOf(2024) else listOfNotNull(samsungYear[letters[0]])
            brand == "LG" && letters.length >= 2 -> lgYear[letters[1]].orEmpty()
            brand.startsWith("SONY") && suffix.isNotEmpty() -> listOfNotNull(sonyYear[suffix[0]])
            else -> emptyList()
        }
        return Parsed(s, size, letters, digits, suffix, years)
    }

    /** Part numbers printed on the remote itself: BN59-01315B, AA59-00443A, N2QAYB001109, RC43140, EN2B27... */
    fun partKey(token: String): Pair<String, String>? {
        val s = norm(token)
        Regex("^([A-Z]{2}\\d{2}\\d{5})([A-Z]?)$").find(s)?.let { return it.groupValues[1] to it.groupValues[2] }   // Samsung style
        Regex("^(N2QA[A-Z]{2}\\d{6})$").find(s)?.let { return it.groupValues[1] to "" }                          // Panasonic
        Regex("^([A-Z]{2,4}\\d{4,7})([A-Z]?)$").find(s)?.let { if (it.groupValues[1].any { c -> c.isDigit() } && it.groupValues[1].length >= 7) return it.groupValues[1] to it.groupValues[2] }
        return null
    }
}

enum class Tier(val label: String) {
    EXACT("Exact match"),
    SAME_REMOTE("Same remote (revision differs)"),
    SAME_SERIES("Same series"),
    SAME_FAMILY("Neighbouring series"),
    SAME_ERA("Same generation (power code)"),
    BRAND("Same brand"),
}

/** [reason] is the model/part number (or year range for SAME_ERA) that explains the match; empty for brand-only. */
class Match(val category: Category, val remote: IrRemote, val tier: Tier, val score: Int, val reason: String)

class MatchEngine(entries: List<Pair<Category, IrRemote>>) {
    private class Tok(val raw: String, val norm: String, val parsed: ModelParser.Parsed?, val part: Pair<String, String>?)
    private class Entry(val category: Category, val remote: IrRemote, val brandKey: String, val toks: List<Tok>)

    private val splitter = Regex("[\\s,;:()\\[\\]{}\"'\\\\]+")

    private fun tokens(text: String, brandKey: String?): List<Tok> = text.split(splitter).flatMap { w ->
        // "43PUS7906/12" yields the model and the region code separately
        listOf(w) + w.split('/').filter { it.length >= 4 && it != w }
    }.filter { it.length >= 4 }.map { Tok(it, ModelParser.norm(it), ModelParser.parse(it, brandKey), ModelParser.partKey(it)) }.distinctBy { it.norm }

    private val index: List<Entry> = entries.map { (c, r) ->
        val bk = ModelParser.norm(r.brand)
        Entry(c, r, bk, tokens(r.model.removePrefix(r.brand), bk).filter { it.norm.any(Char::isDigit) })
    }
    private val brandKeys: Set<String> = index.map { it.brandKey }.filter { it.length >= 2 }.toSet()

    /** Brand keys mentioned in [text] ("Samsung Electronics" -> SAMSUNG). */
    fun brandsIn(text: String): Set<String> {
        val words = text.split(splitter).map { ModelParser.norm(it) }.filter { it.isNotEmpty() }
        return brandKeys.filter { k -> words.any { w -> w == k || (k.length >= 4 && w.startsWith(k)) } }.toSet()
    }

    /** Rank every entry against the free text (device name, manufacturer, model, OCR output, a typed model...). */
    fun match(text: String, preferred: Set<Category> = emptySet(), limit: Int = 40): List<Match> {
        val brands = brandsIn(text)
        val bk = brands.firstOrNull()
        val q = tokens(text, bk)
        val results = ArrayList<Match>()
        for (e in index) {
            val sameBrand = e.brandKey in brands
            val brandKnownAndDifferent = brands.isNotEmpty() && !sameBrand
            var best: Match? = null
            fun offer(score: Int, tier: Tier, reason: String) {
                val s = score + (if (sameBrand) 10 else 0) - (if (brandKnownAndDifferent) 40 else 0) + (if (e.category in preferred) 1 else 0)
                if (best == null || s > best!!.score) best = Match(e.category, e.remote, tier, s, reason)
            }
            for (a in q) for (b in e.toks) {
                if (a.part != null && b.part != null) {
                    if (a.part.first == b.part.first) {
                        if (a.part.second == b.part.second) offer(100, Tier.EXACT, b.raw)
                        else offer(85, Tier.SAME_REMOTE, b.raw)
                    }
                    continue
                }
                if (a.norm == b.norm && a.norm.length >= 5) { offer(100, Tier.EXACT, b.raw); continue }
                val pa = a.parsed ?: continue
                val pb = b.parsed ?: continue
                if (pa.core == pb.core) {
                    val bonus = (if (pa.suffix == pb.suffix) 5 else 0) + (if (pa.size != null && pa.size == pb.size) 3 else 0)
                    offer(90 + bonus, Tier.SAME_SERIES, b.raw)
                } else if (pa.letters == pb.letters && pa.digits.length == pb.digits.length && pa.digits.take(1) == pb.digits.take(1)) {
                    val gap = abs((pa.digits.toIntOrNull() ?: 0) - (pb.digits.toIntOrNull() ?: 0))
                    offer(if (gap <= 100) 75 else 60, Tier.SAME_FAMILY, b.raw)
                }
            }
            if (best == null && sameBrand) offer(10, Tier.BRAND, "")
            best?.let { results.add(it) }
        }
        // generation fallback: remotes of the brand whose power code is shared with models of nearby years
        val queryYears = q.mapNotNull { it.parsed?.years?.takeIf { y -> y.isNotEmpty() } }.flatten()
        if (bk != null && queryYears.isNotEmpty() && results.none { it.tier <= Tier.SAME_SERIES }) {
            val dated = index.filter { it.brandKey == bk }.mapNotNull { e ->
                val years = e.toks.flatMap { it.parsed?.years.orEmpty() }
                val dist = years.minOfOrNull { y -> queryYears.minOf { abs(it - y) } } ?: return@mapNotNull null
                Triple(e, dist, years)
            }.filter { it.second <= 3 }
            val powers = dated.mapNotNull { it.first.remote.find(Slot.POWER)?.encoded }.toSet()
            if (powers.isNotEmpty()) for ((i, r) in results.withIndex()) {
                if (r.tier == Tier.BRAND && r.remote.find(Slot.POWER)?.encoded in powers) {
                    results[i] = Match(r.category, r.remote, Tier.SAME_ERA, 40 + (if (r.category in preferred) 1 else 0), "${queryYears.min() - 3}–${queryYears.max() + 3}")
                }
            }
        }
        return results.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.remote.buttons.size }).take(limit)
    }

    /** Match per remote (by identity) for a query; used to order wizard candidates. Remotes missing from the map are unrelated. */
    fun scoreMap(text: String): Map<IrRemote, Match> =
        java.util.IdentityHashMap<IrRemote, Match>().also { m -> match(text, limit = Int.MAX_VALUE).forEach { m[it.remote] = it } }
}
