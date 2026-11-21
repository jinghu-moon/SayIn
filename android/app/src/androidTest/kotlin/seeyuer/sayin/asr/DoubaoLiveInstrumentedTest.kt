package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Explicit opt-in only. Secrets remain inside the app; no microphone, LAN send or user audio. */
@RunWith(AndroidJUnit4::class)
class DoubaoLiveInstrumentedTest {
    @Test fun savedConfigurationHasAccessToStreamingResource() = runBlocking {
        val config = liveConfiguration()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val result = CompletableDeferred<String?>()
        val socket = client.newWebSocket(DoubaoRecognition().request(config.credentials as DoubaoCredentials), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { result.complete(null); webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Read only on phone. Export a fixed vocabulary, never body, headers or identifiers.
                val description = try {
                    (response?.peekBody(4096)?.string().orEmpty() + " " + response?.header("X-Api-Message").orEmpty()).lowercase()
                } catch (_: Exception) { "" }
                val reason = when {
                    "grant not found" in description || "not grant" in description || "no grant" in description -> "MissingServiceGrant"
                    "quota" in description -> "Quota"
                    "expired" in description -> "ExpiredCredential"
                    "disabled" in description -> "DisabledCredential"
                    "invalid api" in description || "invalid key" in description || "key not found" in description -> "InvalidApiKey"
                    "project" in description -> "ProjectRestriction"
                    "access denied" in description -> "AccessDeniedUnspecified"
                    else -> "Unclassified"
                }
                val hints = listOf("api", "key", "app", "token", "invalid", "expired", "resource", "grant", "permission",
                    "project", "disabled", "not found", "denied", "not exist", "bind", "未开通", "权限", "资源", "密钥")
                    .filter { it in description }.joinToString(",")
                val serviceCode = response?.header("X-Api-Status-Code")?.toLongOrNull()
                result.complete("HTTP=${response?.code}; reason=$reason; code=$serviceCode; hints=[$hints]; descriptionChars=${description.length}")
                response?.close()
            }
        })
        try {
            val error = withTimeout(12_000) { result.await() }
            assertTrue("Streaming resource access: $error", error == null)
        } finally {
            socket.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun savedConfigurationCompletesTheSettingsSilenceProbe() = runBlocking {
        val config = liveConfiguration()
        val result = recognizeCloud(config, ::cloudProbeSource, {}, {})
        assertTrue("Silence must not invent text", result.text.isBlank())
    }

    private fun liveConfiguration(): CloudConfiguration {
        assumeTrue("Live cloud test requires explicit opt-in", InstrumentationRegistry.getArguments().getString("liveCloud") == "true")
        val config = CloudConfigurationStore(InstrumentationRegistry.getInstrumentation().targetContext).load()
        assumeTrue("No saved cloud configuration", config?.doubao != null)
        return CloudConfiguration(checkNotNull(config?.doubao), config.hotwords)
    }

    @Test fun savedConfigurationRecognizesFixtureAndReceivesFinal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = liveConfiguration()
        val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }.copyOf(99_200)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
        var partialSeen = false
        val final = recognizeCloud(checkNotNull(config), {
            object : PcmSource {
                private var offset = 0
                override fun read(): FloatArray? {
                    if (offset == samples.size) return null
                    Thread.sleep(100)
                    val end = minOf(offset + 1600, samples.size)
                    return samples.copyOfRange(offset, end).also { offset = end }
                }
                override fun requestStop() = Unit
                override fun close() = Unit
            }
        }, {}, { if (it.isNotBlank()) partialSeen = true })
        assertTrue("Expected speech from fixture", partialSeen && final.text.isNotBlank())
    }
}
