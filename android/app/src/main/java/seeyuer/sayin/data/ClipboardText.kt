package seeyuer.sayin.data

/** Why a clipboard body cannot become an insertable draft text. */
internal enum class ClipboardInvalidReason {
    TOO_MANY_CODE_POINTS,
    TOO_MANY_BYTES,
    UNSUPPORTED_CHARACTERS,
    MALFORMED_UNICODE,
}

/**
 * Result of one user-authorized clipboard read.
 *
 * [Text.text] is the exact user body and keeps line breaks; [Text.sensitive] comes from the Android
 * 13+ sensitive flag and [Text.multiple] warns that only the first item is represented.
 */
internal sealed interface ClipboardRead {
    data class Text(
        val text: String,
        val sensitive: Boolean,
        val multiple: Boolean,
    ) : ClipboardRead
    data class Invalid(val reason: ClipboardInvalidReason) : ClipboardRead
    data object Empty : ClipboardRead
    data object NoText : ClipboardRead
    data object TooLong : ClipboardRead
    data object Unavailable : ClipboardRead
}

/**
 * Pure classification of the first clipboard item. Android platform access lives in
 * [AndroidClipboard]; this object is deterministic and unit-testable.
 */
internal object ClipboardText {
    /** Above this many UTF-16 units the content is rejected before any second copy is made. */
    const val MAX_UTF16_UNITS = 64_000

    fun classify(
        firstText: String?,
        hasUri: Boolean,
        hasIntent: Boolean,
        itemCount: Int,
        sensitive: Boolean,
    ): ClipboardRead {
        if (firstText == null) {
            return if (hasUri || hasIntent) ClipboardRead.NoText else ClipboardRead.Empty
        }
        // Check the bound before blanking/normalizing/copying so an arbitrarily large clip is not
        // admitted into another application-owned buffer.
        if (firstText.length > MAX_UTF16_UNITS) return ClipboardRead.TooLong
        if (firstText.isBlank()) return ClipboardRead.Empty
        // Acceptance and reason both come from PhraseRules/normalizedText, the same rules the
        // phrase library and the send path use; no second validator lives in the clipboard layer.
        return when (val reason = PhraseRules.invalidReason(firstText)) {
            null -> ClipboardRead.Text(firstText, sensitive, itemCount > 1)
            else -> ClipboardRead.Invalid(reason)
        }
    }
}
