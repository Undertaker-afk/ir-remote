package app.irremote.data

import app.irremote.ir.IrSignal

/**
 * Suggests replacement codes for a key that does not work, from what other remotes of the same device family agree on.
 * A "family" is the protocol and address a remote mostly uses (for example Samsung32 / 0x07).
 */
object KeyRepair {
    /** [encoded] is the proposed signal; [swapWith] names another key that should receive the old code (swapped pairs). */
    class Suggestion(val encoded: String, val votes: Int, val swapWith: String? = null)

    fun keyId(name: String): String {
        val n = norm(name)
        return Slot.entries.firstOrNull { n in it.aliases }?.name ?: n
    }

    private fun family(r: IrRemote): Pair<String, Long>? {
        val counts = HashMap<Pair<String, Long>, Int>()
        for (b in r.buttons) (b.signal as? IrSignal.Parsed)?.let { counts.merge(it.protocol to it.address, 1, Int::plus) }
        return counts.maxByOrNull { it.value }?.key
    }

    fun suggest(remote: IrRemote, buttonName: String, all: List<IrRemote>, limit: Int = 5): List<Suggestion> {
        val btn = remote.buttons.firstOrNull { it.name == buttonName } ?: return emptyList()
        val fam = family(remote) ?: return emptyList()
        val id = keyId(btn.name)
        val peers = all.filter { it !== remote && family(it) == fam }
        if (peers.size < 3) return emptyList()

        // how many distinct peers use each code for a given key
        fun votesFor(key: String): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (p in peers) p.buttons.firstOrNull { keyId(it.name) == key }?.encoded?.let { m.merge(it, 1, Int::plus) }
            return m
        }
        fun consensus(key: String): String? = votesFor(key).maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key

        val mine = votesFor(id)
        val out = ArrayList<Suggestion>()
        // swapped pair: my code for this key is the consensus for another key, and that key holds this key's consensus
        val want = consensus(id)
        if (want != null && want != btn.encoded) {
            remote.buttons.firstOrNull { it !== btn && consensus(keyId(it.name)) == btn.encoded && it.encoded == want }
                ?.let { out.add(Suggestion(want, mine[want] ?: 0, it.name)) }
        }
        val usedElsewhere = remote.buttons.filter { it !== btn }.map { it.encoded }.toSet()
        mine.entries.sortedByDescending { it.value }
            .filter { it.value >= 2 && it.key != btn.encoded && it.key !in usedElsewhere && out.none { s -> s.encoded == it.key } }
            .forEach { out.add(Suggestion(it.key, it.value)) }
        return out.take(limit)
    }

    /** Plain-text report for sharing (issue tracker, e-mail). */
    fun report(remote: IrRemote, button: IrButton, suggestion: Suggestion?): String = buildString {
        appendLine("Remote: ${remote.brand} ${remote.model}".trim())
        appendLine("Key that does not work: ${button.name}")
        appendLine("Code in the database: ${button.encoded}")
        if (suggestion != null) {
            appendLine("Code that worked instead: ${suggestion.encoded}" + (suggestion.swapWith?.let { " (swap with $it)" } ?: ""))
            appendLine("Agreed by ${suggestion.votes} other remotes of the same device family")
        }
        append("Reported from IR Remote (https://github.com/undertaker-afk/ir-remote)")
    }
}
