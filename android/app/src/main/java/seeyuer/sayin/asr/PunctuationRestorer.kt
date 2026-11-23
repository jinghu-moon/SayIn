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
                    ctTransformer = "punctuation",
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
    private val whitespace = setOf(' ', '\u00a0', '\t', '\n', '\r')

    fun needsTerminal(text: String): Boolean = text.isNotBlank() && text.last() !in terminal

    fun accept(original: String, candidate: String): String {
        val trimmed = candidate.trimEnd()
        if (trimmed.length < original.length + 1 || trimmed.length > original.length + 3) return original
        val appended = trimmed.removePrefix(original)
        if (appended.isEmpty() || appended.any { it !in terminal && it !in whitespace }) return original
        return trimmed
    }
}
