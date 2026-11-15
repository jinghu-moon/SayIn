package seeyuer.sayin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardTextTest {
    @Test fun emptyAndMissingTextAreDistinguishedFromUriAndIntent() {
        assertEquals(ClipboardRead.Empty, ClipboardText.classify(null, false, false, 1, false))
        assertEquals(ClipboardRead.Empty, ClipboardText.classify("", false, false, 1, false))
        assertEquals(ClipboardRead.Empty, ClipboardText.classify(" \r\n\t", false, false, 1, false))
        assertEquals(ClipboardRead.NoText, ClipboardText.classify(null, true, false, 1, false))
        assertEquals(ClipboardRead.NoText, ClipboardText.classify(null, false, true, 1, false))
        assertEquals(ClipboardRead.NoText, ClipboardText.classify(null, true, true, 1, false))
    }

    @Test fun lengthBoundIsCheckedBeforeValidationAndCopying() {
        assertEquals(ClipboardRead.TooLong, ClipboardText.classify("a".repeat(64_001), false, false, 1, false))
        // The 64000-unit ceiling applies before the blank shortcut.
        assertEquals(ClipboardRead.TooLong, ClipboardText.classify(" ".repeat(64_001), false, false, 1, false))
        assertEquals(
            ClipboardRead.Invalid(ClipboardInvalidReason.TOO_MANY_BYTES),
            ClipboardText.classify("a".repeat(ClipboardText.MAX_UTF16_UNITS), false, false, 1, false),
        )
        assertEquals(
            ClipboardRead.Invalid(ClipboardInvalidReason.TOO_MANY_BYTES),
            ClipboardText.classify("a".repeat(63_999), false, false, 1, false),
        )
    }

    @Test fun validTextKeepsLineBreaksAndReportsSensitiveAndMultiple() {
        val body = "第一行\r\nsecond\tline"
        assertEquals(ClipboardRead.Text(body, sensitive = false, multiple = false), ClipboardText.classify(body, false, false, 1, false))
        assertEquals(ClipboardRead.Text("secret", sensitive = true, multiple = false), ClipboardText.classify("secret", false, false, 1, true))
        assertEquals(ClipboardRead.Text("first", sensitive = false, multiple = true), ClipboardText.classify("first", false, false, 2, false))
        assertEquals(ClipboardRead.Text("😀".repeat(4000), sensitive = false, multiple = false), ClipboardText.classify("😀".repeat(4000), false, false, 1, false))
    }

    @Test fun rejectsAndNamesEveryInvalidReason() {
        assertEquals(
            ClipboardRead.Invalid(ClipboardInvalidReason.TOO_MANY_CODE_POINTS),
            ClipboardText.classify("a".repeat(4001), false, false, 1, false),
        )
        assertEquals(
            ClipboardRead.Invalid(ClipboardInvalidReason.TOO_MANY_CODE_POINTS),
            ClipboardText.classify("😀".repeat(4001), false, false, 1, false),
        )
        // 3-byte CJK over 16 KiB: the byte budget is the reason a large non-ASCII body hits.
        assertEquals(
            ClipboardRead.Invalid(ClipboardInvalidReason.TOO_MANY_BYTES),
            ClipboardText.classify("中".repeat(5462), false, false, 1, false),
        )
        for (unsupported in listOf("bad\u0000", "bad\u001f", "bad\u007f")) {
            assertEquals(
                unsupported,
                ClipboardRead.Invalid(ClipboardInvalidReason.UNSUPPORTED_CHARACTERS),
                ClipboardText.classify(unsupported, false, false, 1, false),
            )
        }
        for (malformed in listOf("\ud800", "\udc00", "a\ud800b")) {
            assertEquals(
                malformed,
                ClipboardRead.Invalid(ClipboardInvalidReason.MALFORMED_UNICODE),
                ClipboardText.classify(malformed, false, false, 1, false),
            )
        }
    }
}
