package seeyuer.sayin.asr

/**
 * Deterministic correction of recurring ASR misrecognitions (for example "chat gpt" -> "ChatGPT").
 * Matching is case-insensitive; ASCII entries require word boundaries so "kotlin" does not touch
 * "kotlinx". Protected technical spans are never rewritten.
 */
internal class HotwordNormalizer(pairs: List<Pair<String, String>>) {
    private val entries = pairs
        .filter { it.first.isNotBlank() && it.first != it.second }
        .sortedByDescending { it.first.length }
    private val pattern = entries.takeIf { it.isNotEmpty() }
        ?.let { Regex(it.joinToString("|") { entry -> Regex.escape(entry.first) }, RegexOption.IGNORE_CASE) }
    private val protected = Regex(ProtectedText.URL_OR_CODE)

    fun apply(text: String): String {
        val regex = pattern ?: return text
        return ProtectedText.mapOutside(text, protected) { prose ->
            regex.replace(prose) { match ->
                val entry = entries.firstOrNull { it.first.equals(match.value, ignoreCase = true) }
                    ?: return@replace match.value
                if (!hasWordBoundary(prose, match.range, entry.first)) return@replace match.value
                entry.second
            }
        }
    }

    private fun hasWordBoundary(text: String, range: IntRange, key: String): Boolean {
        if (key.first().isAsciiWord() && range.first > 0 && text[range.first - 1].isAsciiWord()) return false
        if (key.last().isAsciiWord() && range.last + 1 < text.length && text[range.last + 1].isAsciiWord()) return false
        return true
    }

    private fun Char.isAsciiWord() = (code < 128 && (isLetterOrDigit())) || this == '_'
}

