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
        val inputTokens: Long?,
        val outputTokens: Long?,
        val durationMillis: Long,
        val httpStatus: Int,
    ) : PolishCallResult
    data class Failure(
        val kind: PolishFailure,
        val httpStatus: Int? = null,
        val durationMillis: Long = 0,
    ) : PolishCallResult
}

internal class PolishClient(
    private val config: PolishConfig,
    private val client: OkHttpClient = defaultClient(),
    private val allowLoopbackHttp: Boolean = false,
) {
    fun call(mode: PolishMode, targetLanguage: String? = null, appName: String? = null,
             scene: PolishScene? = null, customRewrite: String? = null, text: String,
             onCall: (Call) -> Unit = {}): PolishCallResult {
        val system = PolishPrompts.system(mode, targetLanguage, appName, customRewrite, scene)
        return callRaw(system, PolishPrompts.user(text), maxTokens = 4096, onCall = onCall)
    }

    fun test(): PolishCallResult =
        callRaw("你是连接测试器。只输出 OK，不解释。", "<input_text>OK</input_text>", maxTokens = 8)

    fun callRaw(system: String, user: String, maxTokens: Int, onCall: (Call) -> Unit = {}): PolishCallResult {
        val error = config.validate(allowLoopbackHttp)
        if (error != null) return PolishCallResult.Failure(PolishFailure.MissingConfig)
        val started = System.nanoTime()
        val request = runCatching { request(system, user, maxTokens) }.getOrElse {
            return PolishCallResult.Failure(PolishFailure.InvalidResponse)
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
                parse(body, response.code, elapsed)
            }
        } catch (_: SocketTimeoutException) {
            PolishCallResult.Failure(PolishFailure.Timeout, durationMillis = elapsed(started))
        } catch (_: IOException) {
            PolishCallResult.Failure(PolishFailure.Network, durationMillis = elapsed(started))
        } catch (_: Exception) {
            PolishCallResult.Failure(PolishFailure.Unknown, durationMillis = elapsed(started))
        }
    }

    private fun request(system: String, user: String, maxTokens: Int): Request {
        val url = endpoint()
        val body = when (config.protocol) {
            PolishProtocol.Chat -> chatBody(system, user, maxTokens)
            PolishProtocol.Messages -> messagesBody(system, user, maxTokens)
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

    private fun chatBody(system: String, user: String, maxTokens: Int): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val field = if (config.maxTokensField == PolishMaxTokensField.MaxCompletionTokens) "max_completion_tokens" else "max_tokens"
        body.put(field, maxTokens)
        if (config.provider == PolishProvider.OpenAi) body.put("reasoning_effort", "none")
        return body
    }

    private fun messagesBody(system: String, user: String, maxTokens: Int): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put("max_tokens", maxTokens)
        if (config.thinking == PolishThinking.DisabledLow) {
            body.put("thinking", JSONObject().put("type", "disabled"))
            body.put("output_config", JSONObject().put("effort", "low"))
        }
        return body
    }

    private fun parse(body: String, status: Int, elapsed: Long): PolishCallResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        return when (config.protocol) {
            PolishProtocol.Chat -> parseChat(json, status, elapsed)
            PolishProtocol.Messages -> parseMessages(json, status, elapsed)
        }
    }

    private fun parseChat(json: JSONObject, status: Int, elapsed: Long): PolishCallResult {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() != 1) return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val choice = choices.optJSONObject(0) ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        when (choice.optString("finish_reason")) {
            "stop" -> Unit
            "length" -> return PolishCallResult.Failure(PolishFailure.Truncated, status, elapsed)
            "content_filter" -> return PolishCallResult.Failure(PolishFailure.Refused, status, elapsed)
            else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        }
        val message = choice.optJSONObject("message")
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        if (message.has("refusal") && !message.isNull("refusal")) return PolishCallResult.Failure(PolishFailure.Refused, status, elapsed)
        if (message.has("tool_calls")) return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val text = textContent(message.opt("content"))
            ?: return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
        val problem = PolishPrompts.outputProblem(text)
        return if (problem != null) {
            PolishCallResult.Failure(if (problem == "too_long") PolishFailure.TooLarge else PolishFailure.InvalidResponse, status, elapsed)
        } else PolishCallResult.Success(text, json.optJSONObject("usage")?.optLongOrNull("prompt_tokens"),
            json.optJSONObject("usage")?.optLongOrNull("completion_tokens"), elapsed, status)
    }

    private fun parseMessages(json: JSONObject, status: Int, elapsed: Long): PolishCallResult {
        when (json.optString("stop_reason")) {
            "end_turn" -> Unit
            "max_tokens", "model_context_window_exceeded" -> return PolishCallResult.Failure(PolishFailure.Truncated, status, elapsed)
            "refusal" -> return PolishCallResult.Failure(PolishFailure.Refused, status, elapsed)
            else -> return PolishCallResult.Failure(PolishFailure.InvalidResponse, status, elapsed)
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
            PolishCallResult.Failure(if (problem == "too_long") PolishFailure.TooLarge else PolishFailure.InvalidResponse, status, elapsed)
        } else PolishCallResult.Success(text, json.optJSONObject("usage")?.optLongOrNull("input_tokens"),
            json.optJSONObject("usage")?.optLongOrNull("output_tokens"), elapsed, status)
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
