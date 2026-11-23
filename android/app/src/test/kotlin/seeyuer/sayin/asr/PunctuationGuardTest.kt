package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationGuardTest {
    @Test
    fun appendsOnlyTerminalMark() {
        assertEquals("你好。", PunctuationGuard.accept("你好", "你好。"))
        assertEquals("你好？", PunctuationGuard.accept("你好", "你好？"))
    }

    @Test
    fun rejectsContentRewrite() {
        assertEquals("1~11件套餐", PunctuationGuard.accept("1~11件套餐", "1到11件套餐。"))
        assertEquals("v1.2.3", PunctuationGuard.accept("v1.2.3", "版本 1.2.3。"))
    }

    @Test
    fun rejectsExistingTerminalRewrite() {
        assertEquals("你好。", PunctuationGuard.accept("你好。", "你好？"))
        assertFalse(PunctuationGuard.needsTerminal("你好。"))
        assertTrue(PunctuationGuard.needsTerminal("你好"))
    }
}
