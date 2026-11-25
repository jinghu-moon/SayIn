package seeyuer.sayin.asr

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/** Session boundary keeps microphone/native work out of presentation and permits deterministic tests. */
internal interface RecognitionEngine : Closeable {
    val state: StateFlow<RecognitionState>
    val model: StateFlow<LocalModelPhase>
    fun prepareLocal()
    suspend fun releaseLocal() {}
    fun start(
        cloud: CloudConfiguration? = null,
        autoFinish: Boolean = true,
        localSilenceMillis: Int = LocalSilence.DefaultMillis,
    )
    fun finish()
    fun cancel()
    fun clearResult()
}
