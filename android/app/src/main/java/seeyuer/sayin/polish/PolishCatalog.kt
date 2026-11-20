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
    private val fetcher = PolishCatalogFetcher(SOURCE)

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
        when (val fetch = withContext(Dispatchers.IO) { fetcher.fetch(etag()) }) {
            PolishCatalogFetch.NotModified -> {
                preferences.edit().putLong("checked_at", started).apply()
                PolishCatalogRefresh(started, false)
            }
            is PolishCatalogFetch.Failure -> PolishCatalogRefresh(0L, false, fetch.message)
            is PolishCatalogFetch.Updated -> withContext(Dispatchers.IO) {
                val payload = JSONObject()
                    .put("version", fetch.catalog.version)
                    .put("fetchedAt", fetch.catalog.fetchedAt)
                    .put("catalog", JSONObject(fetch.rawJson))
                val stream = cacheFile.startWrite()
                try {
                    stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                    cacheFile.finishWrite(stream)
                } catch (error: Exception) {
                    cacheFile.failWrite(stream)
                    return@withContext PolishCatalogRefresh(0L, false, error.message ?: "写入失败")
                }
                preferences.edit().putLong("checked_at", started)
                    .putString("etag", fetch.etag)
                    .putString("cache_snapshot", snapshot?.version)
                    .apply()
                PolishCatalogRefresh(started, true)
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
