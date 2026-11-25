package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationGuardTest {
    @Test
    fun appendsOnlyTerminalMark() {
        assertEquals("你好。", PunctuationGuard.accept("你好", "你好。"))
        assertEquals("你好？", PunctuationGuard.accept("你好", "你好？"))
        assertEquals("增长了20%。", PunctuationGuard.accept("增长了20%", "增长了20%。"))
    }

    @Test
    fun rejectsContentRewrite() {
        assertEquals("1~11件套餐", PunctuationGuard.accept("1~11件套餐", "1到11件套餐。"))
        assertEquals("v1.2.3", PunctuationGuard.accept("v1.2.3", "版本 1.2.3。"))
    }

    @Test
    fun rejectsExistingTerminalRewrite() {
        assertEquals("你好。", PunctuationGuard.accept("你好。", "你好？"))
        assertEquals("我去了，天气很好。", PunctuationGuard.accept("我去了天气很好。", "我去了，天气很好。"))
    }

    @Test
    fun keepsExistingCommaAndAcceptsOtherMarksWhenCtRepeatsIt() {
        val original = "我需要想一想， 还是明天再决定吧"
        assertEquals("我需要想一想， 还是明天再决定吧。", PunctuationGuard.accept(original, "我需要想一想，，还是明天再决定吧。"))
        assertEquals(original, PunctuationGuard.accept(original, "我需要想一想，，还是后天再决定吧。"))
    }
    @Test
    fun preservesNumbersPathsAndWhitespace() {
        assertEquals("小数3.14", PunctuationGuard.accept("小数3.14", "小数3，14。"))
        assertEquals("范围1~11件", PunctuationGuard.accept("范围1~11件", "范围1~，11件。"))
        assertEquals("https://example.com/foo", PunctuationGuard.accept("https://example.com/foo", "https://example.com，/foo。"))
        assertEquals("git --version", PunctuationGuard.accept("git --version", "git --version."))
        assertEquals("After  tomorrow 是星期三。", PunctuationGuard.accept("After  tomorrow 是星期三", "After tomorrow是星期三。"))
    }
}
