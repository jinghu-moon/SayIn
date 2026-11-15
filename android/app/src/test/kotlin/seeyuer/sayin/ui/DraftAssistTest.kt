package seeyuer.sayin.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class DraftAssistTest {
    private fun selection(text: String, start: Int, end: Int) = DraftAssist.selection(text, start, end)

    @Test fun legalCursorsAndReversedSelectionsAreNormalized() {
        assertEquals(TextRange(0, 0), selection("", 0, 0))
        assertEquals(TextRange(0, 0), selection("abc", 0, 0))
        assertEquals(TextRange(3, 3), selection("abc", 3, 3))
        assertEquals(TextRange(1, 3), selection("abc", 1, 3))
        assertEquals(TextRange(2, 5), selection("abcdef", 5, 2))
        assertEquals(TextRange(0, 6), selection("abcdef", 0, 6))
        assertEquals(TextRange(4, 4), selection("a😀b", 4, 4))
        assertEquals(TextRange(1, 3), selection("a😀b", 1, 3))
        assertEquals(TextRange(1, 3), selection("a😀b", 3, 1))
    }

    @Test fun outOfBoundsAndSurrogateSplittingSelectionsAreRejected() {
        for (text in listOf("", "abc", "a😀b")) {
            assertNull(selection(text, -1, 0))
            assertNull(selection(text, 0, -1))
            assertNull(selection(text, text.length + 1, text.length + 1))
            assertNull(selection(text, 0, text.length + 1))
        }
        // "a😀b" is a, high surrogate, low surrogate, b: indices 2 is inside the pair.
        assertNull(selection("a😀b", 2, 2))
        assertNull(selection("a😀b", 1, 2))
        assertNull(selection("a😀b", 2, 3))
        assertNull(selection("a😀b", 2, 0))
        assertNull(selection("a😀b", 0, 2))
        assertNull(selection("a😀b", 3, 2))
    }

    @Test fun composeReplacesExactlyTheGivenRangeWithoutNormalizing() {
        assertEquals("hi", DraftAssist.compose("", TextRange(0, 0), "hi"))
        assertEquals("aXbc", DraftAssist.compose("abc", TextRange(1, 1), "X"))
        assertEquals("abXf", DraftAssist.compose("abcdef", TextRange(2, 5), "X"))
        assertEquals("Z", DraftAssist.compose("abcdef", TextRange(0, 6), "Z"))
        assertEquals("aXb", DraftAssist.compose("a😀b", TextRange(1, 3), "X"))
        assertEquals("Helloworld", DraftAssist.compose("Hello", TextRange(5, 5), "world"))
        // No automatic space, punctuation or newline is added around the inserted text.
        assertEquals("a\nb\tdc", DraftAssist.compose("a\nc", TextRange(2, 2), "b\td"))
    }

    @Test fun caretUsesUtf16OffsetsOfTheInsertedText() {
        assertEquals(2, DraftAssist.caret(TextRange(0, 0), "hi"))
        assertEquals(2, DraftAssist.caret(TextRange(1, 1), "X"))
        assertEquals(3, DraftAssist.caret(TextRange(2, 5), "X"))
        assertEquals(2, DraftAssist.caret(TextRange(1, 3), "X"))
        assertEquals(3, DraftAssist.caret(TextRange(1, 3), "XY"))
        // One emoji is two UTF-16 units, so the caret lands after both units.
        assertEquals(3, DraftAssist.caret(TextRange(1, 1), "😀"))
        assertEquals(5, DraftAssist.caret(TextRange(2, 2), "b\td"))
    }

    @Test fun codePointLimitAccepts3999And4000ButRejects4001() {
        assertEquals(4000, DraftAssist.LIMIT_CODE_POINTS)
        assertEquals(16384, DraftAssist.LIMIT_UTF8_BYTES)
        assertTrue(DraftAssist.acceptable("a".repeat(3999)))
        assertTrue(DraftAssist.acceptable("a".repeat(4000)))
        assertFalse(DraftAssist.acceptable("a".repeat(4001)))
        assertTrue(DraftAssist.acceptable("中".repeat(4000)))
        assertFalse(DraftAssist.acceptable("中".repeat(4001)))
    }

    @Test fun utf8BudgetIsCheckedAgainstTheSameBoundary() {
        // 4000 code points never reach 16384 UTF-8 bytes (4 bytes per code point is the maximum),
        // so the code-point limit is the binding one; the pinned byte budget stays protocol-identical.
        assertTrue(DraftAssist.acceptable("😀".repeat(4000))) // 4000 code points, 16000 bytes
        assertFalse(DraftAssist.acceptable("😀".repeat(4096))) // 4096 code points, 16384 bytes
        assertEquals(16384, "😀".repeat(4096).toByteArray(Charsets.UTF_8).size)
        assertTrue(DraftAssist.acceptable("é".repeat(4000))) // 4000 code points, 8000 bytes
        // Tabs and line breaks normalize before the protocol checks code points and bytes.
        assertTrue(DraftAssist.acceptable("ab\n".repeat(1333) + "x")) // 4000 code points after normalization
        assertFalse(DraftAssist.acceptable("ab\n".repeat(1333) + "xy")) // 4001 code points
    }

    @Test fun controlCharactersDelAndLoneSurrogatesAreRejected() {
        assertFalse(DraftAssist.acceptable("a\u0000b"))
        assertFalse(DraftAssist.acceptable("a\u001fb"))
        assertFalse(DraftAssist.acceptable("a\u000bb"))
        assertFalse(DraftAssist.acceptable("a\u007fb"))
        assertFalse(DraftAssist.acceptable("a\uD83D"))
        assertFalse(DraftAssist.acceptable("\uDE00a"))
        assertTrue(DraftAssist.acceptable("a😀b"))
        assertTrue(DraftAssist.acceptable("a\r\nb\tc"))
        assertTrue(DraftAssist.acceptable("a\rb"))
    }

    @Test fun blankResultsAreRejectedEvenWhenProtocolNormalizationWouldKeepSpaces() {
        assertFalse(DraftAssist.acceptable(""))
        assertFalse(DraftAssist.acceptable(" "))
        assertFalse(DraftAssist.acceptable(" \n\t"))
        assertTrue(DraftAssist.acceptable(" x "))
    }
}
