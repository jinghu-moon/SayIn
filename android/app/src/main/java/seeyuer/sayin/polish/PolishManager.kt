package seeyuer.sayin.polish

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal sealed interface PolishTestState {
    data object Idle : PolishTestState
    data object Testing : PolishTestState
    data class Success(val durationMillis: Long) : PolishTestState
    data class Failure(val kind: PolishFailure) : PolishTestState
}

internal class PolishManager(context: Context) {
    private val appContext = context.applicationContext
    private val configStore = PolishConfigStore(appContext)
    private val usageStore = PolishUsageStore(appContext)
    private val catalogStore = PolishCatalogStore.shared(appContext)
    val catalogState = catalogStore.state
    private val preferences = appContext.getSharedPreferences("polish", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf<PolishConfigState>(PolishConfigState.Missing)
        private set
    var configurations by mutableStateOf<List<PolishConfig>>(emptyList())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    private var testState by mutableStateOf<PolishTestState>(PolishTestState.Idle)
    private var testedConfig by mutableStateOf<PolishConfig?>(null)
    private var testGeneration = 0
    private val usageChanges = MutableStateFlow(0L)
    val usageVersion = usageChanges.asStateFlow()
    var saving by mutableStateOf(true)
        private set
    var configWriteFailed by mutableStateOf(false)
        private set
    var catalogChecking by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var customModels by mutableStateOf<List<String>>(emptyList())
        private set
    var customModelsLoading by mutableStateOf(false)
        private set
    var customModelsError by mutableStateOf<String?>(null)
        private set
    var onConfigurationChanged: (() -> Unit)? = null
    var targetLanguage by mutableStateOf(preferences.getString("target_language", "en") ?: "en")
        private set
    var appRulesEnabled by mutableStateOf(preferences.getBoolean("app_rules", true))
        private set
    var customRewrite by mutableStateOf(preferences.getString("custom_rewrite", "").orEmpty())
        private set
    var hotwords by mutableStateOf(preferences.getString("hotwords", "").orEmpty())
        private set
    var streamingPreview by mutableStateOf(preferences.getBoolean("streaming_preview", false))
        private set
    var autoPolish by mutableStateOf(false)
        private set
    private var autoFingerprint: String? = preferences.getString("auto_fingerprint", null)
    private var customModelsGeneration = 0
    private var modelListConfig by mutableStateOf<PolishConfig?>(null)

    private fun invalidateCustomModels() {
        customModelsGeneration++
        customModelsLoading = false
        customModels = emptyList()
        customModelsError = null
        modelListConfig = null
    }

    val config: PolishConfig? get() = configurations.firstOrNull { it.id == activeId }
    val ready: Boolean get() = config != null
    val autoPolishBound: Boolean get() = autoPolish && config != null && autoFingerprint == config?.fingerprint

    init {
        scope.launch {
            when (val loaded = withContext(Dispatchers.IO) { configStore.load() }) {
                PolishConfigLoad.Missing -> {
                    configurations = emptyList(); activeId = null; state = PolishConfigState.Missing
                }
                PolishConfigLoad.Corrupt -> {
                    configurations = emptyList(); activeId = null; state = PolishConfigState.Corrupt
                }
                is PolishConfigLoad.Ready -> {
                    configurations = loaded.set.configurations
                    activeId = loaded.set.activeId
                    state = loaded.set.active?.let { PolishConfigState.Ready(it) } ?: PolishConfigState.Missing
                }
            }
            saving = false
            runCatching { withContext(Dispatchers.IO) { usageStore.recover() } }
            usageChanges.update { it + 1 }
            catalogStore.initialize()
            autoPolish = preferences.getBoolean("auto", false) &&
                config?.fingerprint == autoFingerprint
            syncCatalogWork()
        }
    }

    private fun syncCatalogWork() {
        PolishCatalogScheduler.sync(appContext, configurations.any { it.officialCatalogProvider != null })
    }

    private fun modelListBinding(config: PolishConfig) = config.copy(name = "", revision = 0L, model = "")
    fun modelsFor(config: PolishConfig): List<String> =
        if (modelListConfig == modelListBinding(config)) customModels else emptyList()
    fun customModelsErrorFor(config: PolishConfig): Boolean =
        modelListConfig == modelListBinding(config) && customModelsError != null

    fun refreshCustomModels(config: PolishConfig) {
        if (config.provider != PolishProvider.Custom) return
        invalidateCustomModels()
        val generation = customModelsGeneration
        modelListConfig = modelListBinding(config)
        customModelsLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { PolishModelListClient(config).fetch() }
            if (generation != customModelsGeneration) return@launch
            customModelsLoading = false
            customModels = result.models
            customModelsError = result.error
        }
    }

