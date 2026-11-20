package seeyuer.sayin.polish

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class PolishModelListResult(
    val models: List<String> = emptyList(),
    val error: String? = null,
)

/** Separate, uncharged GET used only for custom OpenAI-compatible gateways. */
internal class PolishModelListClient(
    private val config: PolishConfig,
    private val allowLoopbackHttp: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    fun fetch(): PolishModelListResult {
        if (config.protocol != PolishProtocol.Chat) return PolishModelListResult(error = "需要 OpenAI 兼容协议")
        if (config.validate(allowLoopbackHttp) != null) return PolishModelListResult(error = "配置不完整")
        val base = config.normalizedBaseUrl
        val url = if (base.endsWith("/models")) base else "$base/models"
        val request = Request.Builder().url(url).get().header("Accept", "application/json").apply {
            when (config.auth) {
                PolishAuth.Bearer -> header("Authorization", "Bearer ${config.key}")
                PolishAuth.XApiKey -> header("x-api-key", config.key)
            }
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return PolishModelListResult(error = "HTTP ${response.code}")
                val input = response.body?.byteStream() ?: return PolishModelListResult(error = "空响应")
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 512_000) return PolishModelListResult(error = "列表过大")
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                val json = JSONObject(text)
                val data = json.optJSONArray("data") ?: return PolishModelListResult(error = "缺少 data 列表")
                val models = buildList {
                    for (index in 0 until minOf(data.length(), 500)) {
                        val id = data.optJSONObject(index)?.optString("id")?.trim().orEmpty()
                        if (id.isNotBlank() && id.length <= 120) add(id)
                    }
                }.distinct().sorted()
                PolishModelListResult(models = models, error = if (models.isEmpty()) "列表为空" else null)
            }
        } catch (_: SocketTimeoutException) {
            PolishModelListResult(error = "请求超时")
        } catch (_: IOException) {
            PolishModelListResult(error = "网络不可用")
        } catch (_: Exception) {
            PolishModelListResult(error = "列表格式无效")
        }
    }
}
