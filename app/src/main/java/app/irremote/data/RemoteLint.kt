package app.irremote.data

/**
 * Catches database entries that are certainly broken (community-contributed data has labelling slips): a remote whose
 * direction keys all send the same code, or whose channel/volume up and down are identical, cannot be right. Dual-function
 * keys (arrows that double as volume/channel) are legitimate and are not flagged.
 */
object RemoteLint {
    fun problems(r: IrRemote): List<String> {
        val out = ArrayList<String>()
        val dpad = listOf(Slot.UP, Slot.DOWN, Slot.LEFT, Slot.RIGHT).mapNotNull { r.find(it)?.encoded }
        if (dpad.size >= 3 && dpad.toSet().size == 1) out.add("direction keys all send the same code")
        fun same(a: Slot, b: Slot): Boolean {
            val x = r.find(a)?.encoded ?: return false
            return x == r.find(b)?.encoded
        }
        if (same(Slot.CH_UP, Slot.CH_DOWN)) out.add("channel up and down are identical")
        if (same(Slot.VOL_UP, Slot.VOL_DOWN)) out.add("volume up and down are identical")
        return out
    }

    fun broken(r: IrRemote) = problems(r).isNotEmpty()
}

/** Combines a data source with hand-curated remotes. */
object DbMerge {
    /** Part numbers named in a remote's model text ("BN59-01388 TM2240A ..." -> "BN5901388"). */
    fun partBases(r: IrRemote): Set<String> =
        r.model.split(Regex("[\\s,;()]+")).mapNotNull { ModelParser.partKey(it)?.first }.toSet()

    /**
     * Drops entries that fail [RemoteLint], and any source entry of the same brand whose part number is covered by a curated
     * remote (the curated one is the corrected version), then adds the curated remotes.
     */
    fun merge(source: List<IrRemote>, curated: List<IrRemote>): List<IrRemote> {
        val covered = curated.flatMap { c -> partBases(c).map { c.brand.lowercase() to it } }.toSet()
        val kept = source.filter { r -> !RemoteLint.broken(r) && partBases(r).none { (r.brand.lowercase() to it) in covered } }
        return kept + curated
    }
}
