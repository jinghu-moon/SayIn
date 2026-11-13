package seeyuer.sayin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayloadTest {
    @Test fun preservesUnicodeAndLiteralKeyWords() {
        assertEquals("中文 😀 {ENTER}", normalizedText("中文 😀 {ENTER}"))
        assertEquals("😀".repeat(4000), normalizedText("😀".repeat(4000)))
    }
    @Test fun normalizesBeforePayloadBecomesImmutable() {
        assertEquals("a b c d", normalizedText("a\r\nb\nc\td"))
        assertEquals("  ", normalizedText("\n\t"))
    }
    @Test fun rejectsControlsUnpairedSurrogatesAndOversizedText() {
        for (value in listOf("", "bad\u0000", "bad\u007f", "\ud800", "\udc00", "😀".repeat(4001))) {
            assertThrows(IllegalArgumentException::class.java) { normalizedText(value) }
        }
    }
    @Test fun acceptsOnlyCanonicalV4Identifiers() {
        val canonical = "abcdefab-1234-4234-8234-123456789012"
        assertEquals(canonical, validId(canonical))
        for (id in listOf(canonical.uppercase(), "abcdefab-1234-1234-8234-123456789012", "not-an-id")) {
            assertThrows(IllegalArgumentException::class.java) { validId(id) }
        }
    }
    @Test fun ipv4ParsingNeverFallsBackToDns() {
        assertEquals("192.168.1.7", numericAddress("192.168.1.7").hostAddress)
        for (value in listOf("computer.local", "01.2.3.4", "256.1.2.3", "1.2.3", "١.2.3.4", "1.2.3.4/24")) {
            assertThrows(IllegalArgumentException::class.java) { numericAddress(value) }
        }
    }
}
