package seeyuer.sayin.polish

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolishCatalogFetcherTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer(); server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun fetcher() = PolishCatalogFetcher(server.url("/all.json").toString())

    private fun validJson(): String =
        """{"deepseek":{"models":{"deepseek-flash":{"id":"deepseek-flash","name":"Flash","modalities":{"output":["text"]}}}}}"""

    @Test fun `200 validates and returns catalog without credentials`() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"v1\"").body(validJson()).build())
        val result = fetcher().fetch(null)
        assertTrue(result is PolishCatalogFetch.Updated)
        result as PolishCatalogFetch.Updated
        assertEquals("\"v1\"", result.etag)
        assertEquals("deepseek-flash", result.catalog.modelsFor(PolishProvider.DeepSeek).single().id)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["x-api-key"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test fun `304 sends etag and does not replace catalog`() {
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = fetcher().fetch("\"v1\"")
        assertTrue(result is PolishCatalogFetch.NotModified)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("\"v1\"", request.headers["If-None-Match"])
    }

    @Test fun `invalid empty and oversized catalogs are rejected`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"deepseek":{"models":{}}}""").build())
        assertTrue(fetcher().fetch(null) is PolishCatalogFetch.Failure)
        server.enqueue(MockResponse.Builder().code(200).body("not json").build())
        assertTrue(fetcher().fetch(null) is PolishCatalogFetch.Failure)
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(2_100_000)).build())
        assertTrue(fetcher().fetch(null) is PolishCatalogFetch.Failure)
    }
}
