package seeyuer.sayin.asr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CloudSettingsStatus { Loading, Ready, Saved, Testing, Tested, Saving, Clearing }
internal data class CloudSettingsState(
    val config: CloudConfiguration? = null,
    val status: CloudSettingsStatus = CloudSettingsStatus.Loading,
    val issue: CloudIssue? = null,
) {
    val busy get() = status in setOf(CloudSettingsStatus.Loading, CloudSettingsStatus.Saving, CloudSettingsStatus.Testing, CloudSettingsStatus.Clearing)
}

internal class CloudSettings(application: Application) : AndroidViewModel(application) {
    private val store = CloudConfigurationStore(application)
    private val scope get() = viewModelScope
    private val mutableState = MutableStateFlow(CloudSettingsState())
    val state = mutableState.asStateFlow()
    init {
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { store.load() }
                mutableState.value = CloudSettingsState(config, CloudSettingsStatus.Ready)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = CloudSettingsState(status = CloudSettingsStatus.Ready, issue = CloudIssue.Storage) }
        }
    }
    fun save(auth: DoubaoAuth, appKey: String, appId: String, token: String, hotwords: String) {
        if (mutableState.value.busy) return
        val config = try {
            CloudConfiguration(auth, if (auth == DoubaoAuth.AppKey) appKey.trim() else "",
                if (auth == DoubaoAuth.AppIdToken) appId.trim() else "",
                if (auth == DoubaoAuth.AppIdToken) token.trim() else "", parseHotwords(hotwords))
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(issue = CloudIssue.Configuration)
            return
        }
        mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Saving, issue = null)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(config) }
                mutableState.value = CloudSettingsState(config, CloudSettingsStatus.Saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Ready, issue = CloudIssue.Storage) }
        }
    }
    fun clear() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Clearing, issue = null)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                mutableState.value = CloudSettingsState(status = CloudSettingsStatus.Ready)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Ready, issue = CloudIssue.Storage) }
        }
    }
    /** No permission or microphone: tests a one-second silent stream, never sends to the desktop. */
    fun test() {
        val snapshot = mutableState.value
        val config = snapshot.config ?: return
        if (snapshot.busy || snapshot.issue == CloudIssue.Storage) return
        mutableState.value = snapshot.copy(status = CloudSettingsStatus.Testing, issue = null)
        scope.launch {
            try {
                DoubaoRecognition().recognize(config, ::cloudProbeSource, {}, {})
                mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Tested)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(status = CloudSettingsStatus.Ready,
                    issue = (error as? CloudRecognitionException)?.issue ?: CloudIssue.Network)
            }
        }
    }
}

/** Exactly the source used by the service-check action; never accesses the microphone. */
internal fun cloudProbeSource(): PcmSource = object : PcmSource {
    private var remaining = 10
    override fun read(): FloatArray? {
        if (remaining-- <= 0) return null
        Thread.sleep(100)
        return FloatArray(1600)
    }
    override fun requestStop() = Unit
    override fun close() = Unit
}
