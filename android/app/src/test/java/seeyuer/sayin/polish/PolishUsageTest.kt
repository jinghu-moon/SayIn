package seeyuer.sayin.polish

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolishUsageTest {
    @Test fun `chat normalizes cached and reasoning details`() {
        val usage = PolishUsage.fromChat(JSONObject("""
            {"prompt_tokens":1000,"completion_tokens":300,
             "prompt_tokens_details":{"cached_tokens":200},
             "completion_tokens_details":{"reasoning_tokens":80}}
        """))!!
        assertEquals(1000L, usage.inputTotal)
        assertEquals(200L, usage.cacheRead)
        assertEquals(800L, usage.ordinaryInput)
        assertEquals(300L, usage.outputTotal)
        assertEquals(80L, usage.outputReasoning)
    }

    @Test fun `deepseek cache aliases are counted once`() {
        val usage = PolishUsage.fromChat(JSONObject("""
            {"prompt_tokens":1000,"completion_tokens":20,
             "prompt_cache_hit_tokens":200,"prompt_cache_miss_tokens":800}
        """))!!
        assertEquals(1000L, usage.inputTotal)
        assertEquals(200L, usage.cacheRead)
        assertEquals(800L, usage.ordinaryInput)
    }

    @Test fun `anthropic components form total input`() {
        val usage = PolishUsage.fromMessages(JSONObject("""
            {"input_tokens":700,"cache_creation_input_tokens":100,
             "cache_read_input_tokens":200,"output_tokens":50}
        """))!!
        assertEquals(1000L, usage.inputTotal)
        assertEquals(200L, usage.cacheRead)
        assertEquals(100L, usage.cacheWrite)
        assertEquals(700L, usage.ordinaryInput)
        assertEquals(50L, usage.outputTotal)
    }

    @Test fun `invalid and negative counters are rejected`() {
        assertNull(PolishUsage.fromChat(JSONObject("""{"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":20}}""")))
        assertNull(PolishUsage.fromMessages(JSONObject("""{"input_tokens":-1,"output_tokens":3}""")))
        assertNull(PolishUsage.fromChat(JSONObject("""{}""")))
    }
}
