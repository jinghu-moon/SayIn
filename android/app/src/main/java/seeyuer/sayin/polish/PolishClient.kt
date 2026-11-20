package seeyuer.sayin.polish

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class PolishFailure {
    MissingConfig, Unauthorized, RateLimited, Timeout, Network, Server, InvalidResponse,
    Truncated, Refused, TooLarge, Unknown
}

internal sealed interface PolishCallResult {
    data class Success(
        val text: String,
        val usage: PolishUsage?,
        val durationMillis: Long,
        val httpStatus: Int,
        val responseModel: String? = null,
    ) : PolishCallResult {
        val inputTokens: Long? get() = usage?.inputTotal
        val outputTokens: Long? get() = usage?.outputTotal
    }
    data class Failure(
        val kind: PolishFailure,
        val httpStatus: Int? = null,
        val durationMillis: Long = 0,
        val usage: PolishUsage? = null,
        val requestSent: Boolean = true,
    ) : PolishCallResult
}

internal class PolishClient(
    private val config: PolishConfig,
    private val client: OkHttpClient = defaultClient(),
    private val allowLoopbackHttp: Boolean = false,
) {
    fun call(mode: PolishMode, targetLanguage: String? = null, appName: String? = null,
             scene: PolishScene? = null, customRewrite: String? = null, text: String,
             hotwords: String? = null, onCall: (Call) -> Unit = {}): PolishCallResult {
        val system = PolishPrompts.system(mode, targetLanguage, appName, customRewrite, scene, hotwords)
        return callRaw(system, PolishPrompts.user(text), maxTokens = 4096, onCall = onCall)
    }

    fun test(): PolishCallResult =
        callRaw("你是连接测试器。只输出 OK，不解释。", "<input_text>OK</input_text>",
            maxTokens = 512, acceptTruncated = true)

    fun callStreaming(mode: PolishMode, targetLanguage: String? = null, appName: String? = null,
                      scene: PolishScene? = null, customRewrite: String? = null, text: String,
                      hotwords: String? = null, onCall: (Call) -> Unit = {},
                      onPartial: (String) -> Unit = {}): PolishCallResult {
        val system = PolishPrompts.system(mode, targetLanguage, appName, customRewrite, scene, hotwords)
        return streamRaw(system, PolishPrompts.user(text), 4096, onCall, onPartial)
    }

    fun callRaw(system: String, user: String, maxTokens: Int, onCall: (Call) -> Unit = {},
                acceptTruncated: Boolean = false): PolishCallResult {
        val error = config.validate(allowLoopbackHttp)
        if (error != null) return PolishCallResult.Failure(PolishFailure.MissingConfig, requestSent = false)
        val started = System.nanoTime()
        val request = runCatching { request(system, user, maxTokens, stream = false) }.getOrElse {
            return PolishCallResult.Failure(PolishFailure.InvalidResponse, requestSent = false)
        }
        return try {
            client.newCall(request).also(onCall).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) {
                    return PolishCallResult.Failure(httpFailure(response.code), response.code, elapsed)
                }
                val bytes = readBounded(response.body?.byteStream())
                    ?: return PolishCallResult.Failure(PolishFailure.TooLarge, response.code, elapsed)
                val body = decodeStrict(bytes)
                    ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, response.code, elapsed)
                parse(body, response.code, elapsed, acceptTruncated)
            }
        } catch (_: SocketTimeoutException) {
            PolishCallResult.Failure(PolishFailure.Timeout, durationMillis = elapsed(started))
        } catch (_: IOException) {
            PolishCallResult.Failure(PolishFailure.Network, durationMillis = elapsed(started))
        } catch (_: Exception) {
            PolishCallResult.Failure(PolishFailure.Unknown, durationMillis = elapsed(started))
        }
    }

    private fun request(system: String, user: String, maxTokens: Int, stream: Boolean): Request {
        val url = endpoint()
        val body = when (config.protocol) {
            PolishProtocol.Chat -> chatBody(system, user, maxTokens, stream)
            PolishProtocol.Messages -> messagesBody(system, user, maxTokens, stream)
        }
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
        when (config.auth) {
            PolishAuth.Bearer -> builder.header("Authorization", "Bearer ${config.key}")
            PolishAuth.XApiKey -> {
                builder.header("x-api-key", config.key)
                if (config.protocol == PolishProtocol.Messages) {
                    builder.header("anthropic-version", config.apiVersion.ifBlank { "2023-06-01" })
                }
            }
        }
        return builder.build()
    }

    private fun endpoint(): String {
        val base = config.normalizedBaseUrl
        return when (config.protocol) {
            PolishProtocol.Chat -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            PolishProtocol.Messages -> if (base.endsWith("/messages")) base else "$base/messages"
        }
    }

    private fun chatBody(system: String, user: String, maxTokens: Int, stream: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("stream", stream)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        // Only the exact verified profile may use its verified-only fields. A manually
        // entered model falls back to the minimal Chat Completions contract.
        val exactOpenAi = config.provider == PolishProvider.OpenAi && config.model == "gpt-6-luna"
        val field = if (exactOpenAi && config.maxTokensField == PolishMaxTokensField.MaxCompletionTokens)
            "max_completion_tokens" else "max_tokens"
        body.put(field, maxTokens)
        if (exactOpenAi) body.put("reasoning_effort", "none")
        return body
    }

    private fun messagesBody(system: String, user: String, maxTokens: Int, stream: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put("max_tokens", maxTokens)
            .put("stream", stream)
        if (config.provider == PolishProvider.Anthropic && config.model == "claude-haiku-5-5" &&
            config.thinking == PolishThinking.DisabledLow) {
            body.put("thinking", JSONObject().put("type", "disabled"))
            body.put("output_config", JSONObject().put("effort", "low"))
        }
        return body
    }

    private fun streamRaw(system: String, user: String, maxTokens: Int,
                          onCall: (Call) -> Unit, onPartial: (String) -> Unit): PolishCallResult {
        val error = config.validate(allowLoopbackHttp)
        if (error != null) return PolishCallResult.Failure(PolishFailure.MissingConfig, requestSent = false)
        val started = System.nanoTime()
        val request = runCatching { request(system, user, maxTokens, stream = true) }.getOrElse {
            return PolishCallResult.Failure(PolishFailure.InvalidResponse, requestSent = false)
        }
        return try {
            client.newCall(request).also(onCall).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) {
                    return PolishCallResult.Failure(httpFailure(response.code), response.code, elapsed)
                }
                val reader = response.body?.byteStream()?.bufferedReader() ?: return PolishCallResult.Failure(
                    PolishFailure.InvalidResponse, response.code, elapsed,
                )
                val text = StringBuilder()
                var totalBytes = 0
                var finishReason: String? = null
                var usage: PolishUsage? = null
                var responseModel: String? = null
                var done = false
                while (!done) {
                    val line = reader.readLine() ?: break
                    totalBytes += line.length + 1
                    if (totalBytes > MAX_RESPONSE_BYTES) return PolishCallResult.Failure(
                        PolishFailure.TooLarge, response.code, elapsed,
                    )
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") { done = true; continue }
                    val json = runCatching { JSONObject(data) }.getOrNull()
                        ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, response.code, elapsed)
                    json.optString("model").takeIf { it.isNotBlank() }?.let { responseModel = it }
                    when (config.protocol) {
                        PolishProtocol.Chat -> {
                            val choices = json.optJSONArray("choices")
                            val choice = choices?.optJSONObject(0)
                            val delta = choice?.optJSONObject("delta")
                            delta?.opt("content")?.let { textContent(it) }?.let { text.append(it) }
                            choice?.optString("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
                            json.optJSONObject("usage")?.let { usage = PolishUsage.fromChat(it) }
                        }
                        PolishProtocol.Messages -> {
                            when (json.optString("type")) {
                                "content_block_start" -> {
                                    val block = json.optJSONObject("content_block")
                                    if (block?.optString("type") != "text") return PolishCallResult.Failure(
                                        PolishFailure.InvalidResponse, response.code, elapsed,
                                    )
                                    block.optString("text").takeIf { it.isNotEmpty() }?.let(text::append)
                                }
                                "content_block_delta" -> {
                                    val delta = json.optJSONObject("delta")
                                    if (delta?.optString("type") != "text_delta") return PolishCallResult.Failure(
                                        PolishFailure.InvalidResponse, response.code, elapsed,
                                    )
                                    text.append(delta.optString("text"))
                                }
                                "message_delta" -> {
                                    json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }
                                        ?.let { finishReason = it }
                                    json.optJSONObject("usage")?.let { usage = PolishUsage.fromMessages(it) }
                                }
                                "message_stop" -> done = true
                                "error" -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, response.code, elapsed)
                                else -> Unit
                            }
                        }
                    }
                    val current = text.toString()
                    if (current.codePointCount(0, current.length) > MAX_OUTPUT_CODEPOINTS ||
                        current.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) {
                        return PolishCallResult.Failure(PolishFailure.TooLarge, response.code, elapsed)
                    }
                    onPartial(text.toString())
                }
                if (!done) return PolishCallResult.Failure(PolishFailure.InvalidResponse, response.code, elapsed, usage)
                when (finishReason) {
                    "stop", "end_turn" -> Unit
                    "length", "max_tokens", "model_context_window_exceeded" ->
                        return PolishCallResult.Failure(PolishFailure.Truncated, response.code, elapsed, usage)
                    "content_filter", "refusal" -> return PolishCallResult.Failure(PolishFailure.Refused, response.code, elapsed, usage)
                    else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, response.code, elapsed, usage)
                }
                val problem = PolishPrompts.outputProblem(text.toString())
                if (problem != null) return PolishCallResult.Failure(
                    if (problem == "too_long") PolishFailure.TooLarge else PolishFailure.InvalidResponse,
                    response.code, elapsed, usage,
                )
                PolishCallResult.Success(text.toString(), usage, elapsed, response.code, responseModel)
            }
        } catch (_: SocketTimeoutException) {
            PolishCallResult.Failure(PolishFailure.Timeout, durationMillis = elapsed(started))
        } catch (_: IOException) {
            PolishCallResult.Failure(PolishFailure.Network, durationMillis = elapsed(started))
        } catch (_: Exception) {
            PolishCallResult.Failure(PolishFailure.Unknown, durationMillis = elapsed(started))
        }
    }

    private fun parse(body: String, status: Int, elapsed: Long, acceptTruncated: Boolean): PolishCallResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        return when (config.protocol) {
            PolishProtocol.Chat -> parseChat(json, status, elapsed, acceptTruncated)
            PolishProtocol.Messages -> parseMessages(json, status, elapsed, acceptTruncated)
        }
    }

    private fun parseChat(json: JSONObject, status: Int, elapsed: Long,
                          acceptTruncated: Boolean): PolishCallResult {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() != 1) return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val choice = choices.optJSONObject(0) ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val usage = PolishUsage.fromChat(json.optJSONObject("usage"))
        val finish = choice.optString("finish_reason")
        when (finish) {
            "stop" -> Unit
            "length" -> if (!acceptTruncated) return PolishCallResult.Failure(
                PolishFailure.Truncated, status, elapsed, usage,
            )
            "content_filter" -> return PolishCallResult.Failure(PolishFailure.Refused, status, elapsed, usage)
            else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed, usage)
        }
        val message = choice.optJSONObject("message")
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        if (message.has("refusal") && !message.isNull("refusal")) return PolishCallResult.Failure(
            PolishFailure.Refused, status, elapsed, usage,
        )
        if (message.has("tool_calls")) return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed, usage)
        val text = textContent(message.opt("content"))
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed, usage)
        val problem = PolishPrompts.outputProblem(text)
        return if (problem != null) {
            PolishCallResult.Failure(
                if (problem == "too_long") PolishFailure.TooLarge else PolishFailure.InvalidResponse,
                status, elapsed, usage,
            )
        } else PolishCallResult.Success(text, usage, elapsed, status,
            responseModel = json.optString("model").takeIf { it.isNotBlank() })
    }

    private fun parseMessages(json: JSONObject, status: Int, elapsed: Long,
                              acceptTruncated: Boolean): PolishCallResult {
        val usage = PolishUsage.fromMessages(json.optJSONObject("usage"))
        when (json.optString("stop_reason")) {
            "end_turn" -> Unit
            "max_tokens", "model_context_window_exceeded" ->
                if (!acceptTruncated) return PolishCallResult.Failure(
                    PolishFailure.Truncated, status, elapsed, usage,
                )
            "refusal" -> return PolishCallResult.Failure(PolishFailure.Refused, status, elapsed, usage)
            else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed, usage)
        }
        val content = json.optJSONArray("content")
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val text = buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
                when (block.optString("type")) {
                    "text" -> append(block.optString("text"))
                    else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
                }
            }
        }
        val problem = PolishPrompts.outputProblem(text)
        return if (problem != null) {
            PolishCallResult.Failure(
                if (problem == "too_long") PolishFailure.TooLarge else PolishFailure.InvalidResponse,
                status, elapsed, usage,
            )
        } else PolishCallResult.Success(text, usage, elapsed, status,
            responseModel = json.optString("model").takeIf { it.isNotBlank() })
    }

    private fun textContent(value: Any?): String? = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val block = value.optJSONObject(index) ?: return null
                if (block.optString("type") != "text") return null
                append(block.optString("text"))
            }
        }
        else -> null
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_BYTES = 65_536
        const val MAX_OUTPUT_CODEPOINTS = 4000
        const val MAX_OUTPUT_BYTES = 16_384

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        private fun readBounded(input: InputStream?): ByteArray? {
            if (input == null) return ByteArray(0)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun decodeStrict(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()

        private fun httpFailure(code: Int): PolishFailure = when (code) {
            401, 403 -> PolishFailure.Unauthorized
            408 -> PolishFailure.Timeout
            429 -> PolishFailure.RateLimited
            in 500..599 -> PolishFailure.Server
            else -> PolishFailure.InvalidResponse
        }

        private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null
