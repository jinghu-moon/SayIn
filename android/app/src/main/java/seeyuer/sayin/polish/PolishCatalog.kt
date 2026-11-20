package seeyuer.sayin.polish

import android.content.Context
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class PolishCatalogModel(
    val id: String,
    val name: String,
    val providerId: String,
    val reasoning: Boolean,
    val inputUsdPerMillion: Double?,
    val outputUsdPerMillion: Double?,
    val cacheReadUsdPerMillion: Double?,
)

internal data class PolishCatalog(
    val version: String,
    val source: String,
    val fetchedAt: String,
    val modelsByProvider: Map<String, List<PolishCatalogModel>>,
) {
    fun modelsFor(provider: PolishProvider): List<PolishCatalogModel> =
        when (provider) {
            PolishProvider.DeepSeek -> modelsByProvider["deepseek"].orEmpty()
            PolishProvider.Qwen -> (modelsByProvider["alibaba-cn"].orEmpty() + modelsByProvider["alibaba"].orEmpty())
                .distinctBy { it.id }
            PolishProvider.OpenAi -> modelsByProvider["openai"].orEmpty()
            PolishProvider.Anthropic -> modelsByProvider["anthropic"].orEmpty()
            PolishProvider.Custom -> emptyList()
        }

    companion object {
        private val providerIds = setOf("deepseek", "alibaba", "alibaba-cn", "openai", "anthropic")
        private val textOutput = "text"

        fun parse(json: JSONObject, version: String, source: String, fetchedAt: String): PolishCatalog {
            val providers = mutableMapOf<String, List<PolishCatalogModel>>()
            providerIds.forEach { providerId ->
                val provider = json.optJSONObject(providerId) ?: return@forEach
                val models = provider.optJSONObject("models") ?: return@forEach
                val list = buildList {
                    models.allKeys().forEach { key ->
                        val model = models.optJSONObject(key) ?: return@forEach
                        if (model.optString("status") == "deprecated") return@forEach
                        val outputs = model.optJSONObject("modalities")?.optJSONArray("output")
                        if (outputs != null && !outputs.contains(textOutput)) return@forEach
                        val cost = model.optJSONObject("cost")
                        add(PolishCatalogModel(
                            id = model.optString("id").ifBlank { key },
                            name = model.optString("name").ifBlank { key },
                            providerId = providerId,
                            reasoning = model.optBoolean("reasoning"),
                            inputUsdPerMillion = cost?.optDouble("input")?.takeIf { !it.isNaN() },
                            outputUsdPerMillion = cost?.optDouble("output")?.takeIf { !it.isNaN() },
                            cacheReadUsdPerMillion = cost?.optDouble("cache_read")?.takeIf { !it.isNaN() },
                        ))
                    }
                }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                providers[providerId] = list
            }
            return PolishCatalog(version, source, fetchedAt, providers)
        }
    }
}

internal data class PolishCatalogRefresh(
    val checkedAt: Long,
    val updated: Boolean,
    val error: String? = null,
)

internal class PolishCatalogStore(private val context: Context) {
    private val cacheFile = AtomicFile(File(context.filesDir, "polish-catalog.json"))
    private val preferences = context.getSharedPreferences("polish_catalog", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun lastCheckedAt(): Long = preferences.getLong("checked_at", 0L)
    fun etag(): String? = preferences.getString("etag", null)

    fun load(): PolishCatalog? {
        val snapshot = loadSnapshot() ?: return null
        val cache = loadCache()
        return if (cache != null && preferences.getString("cache_snapshot", null) == snapshot.version) cache else snapshot
    }

    private fun loadSnapshot(): PolishCatalog? = runCatching {
        val manifest = JSONObject(context.assets.open("basellm/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val bytes = context.assets.open("basellm/all.json").use { it.readBytes() }
        val sha = sha256(bytes)
        require(sha == manifest.getString("sha256"))
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        PolishCatalog.parse(json, sha, manifest.getString("source"), manifest.getString("fetchedAt"))
    }.getOrNull()

    private fun loadCache(): PolishCatalog? = runCatching {
        val encoded = cacheFile.openRead().use { it.readBytes() }
        require(encoded.size <= 2_500_000)
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        val catalog = json.getJSONObject("catalog")
        val version = json.getString("version")
        PolishCatalog.parse(catalog, version, SOURCE, json.optString("fetchedAt"))
    }.getOrNull()

    suspend fun refresh(): PolishCatalogRefresh = mutex.withLock {
        val started = System.currentTimeMillis()
        val snapshot = loadSnapshot()
        val builder = Request.Builder().url(SOURCE).get().header("Accept", "application/json")
        etag()?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
        return@withLock withContext(Dispatchers.IO) {
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 304) {
                        preferences.edit().putLong("checked_at", started).apply()
                        return@withContext PolishCatalogRefresh(started, false)
                    }
                    if (!response.isSuccessful) {
                        return@withContext PolishCatalogRefresh(0L, false, "HTTP ${response.code}")
                    }
                    val bytes = response.body?.byteStream()?.let { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > 2_000_000) return@withContext PolishCatalogRefresh(
                                0L, false, "目录超过大小限制",
                            )
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: return@withContext PolishCatalogRefresh(0L, false, "空响应")
                    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    val json = JSONObject(text)
                    val version = sha256(bytes)
                    val catalog = PolishCatalog.parse(json, version, SOURCE, java.time.Instant.now().toString())
                    require(catalog.modelsByProvider.values.any { it.isNotEmpty() })
                    val payload = JSONObject()
                        .put("version", version)
                        .put("fetchedAt", java.time.Instant.now().toString())
                        .put("catalog", json)
                    val stream = cacheFile.startWrite()
                    try {
                        stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                        cacheFile.finishWrite(stream)
                    } catch (error: Exception) {
                        cacheFile.failWrite(stream)
                        throw error
                    }
                    preferences.edit().putLong("checked_at", started)
                        .putString("etag", response.header("ETag"))
                        .putString("cache_snapshot", snapshot?.version)
                        .apply()
                    PolishCatalogRefresh(started, true)
                }
            } catch (error: Exception) {
                PolishCatalogRefresh(0L, false, error.message ?: "刷新失败")
            }
        }
    }

    companion object {
        const val SOURCE = "https://basellm.github.io/llm-metadata/api/all.json"
        private val mutex = Mutex()
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal class PolishCatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (PolishCatalogStore(applicationContext).refresh().error == null) Result.success() else Result.retry()
}

internal object PolishCatalogScheduler {
    private const val WORK = "sayin-polish-catalog"
    fun sync(context: Context, hasOfficialConfig: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!hasOfficialConfig) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<PolishCatalogWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

private fun JSONObject.allKeys(): List<String> = buildList { val iterator = keys(); while (iterator.hasNext()) add(iterator.next()) }
private fun JSONArray.contains(value: String): Boolean = (0 until length()).any { optString(it) == value }