    fun refreshCatalog() {
        if (catalogChecking) return
        catalogChecking = true
        catalogError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { catalogStore.refresh() }
            catalogChecking = false
            catalogError = result.error
        }
    }

    fun selectConfig(id: String) {
        if (activeId == id || configurations.none { it.id == id }) return
        persistConfigurations(configurations, id)
    }

    fun updateTargetLanguage(value: String) {
        if (PolishPrompts.validateTargetLanguage(value) != null) return
        targetLanguage = value
        preferences.edit().putString("target_language", value).apply()
    }

    fun updateAppRules(value: Boolean) {
        appRulesEnabled = value
        preferences.edit().putBoolean("app_rules", value).apply()
    }

    fun updateCustomRewrite(value: String) {
        customRewrite = value.take(2000)
        preferences.edit().putString("custom_rewrite", customRewrite).apply()
    }

    fun updateHotwords(value: String) {
        hotwords = value.take(1000)
        preferences.edit().putString("hotwords", hotwords).apply()
    }

    fun updateStreamingPreview(value: Boolean) {
        streamingPreview = value
        preferences.edit().putBoolean("streaming_preview", value).apply()
    }

    fun updateAutoPolish(value: Boolean) {
        if (value && config == null) return
        autoPolish = value
        autoFingerprint = if (value) config?.fingerprint else null
        preferences.edit().putBoolean("auto", value)
            .putString("auto_fingerprint", autoFingerprint).apply()
    }

    fun save(config: PolishConfig, onSaved: (String?) -> Unit = {}) {
        if (saving) return
        val error = config.validate()
        if (error != null) { onSaved(error); return }
        val old = configurations.firstOrNull { it.id == config.id }
        if (old == null && configurations.size >= 32) { onSaved("limit"); return }
        val stored = config.copy(revision = (old?.revision ?: 0L) + 1, name = config.name.ifBlank { config.model })
        val next = if (old == null) configurations + stored else configurations.map { if (it.id == stored.id) stored else it }
        persistConfigurations(next, activeId ?: stored.id, onSaved)
    }

    fun deleteConfig(id: String, onSaved: (String?) -> Unit = {}) {
        if (configurations.none { it.id == id }) return
        persistConfigurations(configurations.filterNot { it.id == id }, activeId.takeUnless { it == id }, onSaved)
    }

    private fun persistConfigurations(next: List<PolishConfig>, nextActive: String?, onSaved: (String?) -> Unit = {}) {
        if (saving) return
        saving = true
        configWriteFailed = false
        val previous = config
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (next.isEmpty()) configStore.clear()
                    else configStore.save(PolishConfigSet(next, nextActive))
                }
            }
            if (result.isSuccess) {
                configurations = next
                activeId = nextActive
                state = config?.let { PolishConfigState.Ready(it) } ?: PolishConfigState.Missing
                if (previous != config) {
                    updateAutoPolish(false)
                    onConfigurationChanged?.invoke()
                }
                syncCatalogWork()
            }
            configWriteFailed = result.isFailure
            saving = false
            onSaved(if (result.isSuccess) null else "write")
        }
    }

    fun testResultFor(config: PolishConfig): PolishTestState =
        if (testedConfig == config) testState else PolishTestState.Idle

    fun resetEditorResults() {
        invalidateCustomModels()
        testedConfig = null
        testState = PolishTestState.Idle
        testGeneration++
    }

    fun test(config: PolishConfig) {
        if (testResultFor(config) == PolishTestState.Testing) return
        val generation = ++testGeneration
        testedConfig = config
        testState = if (config.validate() == null) PolishTestState.Testing
            else PolishTestState.Failure(PolishFailure.MissingConfig)
        if (testState != PolishTestState.Testing) return
        scope.launch {
            val call = runCatching { beginCall(config, PolishCallPurpose.Test) }.getOrNull()
            val result = if (call == null) PolishCallResult.Failure(PolishFailure.Unknown, requestSent = false)
                else withContext(Dispatchers.IO) { PolishClient(config).test() }
            if (testGeneration == generation) {
                testState = when (result) {
                    is PolishCallResult.Success -> PolishTestState.Success(result.durationMillis)
                    is PolishCallResult.Failure -> PolishTestState.Failure(result.kind)
                }
            }
            if (call != null) runCatching { finishCall(call, result) }
        }
    }

    internal data class CallAccounting(
        val row: PolishUsageEntity,
        val catalog: PolishCatalog?,
        val catalogProvider: String?,
        val purpose: PolishCallPurpose,
    )

    suspend fun beginCall(config: PolishConfig, purpose: PolishCallPurpose): CallAccounting {
        catalogStore.initialize()
        val catalog = catalogState.value?.first
        val row = withContext(Dispatchers.IO) { usageStore.begin(config, purpose) }
        return CallAccounting(row, catalog, config.officialCatalogProvider, purpose)
    }

    suspend fun finishCall(call: CallAccounting, result: PolishCallResult, cancelled: Boolean = false) {
        val outcome = when (result) {
            is PolishCallResult.Success -> "success"
            is PolishCallResult.Failure -> if (!result.requestSent) "unsent" else result.kind.name.lowercase()
        }
        withContext(Dispatchers.IO) {
            val usage = when (result) {
                is PolishCallResult.Success -> result.usage
                is PolishCallResult.Failure -> result.usage
            }
            val responseModel = when (result) {
                is PolishCallResult.Success -> result.responseModel
                is PolishCallResult.Failure -> result.responseModel
            }
            val price = PolishPricing.estimate(
                call.catalog, usage, call.catalogProvider,
                responseModel ?: call.row.requestModel, call.purpose, atMillis = call.row.startedAt,
            )
            usageStore.finish(call.row.callId, result, if (cancelled && outcome != "unsent") "cancelled" else outcome, price, responseModel)
        }
        usageChanges.update { it + 1 }
    }

    suspend fun usageReport(days: Int, purpose: String? = null, configId: String? = null,
                            model: String? = null, page: Int = 0, includeDetails: Boolean = true): PolishUsageReport {
        val zone = java.time.ZoneId.systemDefault()
        val since = java.time.LocalDate.now(zone).minusDays((days - 1).coerceAtLeast(0).toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return usageStore.report(since, purpose, configId, model, page, includeDetails)
    }

    fun clearStats() {
        scope.launch {
            withContext(Dispatchers.IO) { usageStore.clear() }
            usageChanges.update { it + 1 }
        }
    }
}

internal fun PolishFailure.userMessage(): String = when (this) {
    PolishFailure.MissingConfig -> "未配置服务"
    PolishFailure.Unauthorized -> "凭据被拒绝"
    PolishFailure.RateLimited -> "请求过于频繁"
    PolishFailure.Timeout -> "请求超时"
    PolishFailure.Network -> "网络不可用"
    PolishFailure.Server -> "服务端错误"
    PolishFailure.InvalidResponse -> "响应格式无效"
    PolishFailure.Truncated -> "响应被截断"
    PolishFailure.Refused -> "模型拒绝了请求"
    PolishFailure.TooLarge -> "响应过长"
    PolishFailure.Unknown -> "未知错误"
}
