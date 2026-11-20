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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class PolishPriceRates(
    val input: Double?,
    val output: Double?,
    val cacheRead: Double?,
    val cacheWrite: Double?,
)

internal data class PolishPriceTier(val minContext: Long, val rates: PolishPriceRates)

internal data class PolishPriceWindow(
    val weekdays: Set<Int>,
    val hours: List<Pair<Int, Int>>,
    val rates: PolishPriceRates,
)

internal data class PolishPriceSchedule(
    val zoneId: String,
    val fallback: String,
    val windows: List<PolishPriceWindow>,
)

internal data class PolishCatalogModel(
    val id: String,
    val name: String,
    val providerId: String,
    val reasoning: Boolean,
    val inputUsdPerMillion: Double?,
    val outputUsdPerMillion: Double?,
    val cacheReadUsdPerMillion: Double?,
    val cacheWriteUsdPerMillion: Double? = null,
    val tiers: List<PolishPriceTier> = emptyList(),
    val schedule: PolishPriceSchedule? = null,
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
                            cacheWriteUsdPerMillion = cost?.optDouble("cache_write")?.takeIf { !it.isNaN() },
                            tiers = cost?.optJSONArray("tiers")?.let(::parseTiers).orEmpty(),
                            schedule = cost?.optJSONObject("schedule")?.let(::parseSchedule),
                        ))
                    }
                }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                providers[providerId] = list
            }
            return PolishCatalog(version, source, fetchedAt, providers)
        }

        private fun parseTiers(array: org.json.JSONArray): List<PolishPriceTier> = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val size = item.optJSONObject("tier")?.optLong("size", 0L) ?: 0L
                if (size <= 0L) continue
                add(PolishPriceTier(size, parseRates(item)))
            }
        }

        private fun parseSchedule(json: JSONObject): PolishPriceSchedule? {
            val zone = json.optString("timezone").ifBlank { "UTC" }
            val windows = buildList {
                val array = json.optJSONArray("windows") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val weekdays = parseWeekdays(item.opt("weekdays"))
                    val hours = parseHours(item.opt("hours"))
                    if (hours.isEmpty()) continue
                    add(PolishPriceWindow(weekdays, hours, parseRates(item)))
                }
            }
            if (windows.isEmpty()) return null
            return PolishPriceSchedule(zone, json.optString("fallback"), windows)
        }

        private fun parseRates(json: JSONObject): PolishPriceRates = PolishPriceRates(
            input = json.optDouble("input")?.takeIf { !it.isNaN() },
            output = json.optDouble("output")?.takeIf { !it.isNaN() },
            cacheRead = json.optDouble("cache_read")?.takeIf { !it.isNaN() },
            cacheWrite = json.optDouble("cache_write")?.takeIf { !it.isNaN() },
        )

        private fun parseWeekdays(value: Any?): Set<Int> = when (value) {
            is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optInt(it, 0).takeIf { day -> day in 1..7 } }.toSet()
            is String -> value.split(Regex("\\s+")).mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }.toSet()
            else -> emptySet()
        }

        private fun parseHours(value: Any?): List<Pair<Int, Int>> {
            val text = when (value) {
                is org.json.JSONArray -> (0 until value.length()).joinToString(" ") { value.optString(it) }
                is String -> value
                else -> return emptyList()
            }
            return text.split(Regex("\\s+")).mapNotNull { part ->
                val pieces = part.split("-")
                if (pieces.size != 2) return@mapNotNull null
                val start = pieces[0].split(":").let { it.getOrNull(0)?.toIntOrNull()?.times(60)?.plus(it.getOrNull(1)?.toIntOrNull() ?: 0) }
                val end = pieces[1].split(":").let { it.getOrNull(0)?.toIntOrNull()?.times(60)?.plus(it.getOrNull(1)?.toIntOrNull() ?: 0) }
                if (start == null || end == null) null else start to end
            }
        }
    }
}

internal data class PolishCatalogRefresh(
    val checkedAt: Long,
    val updated: Boolean,
    val error: String? = null,
)

