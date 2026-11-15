package seeyuer.sayin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import seeyuer.sayin.data.SpeakingDraftStore
import seeyuer.sayin.quick.RecordingService
import seeyuer.sayin.data.VoiceLinkRepository
import seeyuer.sayin.data.receptionScope

internal class VoiceLinkViewModel(application: Application) : AndroidViewModel(application) {
    val repository = VoiceLinkRepository(application)
    val speaking = SpeakSession(application)
    private val foregroundOwners = mutableSetOf<Any>()
    init {
        speaking.onAutoSend = ::send
        speaking.requestMicrophoneService = {
            try { ContextCompat.startForegroundService(application, RecordingService.intent(application)) }
            catch (_: Exception) { speaking.microphoneServiceFailed() }
        }
        viewModelScope.launch { repository.state.collect { speaking.updateReceiver(it) } }
        viewModelScope.launch { speaking.recognition.state.collect { refreshConnection() } }
        speaking.draftRestoring = true
        val initialDraftId = speaking.draftId
        viewModelScope.launch {
            val store = SpeakingDraftStore(application)
            try {
                val saved = withContext(Dispatchers.IO) { store.load() }
                if (saved != null && speaking.draftId == initialDraftId) speaking.restore(saved)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { speaking.draftStorageIssue = true; return@launch }
            finally { speaking.draftRestoring = false }
            snapshotFlow { speaking.savedState }.collectLatest { saved ->
                delay(150)
                try { withContext(Dispatchers.IO) { store.save(saved) }; speaking.draftStorageIssue = false }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { speaking.draftStorageIssue = true }
            }
        }
    }
    fun foreground(owner: Any, active: Boolean) {
        if (active) foregroundOwners.add(owner) else foregroundOwners.remove(owner)
        refreshConnection()
    }
    private fun refreshConnection() {
        repository.foreground(foregroundOwners.isNotEmpty() || speaking.recognition.state.value.busy)
    }
    fun send() {
        if (speaking.markSubmitted()) repository.send(checkNotNull(speaking.submitted).value.text, checkNotNull(speaking.submitted).id, speaking.receiver.receptionScope)
    }
    override fun onCleared() { speaking.close(); repository.close() }
}
