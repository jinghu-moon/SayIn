package seeyuer.sayin.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.Closeable

/**
 * Conservative final punctuation for X-ASR. The model may only append one
 * terminal mark; any content rewrite is rejected and the original text wins.
 */
internal class PunctuationRestorer(private val context: Context) : Closeable {
    private var model: OfflinePunctuation? = null

    fun restore(text: String): String {
        if (!PunctuationGuard.needsTerminal(text)) return text
        val candidate = runCatching {
            ensureModel().addPunctuation(text)
        }.getOrNull() ?: return text
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
}

internal object PunctuationGuard {
    private val terminal = setOf('。', '！', '？', '.', '!', '?')
    private val punctuation = terminal + setOf('，', '、', '；', '：', ',', ';', ':', '…', '—', '（', '）', '(', ')')
    private val whitespace = setOf(' ', '\u00a0', '\t', '\n', '\r')

    fun needsTerminal(text: String): Boolean = text.isNotBlank() && text.last() !in terminal

    fun accept(original: String, candidate: String): String {
        if (original.isBlank()) return original
        // Never rewrite a user's existing terminal punctuation.
        if (original.last() in terminal) return original
        val trimmed = candidate.trimEnd()
        if (trimmed == original) return original
        if (trimmed.length > original.length + 8) return original
        // The model may add commas/terminal marks, but must not change正文.
        if (normalize(trimmed) != normalize(original)) return original
        return trimmed
    }

    private fun normalize(value: String): String = value.filterNot { it in punctuation || it in whitespace }
}
