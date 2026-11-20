package seeyuer.sayin.polish

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishPreferenceRulesTest {
    @Test fun `terms require source equals spelling within limits`() {
        assertTrue(PolishPreferenceRules.validTerms(""))
        assertTrue(PolishPreferenceRules.validTerms("SayIn=赛音\nAPI=接口"))
        assertFalse(PolishPreferenceRules.validTerms("SayIn"))
        assertFalse(PolishPreferenceRules.validTerms("=赛音"))
        assertFalse(PolishPreferenceRules.validTerms("赛音="))
        assertFalse(PolishPreferenceRules.validTerms((1..21).joinToString("\n") { "a$it=b" }))
        assertFalse(PolishPreferenceRules.validTerms("a=" + "x".repeat(60)))
    }

    @Test fun `prompt is one line and at most two hundred code points`() {
        assertTrue(PolishPreferenceRules.validPrompt("请保持简短"))
        assertTrue(PolishPreferenceRules.validPrompt("😀".repeat(200)))
        assertFalse(PolishPreferenceRules.validPrompt("第一行\n第二行"))
        assertFalse(PolishPreferenceRules.validPrompt("a\u0000b"))
        assertFalse(PolishPreferenceRules.validPrompt("😀".repeat(201)))
    }
}
