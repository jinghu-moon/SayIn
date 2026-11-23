package seeyuer.sayin.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoRecognitionTest {
    @Test fun headersUseExactlyOneCredentialFormatAndHotwordsUseCorpusString() {
        val engine = DoubaoRecognition()
        val key = engine.request(config().credentials as DoubaoCredentials)
        assertEquals("test-secret", key.header("X-Api-Key"))
        assertNull(key.header("X-Api-App-Key"))
        assertNull(key.header("X-Api-Access-Key"))
        assertEquals(DoubaoRecognition.RESOURCE, key.header("X-Api-Resource-Id"))
        assertEquals(DoubaoRecognition.ENDPOINT, key.url.toString().replace("https://", "wss://"))
        val legacy = engine.request(DoubaoCredentials(DoubaoAuth.AppIdToken, "", "1234", "legacy-secret"))
        assertNull(legacy.header("X-Api-Key"))
        assertEquals("1234", legacy.header("X-Api-App-Key"))
        assertEquals("legacy-secret", legacy.header("X-Api-Access-Key"))
        val json = engine.parameters(config().hotwords)
        assertFalse(json.contains("test-secret"))
        val request = JSONObject(json).getJSONObject("request")
        assertEquals("full", request.getString("result_type"))
        assertEquals(2500, request.getInt("end_window_size"))
        val context = JSONObject(request.getJSONObject("corpus").getString("context"))
        assertEquals("SayIn", context.getJSONArray("hotwords").getJSONObject(0).getString("word"))
        assertFalse(request.has("context"))
    }

    @Test fun realWebSocketReplacesPartialSnapshotsAndDrainsTailBeforeFinal() = runBlocking {
        val received = mutableListOf<ByteArray>()
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received += bytes.toByteArray()
                when (bytes[1].toInt() and 255) {
                    0x10 -> {
                        webSocket.send(serverPacket("{\"result\":{\"text\":\"你\"}}", false).toByteString())
                        webSocket.send(serverPacket("{\"result\":{\"text\":\"你好\"}}", false).toByteString())
                    }
                    0x22 -> webSocket.send(sequencedServerPacket("{\"result\":{\"text\":\"你好，SayIn。\"}}", 3, 5).toByteString())
                }
            }
        }) { engine, _ ->
            val microphone = Samples(5)
            val seen = mutableListOf<String>()
            val text = engine.recognize(config().credentials as DoubaoCredentials, { microphone }, {}, { seen += it })
            assertEquals(listOf("你", "你好", "你好，SayIn。"), seen)
            assertEquals("你好，SayIn。", text)
            assertTrue(microphone.closed)
            assertTrue(microphone.stopped)
            assertEquals(listOf(0x10, 0x20, 0x20, 0x22), received.map { it[1].toInt() and 255 })
            assertEquals(listOf(6400, 6400, 3200), received.drop(1).map { it.size - 8 })
        }
    }

    @Test fun closeAfterPartialIsFailureNeverSuccess() = runBlocking {
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes[1].toInt() and 255 == 0x22) {
                    webSocket.send(serverPacket("{\"result\":{\"text\":\"草稿\"}}", false).toByteString())
                    webSocket.close(1000, "done without final")
                }
            }
        }) { engine, _ ->
            var last = ""
            val error = try { engine.recognize(config().credentials as DoubaoCredentials, { Samples(1) }, {}, { last = it }); null }
            catch (failure: CloudRecognitionException) { failure }
            assertEquals("草稿", last)
            assertEquals(CloudIssue.Network, error?.issue)
        }
    }

    @Test fun missingFinalTimesOutAndReleasesMicrophone() = runBlocking {
        withServer(object : WebSocketListener() {}) { engine, _ ->
            val microphone = Samples(1)
            val error = try { engine.recognize(config().credentials as DoubaoCredentials, { microphone }, {}, {}); null }
            catch (failure: CloudRecognitionException) { failure }
            assertEquals(CloudIssue.Timeout, error?.issue)
            assertTrue(microphone.closed)
        }
    }

    @Test fun earlyFinalIsRejectedAndDoesNotPretendUserFinished() = runBlocking {
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes[1].toInt() and 255 == 0x10) webSocket.send(serverPacket("{}", true).toByteString())
            }
        }) { engine, _ ->
            val source = object : PcmSource {
                override fun read(): FloatArray { Thread.sleep(30); return FloatArray(1600) }
                override fun requestStop() = Unit
                override fun close() = Unit
            }
            val error = try { withTimeout(3000) { engine.recognize(config().credentials as DoubaoCredentials, { source }, {}, {}) }; null }
            catch (failure: CloudRecognitionException) { failure }
            assertEquals(CloudIssue.Protocol, error?.issue)
        }
    }

    @Test fun handshakeFailuresDistinguishCredentialsAccessAndQuotaWithoutRecordingOrRetry() = runBlocking {
        for ((status, issue) in listOf(401 to CloudIssue.Authentication, 403 to CloudIssue.AccessDenied, 429 to CloudIssue.Quota)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(status).build())
                server.start()
                val engine = DoubaoRecognition(endpoint = server.url("/").toString())
                var opened = false
                val error = try { engine.recognize(config().credentials as DoubaoCredentials, { opened = true; Samples(1) }, {}, {}); null }
                catch (failure: CloudRecognitionException) { failure }
                assertEquals(issue, error?.issue)
                assertFalse(opened)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cancellationReleasesCaptureWithoutSendingLastPacket() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val lastReceived = CountDownLatch(1)
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes[1].toInt() and 255 == 0x22) lastReceived.countDown()
            }
        }) { engine, _ ->
            val source = object : PcmSource {
                @Volatile var closed = false
                override fun read(): FloatArray { Thread.sleep(20); return FloatArray(1600) }
                override fun requestStop() { closed = true }
                override fun close() { closed = true }
            }
            var completed = false
            val job = launch(Dispatchers.Default) { engine.recognize(config().credentials as DoubaoCredentials, { source }, { entered.complete(Unit) }, {}); completed = true }
            withTimeout(3000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(source.closed)
            assertFalse(completed)
            assertFalse(lastReceived.await(100, TimeUnit.MILLISECONDS))
        }
    }

    private suspend fun withServer(listener: WebSocketListener, block: suspend (DoubaoRecognition, MockWebServer) -> Unit) {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
                server.start()
                block(DoubaoRecognition(client, server.url("/").toString(), finishTimeoutMs = 300), server)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private class Samples(private var remaining: Int) : PcmSource {
        var stopped = false
        var closed = false
        override fun read(): FloatArray? = if (remaining-- > 0) FloatArray(1600) else null
        override fun requestStop() { stopped = true }
        override fun close() { closed = true }
    }
}
