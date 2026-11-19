package seeyuer.sayin.ui

/**
 * Converts explicitly spoken editing commands into draft operations.
 *
 * Safety rule: only a whole punctuation-delimited token can be a command. A command word inside a
 * sentence ("这个换行符怎么用") therefore stays ordinary text. The feature is opt-in so dictation
 * never changes shape just because a word happens to be spoken.
 */
internal enum class VoiceCommand { Newline, DeletePreviousSentence, Clear, Translate, Send }

internal data class VoiceCommandResult(
    val text: String,
    val cursor: Int,
    val commands: Set<VoiceCommand>,
) {
    val cleared get() = VoiceCommand.Clear in commands
    val translate get() = VoiceCommand.Translate in commands
    val send get() = VoiceCommand.Send in commands
}

internal object VoiceCommands {
    private const val SEPARATOR_CHARS = "，。！？；：、,.!?;:\n"
    private const val SENTENCE_END_CHARS = "。！？!?.;；\n"
    private val separators = Regex("([，。！？；：、,.!?;:\\n]+)")

    private val aliases: Map<String, VoiceCommand> = buildMap {
        fun put(command: VoiceCommand, vararg values: String) = values.forEach { put(it, command) }
        put(VoiceCommand.Newline, "换行", "换一行", "另起一行", "newline", "new line")
        put(VoiceCommand.DeletePreviousSentence, "删除上一句", "删除上一条", "删掉上一句",
            "delete previous sentence", "delete last sentence")
        put(VoiceCommand.Clear, "清空", "全部清空", "clear all", "clear")
        put(VoiceCommand.Translate, "翻译", "translate")
        put(VoiceCommand.Send, "发送", "发送出去", "send")
    }

    fun process(original: String, start: Int, end: Int, transcript: String): VoiceCommandResult {
        require(start in 0..original.length && end in start..original.length)
        val before = original.take(start)
        val after = original.drop(end)
        val out = StringBuilder(before)
        val commands = linkedSetOf<VoiceCommand>()
        var pendingSeparator: String? = null
        var previousWasCommand = false
        var cleared = false

        for (token in tokenize(transcript)) {
            val command = if (isSeparator(token)) null else aliases[token.trim().lowercase()]
            if (command != null) {
                // A separator before a command belongs to the command clause, not the text.
                pendingSeparator = null
                commands += command
                when (command) {
                    VoiceCommand.Newline -> out.append('\n')
                    VoiceCommand.DeletePreviousSentence -> deletePreviousSentence(out)
                    VoiceCommand.Clear -> {
                        out.setLength(0)
                        cleared = true
                    }
                    VoiceCommand.Translate, VoiceCommand.Send -> Unit
                }
                previousWasCommand = true
            } else if (isSeparator(token)) {
                pendingSeparator = token
            } else {
                if (out.isNotEmpty() && pendingSeparator != null && out.last() != '\n') {
                    out.append(pendingSeparator)
                }
                pendingSeparator = null
                out.append(token)
                previousWasCommand = false
            }
        }
        if (!previousWasCommand && pendingSeparator != null) out.append(pendingSeparator)
        val tail = if (cleared) "" else after
        val text = out.toString() + tail
        return VoiceCommandResult(text, out.length.coerceAtMost(text.length), commands)
    }

    /** Deletes the last sentence-like span, keeping earlier content and its terminator. */
    private fun deletePreviousSentence(out: StringBuilder) {
        while (out.isNotEmpty() && out.last().isWhitespace()) out.deleteCharAt(out.length - 1)
        if (out.isEmpty()) return
        val last = lastSentenceEnd(out, out.length)
        when {
            last < 0 -> out.setLength(0)
            last == out.length - 1 -> {
                val previous = lastSentenceEnd(out, last)
                if (previous < 0) out.setLength(0) else out.setLength(previous + 1)
            }
            else -> out.setLength(last + 1)
        }
    }

    private fun lastSentenceEnd(out: StringBuilder, endExclusive: Int): Int {
        for (index in endExclusive - 1 downTo 0) if (out[index] in SENTENCE_END_CHARS) return index
        return -1
    }

    private fun isSeparator(token: String): Boolean =
        token.isNotEmpty() && token.all { it in SEPARATOR_CHARS }

    private fun tokenize(value: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        for (match in separators.findAll(value)) {
            if (match.range.first > cursor) result += value.substring(cursor, match.range.first)
            result += match.value
            cursor = match.range.last + 1
        }
        if (cursor < value.length) result += value.substring(cursor)
        return result
    }
}
