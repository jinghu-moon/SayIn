package seeyuer.sayin.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.Closeable

internal class PunctuationRestorer(private val context: Context) : Closeable {
    private var model: OfflinePunctuation? = null
    private var unavailable = false

    fun prepare() {
        if (!unavailable) runCatching { ensureModel() }.onFailure { unavailable = true }
    }

    fun restore(text: String): String {
        if (text.isBlank() || text.length > MAX_INPUT_CHARS) return text
        prepare()
        val candidate = runCatching { model?.addPunctuation(text) }.getOrNull() ?: return text
        return PunctuationGuard.accept(text, candidate)
    }

    private fun ensureModel(): OfflinePunctuation {
        model?.let { return it }
        val created = OfflinePunctuation(
            assetManager = context.applicationContext.assets,
            config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = "punctuation/model.int8.onnx",
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        model = created
        return created
    }

    override fun close() {
        model?.release()
        model = null
    }

    private companion object {
        const val MAX_INPUT_CHARS = 512
    }
}

internal object PunctuationGuard {
    private const val insertionMarks = "，、。！？；：,.!?;:"
    private val structured = Regex("""(?:https?://|www\.|--?|/|[a-zA-Z]:[\\/])[^\s\p{IsHan}]+|[A-Za-z0-9_]+(?:[./\\@~+:%-][A-Za-z0-9_]+)+""")

    /** Keep the original text verbatim, including its spaces and punctuation. */
    fun accept(original: String, candidate: String): String {
        if (original.isBlank()) return original
        val protected = structured.findAll(original).map { it.range }.toList()
        val insertionOffsets = mutableListOf<Pair<Int, Char>>()
        var offset = 0
        for (character in candidate) {
            if (character.isWhitespace()) continue
            val beforeSpaces = offset
            while (offset < original.length && original[offset].isWhitespace()) offset++
            if (original.getOrNull(offset) == character) {
                offset++
            } else {
                // CT can repeat a mark already produced by ASR. Keep that mark and continue aligning.
                val previous = original.getOrNull(beforeSpaces - 1)
                if (character in insertionMarks && previous != null && previous in insertionMarks) {
                    offset = beforeSpaces
                    continue
                }
                if (character !in insertionMarks || insertionOffsets.lastOrNull()?.first == beforeSpaces ||
                    !canInsert(original, beforeSpaces, offset, protected)
                ) return original
                insertionOffsets += beforeSpaces to character
                // Reuse the same original character for the next candidate character.
                offset = beforeSpaces
            }
        }
        if (original.drop(offset).any { !it.isWhitespace() }) return original
        val result = StringBuilder(original)
        for ((index, character) in insertionOffsets.asReversed()) result.insert(index, character)
        return result.toString()
    }

    private fun canInsert(text: String, index: Int, afterSpaces: Int, protected: List<IntRange>): Boolean {
        val before = text.getOrNull(index - 1) ?: return false
        val after = text.getOrNull(afterSpaces)
        val afterPercentage = before == '%' && text.getOrNull(index - 2)?.isDigit() == true
        if ((!before.isLetterOrDigit() && !afterPercentage) || (after != null && !after.isLetterOrDigit())) return false
        if (protected.any { index > it.first && index <= it.last + 1 }) return false
        if (index == afterSpaces && after != null) {
            if (before.isDigit() || after.isDigit()) return false
            if (before.code < 128 && after.code < 128) return false
        }
        return true
    }
}
