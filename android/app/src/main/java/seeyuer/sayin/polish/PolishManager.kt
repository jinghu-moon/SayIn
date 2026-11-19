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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loading = AtomicBoolean(false)

    var state by mutableStateOf<PolishConfigState>(PolishConfigState.Missing)
        private set
    var testState by mutableStateOf<PolishTestState>(PolishTestState.Idle)
        private set
    var stats by mutableStateOf<List<PolishStatsEntry>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set

    val config: PolishConfig? get() = (state as? PolishConfigState.Ready)?.config
    val ready: Boolean get() = config != null

    init {
        scope.launch {
            state = withContext(Dispatchers.IO) { configStore.load() }
            stats = withContext(Dispatchers.IO) { statsStore.load() }
        }
    }

    fun refresh() {
        if (!loading.compareAndSet(false, true)) return
        scope.launch {
            state = withContext(Dispatchers.IO) { configStore.load() }
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
            val result = runCatching { withContext(Dispatchers.IO) { configStore.save(config) } }
            saving = false
            if (result.isSuccess) {
                state = PolishConfigState.Ready(config)
                testState = PolishTestState.Idle
                onSaved(null)
            } else {
                onSaved("保存失败")
            }
        }
    }

    fun clear() {
        scope.launch {
            withContext(Dispatchers.IO) { configStore.clear() }
            state = PolishConfigState.Missing
            testState = PolishTestState.Idle
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
