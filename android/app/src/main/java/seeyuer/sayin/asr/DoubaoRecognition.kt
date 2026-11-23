package seeyuer.sayin.asr

import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

internal class DoubaoRecognition(
    private val client: OkHttpClient = cloudWebSocketClient,
    private val endpoint: String = ENDPOINT,
    private val finishTimeoutMs: Long = 10_000,
) {
    companion object {
        const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val RESOURCE = "volc.seedasr.sauc.duration"
        private const val END_WINDOW_MS = 2500
    }

    internal fun request(config: DoubaoCredentials): Request {
        val builder = Request.Builder().url(endpoint)
            .header("X-Api-Resource-Id", RESOURCE)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
        when (config.auth) {
            DoubaoAuth.AppKey -> builder.header("X-Api-Key", config.appKey)
            DoubaoAuth.AppIdToken -> builder.header("X-Api-App-Key", config.appId).header("X-Api-Access-Key", config.accessToken)
        }
        return builder.build()
    }

    internal fun parameters(hotwords: List<String>): String {
        val params = JSONObject().put("model_name", "bigmodel").put("enable_itn", true)
            .put("enable_punc", true).put("result_type", "full")
            .put("end_window_size", END_WINDOW_MS)
        if (hotwords.isNotEmpty()) {
            val words = JSONArray().apply { hotwords.forEach { put(JSONObject().put("word", it)) } }
            params.put("corpus", JSONObject().put("context", JSONObject().put("hotwords", words).toString()))
        }
        return JSONObject().put("user", JSONObject().put("uid", "sayin"))
            .put("audio", JSONObject().put("format", "pcm").put("rate", 16000).put("bits", 16).put("channel", 1))
            .put("request", params).toString()
    }

    fun task(config: DoubaoCredentials, hotwords: List<String>): CloudStreamingTask = object : CloudStreamingTask {
        override val request = request(config)
        override val start = CloudFrame.Audio(DoubaoProtocol.request(parameters(hotwords)).toByteString())
        override val needsReadyEvent = false
        override val chunkBytes = 6400

        override fun audio(chunk: ByteArray) = CloudFrame.Audio(DoubaoProtocol.audio(chunk, last = false).toByteString())

        override fun finish(tail: ByteArray) = listOf(CloudFrame.Audio(DoubaoProtocol.audio(tail, last = true).toByteString()))

        override fun receive(frame: CloudFrame): CloudEvent {
            if (frame !is CloudFrame.Audio) throw CloudRecognitionException(CloudIssue.Protocol)
            val packet = DoubaoProtocol.parse(frame.bytes.toByteArray())
            packet.errorCode?.let { throw CloudRecognitionException(serviceIssue(it)) }
            val json = JSONObject(packet.payload)
            val code = if (json.has("code")) json.getInt("code") else 0
            if (code != 0 && code != 20_000_000) throw CloudRecognitionException(serviceIssue(code))
            val result = json.optJSONObject("result")
            val text = if (result != null && result.has("text")) {
                (result.get("text") as? String)?.takeIf { it.length <= 65_536 }
                    ?: throw CloudRecognitionException(CloudIssue.Protocol)
            } else null
            return CloudEvent(transcript = text, finished = packet.final)
        }
    }

    suspend fun recognize(
        config: DoubaoCredentials,
        openSource: () -> PcmSource,
        onListening: () -> Unit,
        onTranscript: (String) -> Unit,
        hotwords: List<String> = emptyList(),
    ): String = streamCloud(task(config, hotwords), client, finishTimeoutMs, openSource, onListening, onTranscript).text
}

private fun serviceIssue(code: Int) = when (code) {
    45_000_030, 45_000_031 -> CloudIssue.Authentication
    45_000_081 -> CloudIssue.Timeout
    else -> CloudIssue.Service
}
