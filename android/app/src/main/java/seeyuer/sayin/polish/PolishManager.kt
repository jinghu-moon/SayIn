package seeyuer.sayin.polish

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val statsStore = PolishStatsStore(appContext)
    private val preferences = appContext.getSharedPreferences("polish", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loading = AtomicBoolean(false)

    var state by mutableStateOf<PolishConfigState>(PolishConfigState.Missing)
        private set
    var configurations by mutableStateOf<List<PolishConfig>>(emptyList())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var testState by mutableStateOf<PolishTestState>(PolishTestState.Idle)
        private set
    var stats by mutableStateOf<List<PolishStatsEntry>>(emptyList())
        private set
    var saving by mutableStateOf(false)
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
                    state = PolishConfigState.Ready(loaded.set.active)
                }
            }
            stats = withContext(Dispatchers.IO) { statsStore.load() }
            autoPolish = preferences.getBoolean("auto", false) &&
                config?.fingerprint == autoFingerprint
        }
    }

    fun selectConfig(id: String) {
        val selected = configurations.firstOrNull { it.id == id } ?: return
        if (activeId == id) return
        activeId = id
        persistActive(id)
        state = PolishConfigState.Ready(selected)
        testState = PolishTestState.Idle
        if (autoPolish) updateAutoPolish(false)
        onConfigurationChanged?.invoke()
    }

    private fun persistActive(id: String) {
        val set = PolishConfigSet(configurations, id)
        scope.launch { withContext(Dispatchers.IO) { configStore.save(set) } }
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

    fun refresh() {
        if (!loading.compareAndSet(false, true)) return
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
                    state = PolishConfigState.Ready(loaded.set.active)
                }
            }
            stats = withContext(Dispatchers.IO) { statsStore.load() }
            loading.set(false)
        }
    }

    fun save(config: PolishConfig, onSaved: (String?) -> Unit = {}) {
        if (saving) return
        val error = config.validate()
        if (error != null) { onSaved(error); return }
        saving = true
        scope.launch {
            val existingIndex = configurations.indexOfFirst { it.id == config.id }
            val stored = if (existingIndex >= 0) {
                config.copy(revision = config.revision + 1, name = config.name.ifBlank { config.model })
            } else {
                config.copy(revision = 1L, name = config.name.ifBlank { config.model })
            }
            val next = if (existingIndex >= 0) {
                configurations.toMutableList().also { it[existingIndex] = stored }
            } else {
                (configurations + stored).take(32)
            }
            val previous = this@PolishManager.config?.fingerprint
            val nextActive = if (activeId == null || existingIndex < 0) stored.id else activeId!!
            val set = PolishConfigSet(next, nextActive)
            val result = runCatching { withContext(Dispatchers.IO) { configStore.save(set) } }
            saving = false
            if (result.isSuccess) {
                val activeBefore = activeId
                val editedActive = existingIndex >= 0 && activeBefore == config.id
                val becameActive = existingIndex < 0 || editedActive
                configurations = next
                activeId = nextActive
                state = PolishConfigState.Ready(next.first { it.id == nextActive })
                testState = PolishTestState.Idle
                if (autoPolish && (becameActive || previous != stored.fingerprint)) updateAutoPolish(false)
                if (becameActive) onConfigurationChanged?.invoke()
                onSaved(null)
            } else {
                onSaved("保存失败")
            }
        }
    }

    fun deleteConfig(id: String) {
        val remaining = configurations.filterNot { it.id == id }
        val nextActive = activeId.takeIf { active -> remaining.any { it.id == active } } ?: remaining.firstOrNull()?.id
        scope.launch {
            if (remaining.isEmpty()) {
                withContext(Dispatchers.IO) { configStore.clear() }
                configurations = emptyList(); activeId = null
                state = PolishConfigState.Missing
            } else {
                val set = PolishConfigSet(remaining, nextActive!!)
                withContext(Dispatchers.IO) { configStore.save(set) }
                configurations = remaining; activeId = nextActive
                state = PolishConfigState.Ready(set.active)
            }
            testState = PolishTestState.Idle
            if (autoPolish) updateAutoPolish(false)
            onConfigurationChanged?.invoke()
        }
    }

    fun clear() {
        scope.launch {
            withContext(Dispatchers.IO) { configStore.clear() }
            configurations = emptyList(); activeId = null
            state = PolishConfigState.Missing
            testState = PolishTestState.Idle
            if (autoPolish) updateAutoPolish(false)
            onConfigurationChanged?.invoke()
        }
    }

    fun test(config: PolishConfig) {
        if (testState == PolishTestState.Testing) return
        val error = config.validate()
        if (error != null) { testState = PolishTestState.Failure(PolishFailure.MissingConfig); return }
        testState = PolishTestState.Testing
        scope.launch {
            val result = withContext(Dispatchers.IO) { PolishClient(config).test() }
            testState = when (result) {
                is PolishCallResult.Success -> PolishTestState.Success(result.durationMillis)
                is PolishCallResult.Failure -> PolishTestState.Failure(result.kind)
            }
            record(config, result, "test")
        }
    }

    fun record(config: PolishConfig, result: PolishCallResult, outcome: String) {
        val entry = when (result) {
            is PolishCallResult.Success -> PolishStatsEntry(
                System.currentTimeMillis(), config.provider.id, config.model,
                result.inputTokens, result.outputTokens, result.durationMillis, outcome,
                PolishCostSource.Unknown, result.httpStatus,
            )
            is PolishCallResult.Failure -> PolishStatsEntry(
                System.currentTimeMillis(), config.provider.id, config.model,
                null, null, result.durationMillis, result.kind.name.lowercase(),
                PolishCostSource.Unknown, result.httpStatus,
            )
        }
        stats = (listOf(entry) + stats).take(PolishStatsStore.MAX_ENTRIES)
        scope.launch { withContext(Dispatchers.IO) { statsStore.add(entry) } }
    }

    fun clearStats() {
        stats = emptyList()
        scope.launch { withContext(Dispatchers.IO) { statsStore.clear() } }
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
