package seeyuer.sayin.asr

/**
 * Formatting policy for final text (refer/voice/format.tsv):
 *
 * - collapse duplicated Chinese punctuation, keeping 3+ full stops as an ellipsis;
 * - remove spaces that sit next to Chinese text or Chinese punctuation (P7), while keeping
 *   spaces inside English phrases and keeping line breaks;
 * - trim surrounding whitespace.
 *
 * Text that is already clean must stay byte-identical (idempotent).
 */
internal object TextFormat {
    private const val SPACE = "[ \\t\\u3000]+"
    private const val HAN = "\\p{IsHan}"
    private const val PUNCT = "，。！？；：、…—"
    private val repeatStop = Regex("。{3,}")
    private val doubleStop = Regex("。{2}")
    private val repeatOther = Regex("([，？！])\\1+")
    // One space next to Chinese text or Chinese punctuation. English-to-English spaces stay.
    private val spaceNextToCjk = Regex("(?:(?<=[$HAN$PUNCT])$SPACE|$SPACE(?=[$HAN$PUNCT]))")

    fun clean(text: String): String {
        if (text.isBlank()) return text
        var value = repeatStop.replace(text, "……")
        value = doubleStop.replace(value, "。")
        value = repeatOther.replace(value, "$1")
        value = spaceNextToCjk.replace(value, "")
        return value.trim(' ', '\t', '\u3000')
    }
}
