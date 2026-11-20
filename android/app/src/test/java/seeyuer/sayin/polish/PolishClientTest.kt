package seeyuer.sayin.polish

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolishClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun base(provider: PolishProvider, protocol: PolishProtocol = PolishProtocol.Chat): PolishConfig =
        PolishConfig(
            provider = provider, protocol = protocol, baseUrl = server.url("/").toString().trimEnd('/'),
            model = when {
                provider == PolishProvider.OpenAi -> "gpt-6-luna"
                provider == PolishProvider.Anthropic -> "claude-haiku-5-5"
                protocol == PolishProtocol.Chat -> "test-chat"
                else -> "test-messages"
            },
            key = "secret-key", auth = if (protocol == PolishProtocol.Chat) PolishAuth.Bearer else PolishAuth.XApiKey,
            apiVersion = if (protocol == PolishProtocol.Messages) "2026-10-21" else "",
            maxTokensField = if (provider == PolishProvider.OpenAi) PolishMaxTokensField.MaxCompletionTokens else PolishMaxTokensField.MaxTokens,
            thinking = if (protocol == PolishProtocol.Messages) PolishThinking.DisabledLow else PolishThinking.None,
        )

    private fun response(code: Int = 200, body: String): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    private fun chatResponse(content: String = "clean text", finish: String = "stop"): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", content))
            .put("finish_reason", finish)))
        .put("usage", JSONObject().put("prompt_tokens", 12).put("completion_tokens", 5))
        .toString()

    private fun messagesResponse(text: String = "译文", stop: String = "end_turn"): String = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("stop_reason", stop)
        .put("usage", JSONObject().put("input_tokens", 7).put("output_tokens", 3))
        .toString()

    private fun client(config: PolishConfig) = PolishClient(config, allowLoopbackHttp = true)

    @Test fun `chat success uses one request and parses usage`() {
        server.enqueue(response(body = chatResponse()))
        val result = client(base(PolishProvider.DeepSeek)).call(PolishMode.Voice, text = "嗯那个")
        assertTrue(result is PolishCallResult.Success)
        result as PolishCallResult.Success
        assertEquals("clean text", result.text)
        assertEquals(12L, result.inputTokens)
        assertEquals(5L, result.outputTokens)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer secret-key", request.headers["Authorization"])
        assertEquals("/chat/completions", request.target)
        val body = JSONObject(request.body!!.utf8())
        assertTrue(body.has("max_tokens"))
        assertFalse(body.has("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("嗯那个"))
    }

    @Test fun `openai preset uses max completion tokens and no temperature`() {
        server.enqueue(response(body = chatResponse()))
        client(base(PolishProvider.OpenAi)).call(PolishMode.Voice, text = "hello")
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertTrue(body.has("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertEquals("none", body.getString("reasoning_effort"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `unverified openai model does not inherit reasoning or completion field`() {
        server.enqueue(response(body = chatResponse("ok")))
        client(base(PolishProvider.OpenAi).copy(model = "custom-gpt")).call(PolishMode.Voice, text = "hello")
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertTrue(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun `unverified anthropic model does not inherit thinking fields`() {
        server.enqueue(response(body = messagesResponse("ok")))
        client(base(PolishProvider.Anthropic, PolishProtocol.Messages).copy(model = "custom-claude"))
            .call(PolishMode.Voice, text = "hello")
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(body.has("thinking"))
        assertFalse(body.has("output_config"))
    }

    @Test fun `anthropic success uses system and thinking fields`() {
        server.enqueue(response(body = messagesResponse()))
        val result = client(base(PolishProvider.Anthropic, PolishProtocol.Messages))
            .call(PolishMode.Translate, targetLanguage = "ja", text = "请改到周五")
        assertTrue(result is PolishCallResult.Success)
        assertEquals("译文", (result as PolishCallResult.Success).text)
        val request = server.takeRequest()
        assertEquals("/messages", request.target)
        assertEquals("secret-key", request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertTrue(request.headers["Authorization"] == null)
        val body = JSONObject(request.body!!.utf8())
        assertTrue(body.getString("system").contains("目标语言：ja"))
        assertTrue(body.has("max_tokens"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
    }

    @Test fun `truncated refusal and tool responses are not accepted`() {
        server.enqueue(response(body = chatResponse(finish = "length")))
        assertFailure(PolishFailure.Truncated, base(PolishProvider.DeepSeek))
        server.enqueue(response(body = chatResponse(finish = "content_filter")))
        assertFailure(PolishFailure.Refused, base(PolishProvider.DeepSeek))
        server.enqueue(response(body = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", "x").put("tool_calls", JSONArray().put(JSONObject())))
            .put("finish_reason", "stop"))).toString()))
        assertFailure(PolishFailure.InvalidResponse, base(PolishProvider.DeepSeek))
    }

    @Test fun `anthropic tool and refusal are rejected`() {
        server.enqueue(response(body = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "tool_use")))
            .put("stop_reason", "end_turn").toString()))
        assertFailure(PolishFailure.InvalidResponse, base(PolishProvider.Anthropic, PolishProtocol.Messages))
        server.enqueue(response(body = messagesResponse(stop = "refusal")))
        assertFailure(PolishFailure.Refused, base(PolishProvider.Anthropic, PolishProtocol.Messages))
    }

    @Test fun `http failures map without a second request`() {
        server.enqueue(response(401, ""))
        assertFailure(PolishFailure.Unauthorized, base(PolishProvider.DeepSeek))
        assertEquals(1, server.requestCount)
        server.takeRequest()
        server.enqueue(response(500, ""))
        assertFailure(PolishFailure.Server, base(PolishProvider.DeepSeek))
        assertEquals(2, server.requestCount)
    }

    @Test fun `redirect is not followed`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
        assertFailure(PolishFailure.InvalidResponse, base(PolishProvider.DeepSeek))
        assertEquals(1, server.requestCount)
    }

    @Test fun `oversized body and invalid utf8 are rejected`() {
        server.enqueue(response(body = "x".repeat(70_000)))
        assertFailure(PolishFailure.TooLarge, base(PolishProvider.DeepSeek))
        server.takeRequest()
        server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(0xC3.toByte(), 0x28))).build())
        assertFailure(PolishFailure.InvalidResponse, base(PolishProvider.DeepSeek))
    }

    @Test fun `streaming chat emits partials and validates the final reason`() {
        val body = """
            data: {"choices":[{"delta":{"content":"clean "},"finish_reason":null}]}
            data: {"choices":[{"delta":{"content":"text"},"finish_reason":"stop"}]}
            data: [DONE]
        """.trimIndent()
        server.enqueue(response(body = body))
        val partials = mutableListOf<String>()
        val result = client(base(PolishProvider.DeepSeek)).callStreaming(
            PolishMode.Voice, text = "嗯那个", onPartial = { partials += it },
        )
        assertTrue(result is PolishCallResult.Success)
        assertEquals("clean text", (result as PolishCallResult.Success).text)
        assertEquals(listOf("clean ", "clean text"), partials)
        assertEquals(1, server.requestCount)
    }

    @Test fun `streaming anthropic accepts text deltas and end turn`() {
        val body = """
            data: {"type":"content_block_start","content_block":{"type":"text","text":""}}
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"译"}}
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}
            data: {"type":"message_stop"}
        """.trimIndent()
        server.enqueue(response(body = body))
        val result = client(base(PolishProvider.Anthropic, PolishProtocol.Messages))
            .callStreaming(PolishMode.Voice, text = "x")
        assertTrue(result is PolishCallResult.Success)
        assertEquals("译", (result as PolishCallResult.Success).text)
    }

    @Test fun `streaming truncation and tool blocks are rejected`() {
        server.enqueue(response(body = """
            data: {"choices":[{"delta":{"content":"half"},"finish_reason":"length"}]}
            data: [DONE]
        """.trimIndent()))
        assertFailureStreaming(PolishFailure.Truncated, base(PolishProvider.DeepSeek))
        server.enqueue(response(body = """
            data: {"type":"content_block_start","content_block":{"type":"tool_use"}}
            data: {"type":"message_stop"}
        """.trimIndent()))
        assertFailureStreaming(PolishFailure.InvalidResponse, base(PolishProvider.Anthropic, PolishProtocol.Messages))
    }

    @Test fun `connection test accepts a bounded response even when the budget truncated it`() {
        server.enqueue(response(body = chatResponse(content = "OK", finish = "length")))
        val result = client(base(PolishProvider.DeepSeek)).test()
        assertTrue(result is PolishCallResult.Success)
        assertEquals("OK", (result as PolishCallResult.Success).text)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(512, body.getInt("max_tokens"))
    }

    @Test fun `missing key is rejected before network`() {
        val result = client(base(PolishProvider.DeepSeek).copy(key = "")).call(PolishMode.Voice, text = "x")
        assertEquals(PolishFailure.MissingConfig, (result as PolishCallResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    private fun assertFailure(expected: PolishFailure, config: PolishConfig) {
        val result = client(config).call(PolishMode.Voice, text = "test")
        assertTrue(result is PolishCallResult.Failure)
        assertEquals(expected, (result as PolishCallResult.Failure).kind)
    }

    private fun assertFailureStreaming(expected: PolishFailure, config: PolishConfig) {
        val result = client(config).callStreaming(PolishMode.Voice, text = "test")
        assertTrue(result is PolishCallResult.Failure)
        assertEquals(expected, (result as PolishCallResult.Failure).kind)
    }
}
