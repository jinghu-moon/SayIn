package seeyuer.sayin.asr

import java.util.UUID
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

internal class QwenRecognition(private val endpoint: String? = null) {
    companion object {
        const val MODEL = "qwen-audio-3.1-asr-flash-streaming"
    }

    fun task(credentials: QwenCredentials, hotwords: List<String>): CloudStreamingTask =
        Task(credentials, hotwords)

    private inner class Task(credentials: QwenCredentials, hotwords: List<String>) : CloudStreamingTask {
        private val taskId = UUID.randomUUID().toString()
        private val committed = StringBuilder()
        private var lastCommittedId = 0
        private var partialId: Int? = null
        private var started = false

        override val request = Request.Builder()
            .url(endpoint ?: "wss://${credentials.workspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference")
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .build()
        override val needsReadyEvent = true
        override val chunkBytes = 3200
        override val start: CloudFrame = command("run-task", JSONObject()
            .put("task_group", "audio").put("task", "asr").put("function", "recognition").put("model", MODEL)
            .put("parameters", JSONObject().put("format", "pcm").put("sample_rate", 16000)
                .put("max_sentence_silence", 2500).put("vad_model", "near_meeting_16k").apply {
                if (hotwords.isNotEmpty()) put("vocabulary", JSONObject().apply {
                    hotwords.forEach { put(it, 4) }
                })
            })
            .put("input", JSONObject()))

        override fun audio(chunk: ByteArray) = CloudFrame.Audio(chunk.toByteString())

        override fun finish(tail: ByteArray): List<CloudFrame> = buildList {
            if (tail.isNotEmpty()) add(audio(tail))
            add(command("finish-task", JSONObject().put("input", JSONObject())))
        }

        private fun command(action: String, payload: JSONObject) = CloudFrame.Json(
            JSONObject().put("header", JSONObject().put("action", action)
                .put("task_id", taskId).put("streaming", "duplex"))
                .put("payload", payload).toString(),
        )

        override fun receive(frame: CloudFrame): CloudEvent {
            if (frame !is CloudFrame.Json || frame.text.length > 1_048_576) invalid()
            val json = JSONObject(frame.text)
            val header = json.getJSONObject("header")
            val event = header.getString("event")
            if (event == "error") throw CloudRecognitionException(serviceIssue(header.optString("error_code")))
            if (header.getString("task_id") != taskId) invalid("wrong-task")
            val payload = json.optJSONObject("payload")
            val usage = payload?.optJSONObject("usage")?.let {
                CloudUsage(it.counter("input_tokens"), it.counter("output_tokens"))
            }
            return when (event) {
                "task-started" -> {
                    if (started) invalid()
                    started = true
                    CloudEvent(ready = true)
                }
                "result-generated" -> {
                    if (!started) invalid()
                    val sentence = payload?.optJSONObject("output")?.optJSONObject("sentence") ?: invalid()
                    CloudEvent(transcript = updateSentence(sentence), usage = usage)
                }
                "task-finished" -> {
                    if (!started || partialId != null) invalid("unconfirmed-tail")
                    CloudEvent(transcript = committed.toString(), finished = true, usage = usage)
                }
                "task-failed" -> throw CloudRecognitionException(serviceIssue(header.optString("error_code")))
                else -> CloudEvent()
            }
        }

        private fun updateSentence(sentence: JSONObject): String? {
            if (sentence.optBoolean("heartbeat")) return null
            val text = sentence.get("text") as? String ?: invalid()
            if (text.isEmpty()) return null
            val id = sentence.getInt("sentence_id")
            if (id <= 0) invalid()
            if (id <= lastCommittedId) return null
            if (partialId?.let { it != id } == true) invalid("sentence-order")
            val addSpace = committed.lastOrNull()?.let { it.isAsciiWord() && text.first().isAsciiWord() } == true
            if (committed.length + text.length + (if (addSpace) 1 else 0) > 65_536) invalid("transcript-size")
            if (!sentence.optBoolean("sentence_end")) {
                partialId = id
                return committed.toString() + (if (addSpace) " " else "") + text
            }
            if (addSpace) committed.append(' ')
            committed.append(text)
            lastCommittedId = id
            partialId = null
            return committed.toString()
        }
    }
}

private fun JSONObject.counter(key: String): Long? {
    val value = opt(key)
    val number = if (value is Long) value else (value as? Int)?.toLong() ?: return null
    return number.takeIf { it >= 0 }
}

private fun Char.isAsciiWord() = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

private fun invalid(detail: String = "qwen-event"): Nothing = throw CloudRecognitionException(CloudIssue.Protocol, detail)

private fun serviceIssue(code: String): CloudIssue = when (code) {
    "InvalidApiKey", "AuthenticationFailed", "InvalidAccessToken" -> CloudIssue.Authentication
    "AccessDenied", "AccessDenied.Unpurchased", "Workspace.AccessDenied", "ModelNotFound" -> CloudIssue.AccessDenied
    "Throttling", "Throttling.RateQuota", "AllocationQuota.FreeTierOnly", "QuotaExceeded" -> CloudIssue.Quota
    "RequestTimeout", "TaskTimeout" -> CloudIssue.Timeout
    else -> CloudIssue.Service
}
