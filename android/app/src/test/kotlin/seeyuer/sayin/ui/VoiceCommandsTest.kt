package seeyuer.sayin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    @Test
    fun plainTranscriptKeepsSelectionAndSurroundingText() {
        val result = VoiceCommands.process("前缀|后缀", 2, 3, "你好，世界。")
        assertEquals("前缀你好，世界。后缀", result.text)
        assertEquals(8, result.cursor)
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun newlineReplacesTheCommandClause() {
        val result = VoiceCommands.process("", 0, 0, "你好，换行，世界")
        assertEquals("你好\n世界", result.text)
        assertEquals(5, result.cursor)
        assertEquals(setOf(VoiceCommand.Newline), result.commands)
    }

    @Test
    fun deletePreviousSentenceKeepsEarlierSentence() {
        val result = VoiceCommands.process("", 0, 0, "第一句。第二句，删除上一句")
        assertEquals("第一句。", result.text)
        assertEquals("第一句。".length, result.cursor)
        assertEquals(setOf(VoiceCommand.DeletePreviousSentence), result.commands)
    }

    @Test
    fun deletePreviousSentenceRemovesTheOnlySentence() {
        val result = VoiceCommands.process("前缀", 0, 2, "删除上一句")
        assertEquals("", result.text)
        assertEquals(0, result.cursor)
    }

    @Test
    fun clearDropsBaseAndSuffix() {
        val result = VoiceCommands.process("旧|新", 1, 2, "清空")
        assertEquals("", result.text)
        assertEquals(0, result.cursor)
        assertTrue(result.cleared)
    }

    @Test
    fun textAfterClearIsKept() {
        val result = VoiceCommands.process("旧|新", 1, 2, "清空，你好")
        assertEquals("你好", result.text)
        assertEquals(2, result.cursor)
    }

    @Test
    fun commandWordInsideSentenceIsNotApplied() {
        val result = VoiceCommands.process("", 0, 0, "这个换行符怎么用")
        assertEquals("这个换行符怎么用", result.text)
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun englishCommandsWorkWhenClauseDelimited() {
        val result = VoiceCommands.process("", 0, 0, "hello, new line, world")
        assertEquals("hello\n world", result.text)
        assertEquals(setOf(VoiceCommand.Newline), result.commands)
    }

    @Test
    fun translateAndSendAreFlagsOnly() {
        val result = VoiceCommands.process("", 0, 0, "你好，翻译")
        assertEquals("你好", result.text)
        assertTrue(result.translate)
        assertFalse(result.send)
        assertEquals(setOf(VoiceCommand.Translate), result.commands)
        assertFalse(result.cleared)
    }

    @Test
    fun trailingPunctuationIsPreservedWithoutCommands() {
        val result = VoiceCommands.process("", 0, 0, "Hello, world.")
        assertEquals("Hello, world.", result.text)
        assertEquals("Hello, world.".length, result.cursor)
    }

    @Test
    fun commandsAreTrimmedAndCaseInsensitive() {
        val result = VoiceCommands.process("", 0, 0, "你好， 发送 ")
        assertEquals("你好", result.text)
        assertTrue(result.send)
    }
}
