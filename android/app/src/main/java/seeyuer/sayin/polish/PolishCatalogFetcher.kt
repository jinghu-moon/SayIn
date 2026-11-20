package seeyuer.sayin.polish

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal sealed interface PolishCatalogFetch {
    data class Updated(val catalog: PolishCatalog, val rawJson: String, val etag: String?) : PolishCatalogFetch
    data object NotModified : PolishCatalogFetch
    data class Failure(val message: String) : PolishCatalogFetch
}

/** Pure network/validation boundary; storage and scheduling stay in PolishCatalogStore. */
internal class PolishCatalogFetcher(
    private val source: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Int = 2_000_000,
) {
    fun fetch(etag: String?): PolishCatalogFetch {
        val request = Request.Builder().url(source).get().header("Accept", "application/json").apply {
            etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return PolishCatalogFetch.NotModified
                if (!response.isSuccessful) return PolishCatalogFetch.Failure("HTTP ${response.code}")
                val input = response.body?.byteStream() ?: return PolishCatalogFetch.Failure("空响应")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > maxBytes) return PolishCatalogFetch.Failure("目录超过大小限制")
                    output.write(buffer, 0, read)
                }
                val bytes = output.toByteArray()
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                val json = JSONObject(text)
                val version = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val catalog = PolishCatalog.parse(json, version, source, java.time.Instant.now().toString())
                if (catalog.modelsByProvider.values.none { it.isNotEmpty() }) {
                    return PolishCatalogFetch.Failure("目录结构无效")
                }
                PolishCatalogFetch.Updated(catalog, text, response.header("ETag"))
            }
        } catch (error: Exception) {
            PolishCatalogFetch.Failure(error.message ?: "刷新失败")
        }
    }
}
