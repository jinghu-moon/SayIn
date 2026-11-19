package seeyuer.sayin.polish

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishConfigTest {
    private fun config(provider: PolishProvider = PolishProvider.DeepSeek) =
        PolishConfig.preset(provider).copy(key = "key")

    @Test fun `json round trip preserves protocol and key`() {
        val original = config(PolishProvider.Anthropic)
        val parsed = PolishConfig.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, parsed)
        assertEquals(original.fingerprint, parsed.fingerprint)
    }

    @Test fun `fingerprint changes with endpoint and model but not key`() {
        val base = config()
        assertEquals(base.fingerprint, base.copy(key = "other").fingerprint)
        assertNotEquals(base.fingerprint, base.copy(baseUrl = "https://example.com").fingerprint)
        assertNotEquals(base.fingerprint, base.copy(model = "other").fingerprint)
    }

    @Test fun `validation rejects insecure endpoints and blank keys`() {
        assertTrue(config().copy(baseUrl = "http://api.example.com").validate()!!.contains("HTTPS"))
        assertTrue(config().copy(key = "").validate()!!.contains("Key"))
        assertNull(config().copy(baseUrl = "http://127.0.0.1:8080").validate(allowLoopbackHttp = true))
        assertEquals("https://api.deepseek.com", config().copy(baseUrl = " https://api.deepseek.com/ ").normalizedBaseUrl)
    }

    @Test fun `presets match the documented first batch`() {
        assertEquals(PolishProtocol.Chat, PolishConfig.preset(PolishProvider.DeepSeek).protocol)
        assertEquals("deepseek-flash", PolishConfig.preset(PolishProvider.DeepSeek).model)
        assertEquals(PolishProtocol.Chat, PolishConfig.preset(PolishProvider.Qwen).protocol)
        assertEquals("qwen3.8-flash", PolishConfig.preset(PolishProvider.Qwen).model)
        assertEquals(PolishMaxTokensField.MaxCompletionTokens,
            PolishConfig.preset(PolishProvider.OpenAi).maxTokensField)
        assertEquals("gpt-6-luna", PolishConfig.preset(PolishProvider.OpenAi).model)
        assertEquals(PolishProtocol.Messages, PolishConfig.preset(PolishProvider.Anthropic).protocol)
        assertEquals("claude-haiku-5-5", PolishConfig.preset(PolishProvider.Anthropic).model)
        assertEquals("2026-10-21", PolishConfig.preset(PolishProvider.Anthropic).apiVersion)
        assertEquals(PolishThinking.DisabledLow, PolishConfig.preset(PolishProvider.Anthropic).thinking)
    }
}
