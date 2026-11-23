package seeyuer.sayin.asr

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QwenRecognitionTest {
    private val credentials = QwenCredentials("test-api-key", "ws-test123")

    @Test fun requestBindsKeyToOfficialWorkspaceAndUsesInstantHotwords() {
        val task = QwenRecognition().task(credentials, listOf("SayIn"))
        assertEquals("ws-test123.cn-beijing.maas.aliyuncs.com", task.request.url.host)
        assertEquals("Bearer test-api-key", task.request.header("Authorization"))
        assertNull(task.request.header("X-Api-Key"))
        val payload = JSONObject((task.start as CloudFrame.Json).text).getJSONObject("payload")
        assertEquals(QwenRecognition.MODEL, payload.getString("model"))
        assertEquals(2500, payload.getJSONObject("parameters").getInt("max_sentence_silence"))
        assertEquals("near_meeting_16k", payload.getJSONObject("parameters").getString("vad_model"))
        assertEquals(4, payload.getJSONObject("parameters").getJSONObject("vocabulary").getInt("SayIn"))
        assertFalse(payload.toString().contains("test-api-key"))
        assertThrows(IllegalArgumentException::class.java) { QwenCredentials("key", "ws-x.evil.test") }
    }

    @Test fun waitsForReadyDrainsTailAndFinalizesWholeTaskWithCumulativeUsage() = runBlocking {
        val frames = CopyOnWriteArrayList<Int>()
        val previews = CopyOnWriteArrayList<String>()
        var id = ""
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val header = JSONObject(text).getJSONObject("header")
                when (header.getString("action")) {
                    "run-task" -> {
                        id = header.getString("task_id")
                        webSocket.send(event(id, "task-started"))
                    }
                    "finish-task" -> {
                        assertEquals(id, header.getString("task_id"))
                        webSocket.send(result(id, 1, "你好", false))
                        webSocket.send(result(id, 1, "你好。", true, 20, 3))
                        webSocket.send(result(id, 1, "你好。", true, 20, 3))
                        webSocket.send(result(id, 2, "SayIn", true, 40, 8))
                        webSocket.send(event(id, "task-finished"))
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames += bytes.size }
        }) { task ->
            val source = Source()
            val completed = streamCloud(task, cloudWebSocketClient, 1000, { source }, {}, { previews += it })
            assertEquals("你好。SayIn", completed.text)
            assertEquals(CloudUsage(40, 8), completed.usage)
            assertEquals(listOf(3200, 800), frames)
            assertEquals(listOf("你好", "你好。", "你好。SayIn", "你好。SayIn"), previews)
            assertTrue(source.closed)
        }
    }

    @Test fun sentenceEndAloneCannotFinalizeAndTimeoutClosesSource() = runBlocking {
        withServer(respond { id, socket ->
            socket.send(result(id, 1, "草稿。", true))
        }) { task ->
            val source = Source()
            val previews = CopyOnWriteArrayList<String>()
            val error = runCatching {
                streamCloud(task, cloudWebSocketClient, 500, { source }, {}, { previews += it })
            }.exceptionOrNull() as CloudRecognitionException
            assertEquals(CloudIssue.Timeout, error.issue)
            assertEquals(listOf("草稿。"), previews)
            assertTrue(source.closed)
        }
    }

    @Test fun prematureFinishDoesNotOpenMicrophoneAndPartialTailIsNotSuccessful() = runBlocking {
        withServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = JSONObject(text).getJSONObject("header").getString("task_id")
                webSocket.send(event(id, "task-finished"))
            }
        }) { task ->
            val error = runCatching {
                streamCloud(task, cloudWebSocketClient, 1000, { error("Microphone opened") }, {}, {})
            }.exceptionOrNull() as CloudRecognitionException
            assertEquals(CloudIssue.Protocol, error.issue)
        }
        withServer(respond { id, socket ->
            socket.send(result(id, 1, "半句", false))
            socket.send(event(id, "task-finished"))
        }) { task ->
            val error = runCatching {
                streamCloud(task, cloudWebSocketClient, 1000, { Source() }, {}, {})
            }.exceptionOrNull() as CloudRecognitionException
            assertEquals(CloudIssue.Protocol, error.issue)
        }
    }

    @Test fun unpurchasedModelReportsAccessDeniedWithoutProviderMessage() = runBlocking {
        withServer(respond { id, socket ->
            val failed = JSONObject(event(id, "task-failed"))
            failed.getJSONObject("header").put("error_code", "AccessDenied.Unpurchased")
                .put("error_message", "provider details must not leave the protocol layer")
            socket.send(failed.toString())
        }) { task ->
            val source = Source()
            val error = runCatching {
                streamCloud(task, cloudWebSocketClient, 1000, { source }, {}, {})
            }.exceptionOrNull() as CloudRecognitionException
            assertEquals(CloudIssue.AccessDenied, error.issue)
            assertEquals("Cloud ASR: AccessDenied", error.message)
            assertTrue(source.closed)
        }
    }

    private fun respond(onFinish: (String, WebSocket) -> Unit) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val header = JSONObject(text).getJSONObject("header")
            val id = header.getString("task_id")
            when (header.getString("action")) {
                "run-task" -> webSocket.send(event(id, "task-started"))
                "finish-task" -> onFinish(id, webSocket)
            }
        }
    }

    private suspend fun withServer(listener: WebSocketListener, block: suspend (CloudStreamingTask) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
            server.start()
            block(QwenRecognition(server.url("/").toString()).task(credentials, emptyList()))
        }
    }

    private fun result(id: String, sentenceId: Int, text: String, end: Boolean,
                       inputTokens: Int? = null, outputTokens: Int? = null): String {
        val sentence = JSONObject().put("sentence_id", sentenceId).put("text", text).put("sentence_end", end)
        val payload = JSONObject().put("output", JSONObject().put("sentence", sentence))
        if (inputTokens != null) payload.put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
        return event(id, "result-generated", payload)
    }

    private fun event(id: String, event: String, payload: JSONObject = JSONObject()) =
        JSONObject().put("header", JSONObject().put("task_id", id).put("event", event)).put("payload", payload).toString()

    private class Source : PcmSource {
        private var remaining = 2
        var closed = false
        override fun read(): FloatArray? = when (remaining--) {
            2 -> FloatArray(1600)
            1 -> FloatArray(400)
            else -> null
        }
        override fun requestStop() = Unit
        override fun close() { closed = true }
    }
}
