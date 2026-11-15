package seeyuer.sayin.ui

import androidx.compose.ui.text.TextRange
import seeyuer.sayin.data.normalizedText

/** Which owned representation of the shared draft an insertion targets. */
internal enum class DraftRepresentation { Original, Translation }

/** A frozen insertion position, captured when the input-assist panel opened (or after the
 * previous insertion in the same panel). It never falls back to "append at the end later".
 */
internal data class AssistInsertion(
    val draftId: String,
    val representation: DraftRepresentation,
    val revision: Long,
    val start: Int,
    val end: Int,
    val epoch: Long,
)

internal sealed interface AssistInsertResult {
    data class Inserted(val caret: Int) : AssistInsertResult
    data object Stale : AssistInsertResult
    data object NotEditable : AssistInsertResult
    data object Rejected : AssistInsertResult
}

/** Pure rules for one insertion transaction. Selection indexes are Kotlin UTF-16 offsets. */
internal object DraftAssist {
    const val LIMIT_CODE_POINTS = 4000
    const val LIMIT_UTF8_BYTES = 16384

    /**
     * Returns the range normalized to min/max, or null when the selection cannot be edited:
     * out of bounds, or a boundary that would split a surrogate pair. A collapsed cursor is
     * a legal range; callers choose the fallback position at open time, never at insert time.
     */
    fun selection(text: String, start: Int, end: Int): TextRange? {
        if (start !in 0..text.length || end !in 0..text.length) return null
        if (start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) return null
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) return null
        return TextRange(minOf(start, end), maxOf(start, end))
    }

    /** Prefix + inserted text + suffix. No automatic space, punctuation or newline. */
    fun compose(text: String, range: TextRange, insert: String): String =
        text.substring(0, range.min) + insert + text.substring(range.max)

    fun caret(range: TextRange, insert: String): Int = range.min + insert.length

    /**
     * The transport validator decides what may be inserted: an over-limit or invalid result is
     * rejected as a whole, never truncated, and the unchanged draft keeps its selection.
     */
    fun acceptable(result: String): Boolean =
        result.isNotBlank() && runCatching { normalizedText(result) }.isSuccess
}