internal class PolishCatalogStore(
    private val context: Context,
    private val cacheFile: AtomicFile = AtomicFile(File(context.filesDir, "polish-catalog.json")),
    private val preferences: android.content.SharedPreferences =
        context.getSharedPreferences("polish_catalog", Context.MODE_PRIVATE),
    private val fetch: (String?) -> PolishCatalogFetch = PolishCatalogFetcher(SOURCE)::fetch,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<Pair<PolishCatalog?, Long>?>(null)
    val state = current.asStateFlow()
    @Volatile private var lastRefresh: PolishCatalogRefresh? = null

    private data class Cache(val catalog: PolishCatalog, val etag: String?, val checkedAt: Long)

    fun load(): PolishCatalog? {
        val snapshot = loadSnapshot() ?: return null
        return loadCache(snapshot.version)?.catalog ?: snapshot
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (current.value == null) {
                val snapshot = loadSnapshot()
                val cache = snapshot?.let { loadCache(it.version) }
                current.value = (cache?.catalog ?: snapshot) to checkedAt(cache)
            }
        }
    }

    private fun checkedAt(cache: Cache?): Long = if (cache == null) 0L else
        maxOf(cache.checkedAt, preferences.getLong("checked_${cache.catalog.version}", 0L))

    private fun loadSnapshot(): PolishCatalog? = runCatching {
        val manifest = JSONObject(context.assets.open("basellm/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val bytes = context.assets.open("basellm/all.json").use { it.readBytes() }
        val sha = sha256(bytes)
        require(sha == manifest.getString("sha256"))
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        PolishCatalog.parse(json, sha, manifest.getString("source"), manifest.getString("fetchedAt"))
    }.getOrNull()

    private fun loadCache(snapshotVersion: String): Cache? = runCatching {
        val encoded = cacheFile.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 4_000_000)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        require(json.getString("snapshotVersion") == snapshotVersion)
        val raw = json.getString("catalog")
        val version = json.getString("version")
        require(sha256(raw.toByteArray(Charsets.UTF_8)) == version)
        val catalog = PolishCatalog.parse(JSONObject(raw), version, SOURCE, json.getString("fetchedAt"))
        require(catalog.modelsByProvider.values.any { it.isNotEmpty() })
        Cache(catalog, json.optString("etag").takeIf { it.isNotBlank() }, json.getLong("checkedAt"))
    }.getOrNull()

    suspend fun refresh(): PolishCatalogRefresh {
        val previous = lastRefresh
        return withContext(Dispatchers.IO) { mutex.withLock {
            lastRefresh?.takeIf { it !== previous } ?: refreshLocked().also { lastRefresh = it }
        } }
    }

    private fun refreshLocked(): PolishCatalogRefresh {
        val started = System.currentTimeMillis()
        val snapshot = loadSnapshot() ?: return PolishCatalogRefresh(0L, false, "snapshot")
        val cache = loadCache(snapshot.version)
        if (current.value == null) current.value = (cache?.catalog ?: snapshot) to checkedAt(cache)
        return when (val fetch = fetch(cache?.etag)) {
            PolishCatalogFetch.NotModified -> {
                if (cache?.etag == null) return PolishCatalogRefresh(0L, false, "invalid_304")
                preferences.edit().clear().putLong("checked_${cache.catalog.version}", started).apply()
                current.value = cache.catalog to started
                PolishCatalogRefresh(started, false)
            }
            is PolishCatalogFetch.Failure -> PolishCatalogRefresh(0L, false, fetch.message)
            is PolishCatalogFetch.Updated -> {
                val unchanged = fetch.catalog.version == cache?.catalog?.version
                val catalog = if (unchanged) checkNotNull(cache).catalog else fetch.catalog
                val payload = JSONObject()
                    .put("version", catalog.version)
                    .put("snapshotVersion", snapshot.version)
                    .put("fetchedAt", catalog.fetchedAt)
                    .put("checkedAt", started)
                    .put("etag", fetch.etag)
                    .put("catalog", fetch.rawJson)
                val encoded = payload.toString().toByteArray(Charsets.UTF_8)
                if (encoded.size > 4_000_000) return PolishCatalogRefresh(0L, false, "cache_size")
                try {
                    val stream = cacheFile.startWrite()
                    try {
                        stream.write(encoded)
                        cacheFile.finishWrite(stream)
                    } catch (error: Exception) {
                        cacheFile.failWrite(stream)
                        throw error
                    }
                } catch (_: Exception) {
                    return PolishCatalogRefresh(0L, false, "cache_write")
                }
                current.value = catalog to started
                PolishCatalogRefresh(started, !unchanged)
            }
        }
    }

    companion object {
        const val SOURCE = "https://basellm.github.io/llm-metadata/api/all.json"
        private var shared: PolishCatalogStore? = null
        @Synchronized fun shared(context: Context): PolishCatalogStore =
            shared ?: PolishCatalogStore(context.applicationContext).also { shared = it }
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal class PolishCatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (PolishCatalogStore.shared(applicationContext).refresh().error == null) Result.success() else Result.retry()
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
