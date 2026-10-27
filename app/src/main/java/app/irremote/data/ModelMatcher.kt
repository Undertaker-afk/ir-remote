package app.irremote.data

/** Matches text read from a model sticker (OCR) against bundled remotes. */
object ModelMatcher {
    private fun alnum(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Candidate words from raw OCR text: alphanumeric, at least 3 characters, de-duplicated, hyphens removed. */
    fun tokens(text: String): List<String> =
        text.split(Regex("[\\s,;:()\\[\\]{}\"'/\\\\]+")).map { alnum(it) }.filter { it.length >= 3 }.distinct()

    private fun hasDigit(s: String) = s.any { it.isDigit() }

    /** Higher is better; 0 means no match. Model codes carry far more weight than brand names. */
    fun score(brand: String, model: String, tokens: List<String>): Int {
        val hay = alnum("$brand $model")
        val modelCodes = model.uppercase().split(Regex("[\\s_]+")).map { alnum(it) }.filter { it.length >= 5 && hasDigit(it) }
        val brandKey = alnum(brand)
        var score = 0
        for (t in tokens) {
            if (t.length >= 4 && hasDigit(t) && hay.contains(t)) score += 10 + t.length   // sticker code inside DB model
            else if (modelCodes.any { t.contains(it) }) score += 8 + t.length               // DB model inside longer sticker code
            else if (brandKey.length >= 3 && t == brandKey) score += 3                    // brand word
        }
        return score
    }
}
