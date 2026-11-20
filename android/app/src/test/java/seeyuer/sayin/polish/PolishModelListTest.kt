package seeyuer.sayin.polish

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PolishModelListTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun config() = PolishConfig(
        provider = PolishProvider.Custom,
        protocol = PolishProtocol.Chat,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "test-model",
        key = "secret",
        auth = PolishAuth.Bearer,
        apiVersion = "",
        maxTokensField = PolishMaxTokensField.MaxTokens,
        thinking = PolishThinking.None,
    )

    @Test fun `gateway model list uses configured auth and get`() {
        server.enqueue(MockResponse.Builder().code(200)
            .body("""{"data":[{"id":"zzz"},{"id":"aaa"},{"id":"aaa"}]}""").build())
        val result = PolishModelListClient(config(), allowLoopbackHttp = true).fetch()
        assertNull(result.error)
        assertEquals(listOf("aaa", "zzz"), result.models)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test fun `gateway failure keeps an error and no models`() {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        val result = PolishModelListClient(config(), allowLoopbackHttp = true).fetch()
        assertEquals("HTTP 401", result.error)
        assertEquals(emptyList<String>(), result.models)
    }
}
