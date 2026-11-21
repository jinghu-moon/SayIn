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
    val profiles: CloudProfiles = CloudProfiles(),
    val status: CloudSettingsStatus = CloudSettingsStatus.Loading,
    val issue: CloudIssue? = null,
) {
    val config: CloudConfiguration? = profiles.credentials?.let { CloudConfiguration(it, profiles.hotwords) }
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
                val profiles = withContext(Dispatchers.IO) { store.load() } ?: CloudProfiles()
                mutableState.value = CloudSettingsState(profiles, CloudSettingsStatus.Ready)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = CloudSettingsState(status = CloudSettingsStatus.Ready, issue = CloudIssue.Storage) }
        }
    }
    fun saveCredentials(build: () -> CloudCredentials) {
        val snapshot = mutableState.value
        if (snapshot.busy || snapshot.issue == CloudIssue.Storage) return
        val next = try {
            when (val credentials = build()) {
                is DoubaoCredentials -> snapshot.profiles.copy(selected = CloudProvider.Doubao, doubao = credentials)
                is QwenCredentials -> snapshot.profiles.copy(selected = CloudProvider.Qwen, qwen = credentials)
            }
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(issue = CloudIssue.Configuration)
            return
        }
        persist(next)
    }
    fun select(provider: CloudProvider) {
        val profiles = mutableState.value.profiles
        if (provider != profiles.selected) persist(profiles.copy(selected = provider))
    }
    fun saveHotwords(text: String) {
        val words = try { parseHotwords(text) } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(issue = CloudIssue.Configuration)
            return
        }
        persist(mutableState.value.profiles.copy(hotwords = words))
    }
    private fun persist(profiles: CloudProfiles, status: CloudSettingsStatus = CloudSettingsStatus.Saving) {
        val snapshot = mutableState.value
        if (snapshot.busy || snapshot.issue == CloudIssue.Storage) return
        mutableState.value = snapshot.copy(status = status, issue = null)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(profiles) }
                mutableState.value = CloudSettingsState(profiles, if (status == CloudSettingsStatus.Clearing) CloudSettingsStatus.Ready
                    else CloudSettingsStatus.Saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = snapshot.copy(status = CloudSettingsStatus.Ready, issue = CloudIssue.Storage) }
        }
    }
    fun clear() {
        val snapshot = mutableState.value
        if (snapshot.busy) return
        if (snapshot.issue != CloudIssue.Storage) {
            val profiles = snapshot.profiles
            val next = when (profiles.selected) {
                CloudProvider.Doubao -> profiles.copy(doubao = null)
                CloudProvider.Qwen -> profiles.copy(qwen = null)
            }
            persist(next, CloudSettingsStatus.Clearing)
            return
        }
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
                recognizeCloud(config, ::cloudProbeSource, {}, {})
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
