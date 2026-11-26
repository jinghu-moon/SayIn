package seeyuer.sayin.asr

/**
 * Spans that final-text stages must not rewrite: code, URLs, file paths, e-mail addresses,
 * version numbers and dotted technical tokens. The same pattern is shared by the hotword,
 * ITN and format stages so a rewrite cannot leak into protected text.
 */
internal object ProtectedText {
    const val URL_OR_CODE =
        """`[^`]*`|(?:https?://|www\.|[A-Za-z]:[\\/]|/)[^\s，。！？；、]+|(?!\d+\.\d+(?![A-Za-z0-9_.\\@~+:%=-]))[A-Za-z0-9_]+(?:[.\\@~+:%=-][A-Za-z0-9_]+)+"""

    fun mapOutside(text: String, protected: Regex, transform: (String) -> String): String = buildString {
        var start = 0
        for (match in protected.findAll(text)) {
            append(transform(text.substring(start, match.range.first)))
            append(match.value)
            start = match.range.last + 1
        }
        append(transform(text.substring(start)))
    }
}
