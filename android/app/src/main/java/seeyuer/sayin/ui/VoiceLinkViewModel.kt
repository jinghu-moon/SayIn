package seeyuer.sayin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import seeyuer.sayin.data.AutoSendDestination
import seeyuer.sayin.quick.RecordingService
import seeyuer.sayin.data.VoiceLinkRepository
import seeyuer.sayin.data.autoSendDestination
import seeyuer.sayin.data.receptionScope

internal class VoiceLinkViewModel(application: Application) : AndroidViewModel(application) {
    val repository = VoiceLinkRepository(application)
    val speaking = SpeakSession(application)
    private val foregroundOwners = mutableSetOf<Any>()
    init {
        speaking.onSendReady = ::send
        speaking.requestMicrophoneService = { generation ->
            try { ContextCompat.startForegroundService(application, RecordingService.intent(application, generation)) }
            catch (_: Exception) { speaking.microphoneServiceFailed(generation) }
        }
        viewModelScope.launch { repository.state.collect { speaking.updateReceiver(it) } }
        viewModelScope.launch { speaking.recognition.state.collect { refreshConnection() } }
        speaking.draftRestoring = true
        val initialDraftId = speaking.draftId
        viewModelScope.launch {
            try {
                val saved = repository.loadSpeakingDraft()
                if (saved != null && speaking.draftId == initialDraftId) speaking.restore(saved)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { speaking.draftStorageIssue = true; return@launch }
            finally { speaking.draftRestoring = false }
            snapshotFlow { speaking.savedState }.collectLatest { saved ->
                delay(150)
                if (saved == speaking.savedState) repository.saveSpeakingDraft(saved)
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
    fun send(destination: AutoSendDestination? = null) {
        if (destination != null && destination != repository.state.value.autoSendDestination) return
        if (!speaking.markSubmitted()) return
        repository.send(speaking.savedState, destination?.scope ?: speaking.receiver.receptionScope, destination)
    }
    override fun onCleared() { speaking.close(); repository.close() }
}
