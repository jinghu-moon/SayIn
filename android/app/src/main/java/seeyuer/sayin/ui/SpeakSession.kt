package seeyuer.sayin.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.ReceptionScope
import seeyuer.sayin.data.receptionScope
import seeyuer.sayin.data.SavedSpeakingDraft

/** One process-local speaking session, independent of any page, Activity or entry point.
 * A send ticket is deliberately ephemeral: changing visibility can never restore it.
 */
internal class SpeakSession(
    context: Context,
    initialDraft: TextFieldValue = TextFieldValue(),
    private val onDraftChanged: (TextFieldValue) -> Unit = {},
    val recognition: RecognitionEngine = SpeechRecognition(context),
) : Closeable {
    private val preferences = context.getSharedPreferences("presentation", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var draft by mutableStateOf(initialDraft); private set
    var draftId by mutableStateOf(UUID.randomUUID().toString()); private set
    var source by mutableStateOf(runCatching { SpeakInputSource.valueOf(preferences.getString("input_source", "Cloud")!!) }.getOrDefault(SpeakInputSource.Cloud)); private set
    var sendMode by mutableStateOf(if (preferences.getBoolean("auto_send", false)) SpeakSendMode.Automatic else SpeakSendMode.Manual); private set
    var interaction by mutableStateOf(SpeakInteraction()); private set
    var asr by mutableStateOf(RecognitionState()); private set
    var model by mutableStateOf(LocalModelPhase.Unloaded); private set
    var recordingStartedAtMillis by mutableStateOf(0L); private set
    var receiver by mutableStateOf(ClientState()); private set
    var secondsRemaining by mutableStateOf(3); private set
    var preparingToRecord by mutableStateOf(false); private set
    var microphoneDenied by mutableStateOf(false)
    var undoDraft by mutableStateOf<TextFieldValue?>(null); private set
    var submitted by mutableStateOf<SubmittedDraft?>(null); private set
    var draftStorageIssue by mutableStateOf(false)
    var draftRestoring by mutableStateOf(false)
    val savedState get(): SavedSpeakingDraft {
        val request = submitted?.takeIf { it.state != "injected" }
        return SavedSpeakingDraft(draftId, draft.text, draft.selection.start, draft.selection.end,
            request?.id, request?.value?.text, request?.state)
    }
    var onAutoSend: () -> Unit = {}
    var requestMicrophoneService: (() -> Unit)? = null
    private var insertion: RecognitionInsertion? = null
    private var originalDraft: TextFieldValue? = null
    private var acceptRecognition = false
    private var recognitionHadText = false
    private var explicitFinish = false
    private var recordingScope: ReceptionScope? = null
    private var recordingMayAutoSend = false
    private var pendingCloud: CloudConfiguration? = null
    private var countdownJob: Job? = null
    private val visibleOwners = mutableSetOf<Any>()
    private val visible get() = visibleOwners.isNotEmpty()
    val busy get() = asr.busy || preparingToRecord
    val requestLocked get() = submitted?.state in setOf("saved", "accepted", "held", "executing", "review", "uncertain")

    init {
        scope.launch { recognition.state.collect { result ->
            if (result.phase == RecognitionPhase.Listening && asr.phase != RecognitionPhase.Listening) recordingStartedAtMillis = SystemClock.elapsedRealtime()
            asr = result
            val range = insertion
            if (acceptRecognition && range != null && result.session != 0L) {
                if (result.transcript.isNotEmpty() || recognitionHadText) {
                    recognitionHadText = result.transcript.isNotEmpty()
                    val value = if (result.transcript.isEmpty()) originalDraft ?: TextFieldValue(range.original, TextRange(range.start, range.end))
                        else TextFieldValue(range.text(result.transcript), TextRange(range.cursor(result.transcript)))
                    changeDraft(value, fromRecognition = true)
                }
                if (!result.busy) {
                    acceptRecognition = false
                    if (result.finished && explicitFinish && result.transcript.isNotBlank() && visible) {
                        val mode = if (recordingMayAutoSend && recordingScope == receiver.receptionScope) sendMode else SpeakSendMode.Manual
                        completeInput(mode)
                    }
                    explicitFinish = false
                }
            }
        } }
        scope.launch { recognition.model.collect {
            model = it
            if (preparingToRecord && it == LocalModelPhase.Ready) requestRecording()
            if (it == LocalModelPhase.Failed) preparingToRecord = false
        } }
    }

    fun setVisible(owner: Any, value: Boolean) {
        if (value) visibleOwners.add(owner) else {
            visibleOwners.remove(owner)
            cancelAutoSend()
            if (explicitFinish) recordingMayAutoSend = false
            if (!visible) preparingToRecord = false
        }
    }

    fun updateReceiver(value: ClientState) {
        if (receiver.receptionScope != value.receptionScope) {
            cancelAutoSend(); recordingMayAutoSend = false
        }
        receiver = value
        val request = submitted ?: return
        if (value.rejectedDraft == request.id) { submitted = request.copy(state = "failed"); return }
        val item = value.history.firstOrNull { it.id == request.id } ?: return
        submitted = request.copy(state = item.state)
        if (item.state == "injected" && draftId == request.id && draft.text == request.value.text) {
            changeDraft(TextFieldValue())
        }
    }

    fun restore(saved: SavedSpeakingDraft) {
        cancelAutoSend(); draftId = saved.id
        draft = TextFieldValue(saved.text, TextRange(saved.start, saved.end))
        submitted = saved.requestId?.let { SubmittedDraft(it, TextFieldValue(saved.requestText.orEmpty()), saved.requestState ?: "saved") }
        // The ledger, not this snapshot, remains authoritative about execution.
        updateReceiver(receiver)
    }

    fun changeDraft(value: TextFieldValue, fromRecognition: Boolean = false) {
        if (draft == value) return
        if (!fromRecognition) { cancelAutoSend(); if (asr.busy) cancelRecording(restore = false) }
        if (value.text != draft.text) {
            interaction = interaction.inputChanged()
            draftId = UUID.randomUUID().toString()
        } else if (value.selection != draft.selection || value.composition != draft.composition) cancelAutoSend()
        draft = value
        if (!fromRecognition) undoDraft = null
        onDraftChanged(value)
    }

    fun selectSource(value: SpeakInputSource) {
        if (busy || requestLocked || value == source) return
        cancelAutoSend(); source = value; recognition.clearResult()
        preferences.edit().putString("input_source", value.name).apply()
        if (value == SpeakInputSource.Local) recognition.prepareLocal()
    }

    fun changeSendMode(value: SpeakSendMode) {
        if (busy || requestLocked) return
        cancelAutoSend(); recordingMayAutoSend = false; sendMode = value
        preferences.edit().putBoolean("auto_send", value == SpeakSendMode.Automatic).apply()
    }

    fun start(cloud: CloudConfiguration?) {
        if (!visible || busy || requestLocked || draftRestoring || !source.isRecognition || (source == SpeakInputSource.Cloud && cloud == null)) return
        cancelAutoSend(); undoDraft = null; submitted = null; explicitFinish = false
        val selection = draft.selection
        originalDraft = draft.copy(composition = null)
        insertion = RecognitionInsertion(draft.text, selection.min, selection.max)
        recordingScope = receiver.receptionScope; recordingMayAutoSend = true
        pendingCloud = if (source == SpeakInputSource.Cloud) cloud else null
        preparingToRecord = true
        if (source == SpeakInputSource.Local && model != LocalModelPhase.Ready) recognition.prepareLocal() else requestRecording()
    }

    private fun requestRecording() {
        if (!preparingToRecord || !visible) { preparingToRecord = false; return }
        requestMicrophoneService?.invoke() ?: startPreparedRecording()
    }

    /** Called only after Android has accepted the microphone foreground service. */
    fun startPreparedRecording() {
        if (!preparingToRecord || !visible) { preparingToRecord = false; return }
        preparingToRecord = false; recognitionHadText = false; acceptRecognition = true
        recognition.start(pendingCloud)
        pendingCloud = null
    }

    fun microphoneServiceFailed() { preparingToRecord = false; acceptRecognition = false; asr = asr.copy(issue = RecognitionIssue.Microphone) }
    fun finishRecording() { if (asr.phase == RecognitionPhase.Listening) { explicitFinish = true; recordingMayAutoSend = recordingMayAutoSend && visible; recognition.finish() } }

    fun cancelRecording(restore: Boolean = true) {
        acceptRecognition = false; explicitFinish = false; preparingToRecord = false; pendingCloud = null
        cancelAutoSend(); recognition.cancel()
        if (restore) originalDraft?.let { draft = it; draftId = UUID.randomUUID().toString(); onDraftChanged(draft) }
        insertion = null; originalDraft = null
    }

    fun clearDraft() {
        if (busy || requestLocked || draftRestoring) return
        val old = draft
        changeDraft(TextFieldValue()); undoDraft = old.takeIf { it.text.isNotEmpty() }; submitted = null
    }
    fun undoClear() { val old = undoDraft ?: return; changeDraft(old); undoDraft = null }
    fun edit() { cancelAutoSend(); interaction = interaction.edit() }
    fun cancelAutoSend() { countdownJob?.cancel(); countdownJob = null; interaction = interaction.cancelCountdown() }

    fun completeInput(mode: SpeakSendMode = sendMode) {
        cancelAutoSend()
        if (!visible || requestLocked || draftRestoring) return
        interaction = interaction.finish(draft.text, draft.composition != null, mode, receiver.receptionScope, SystemClock.elapsedRealtime())
        val ticket = interaction.countdown ?: return
        countdownJob = scope.launch {
            while (visible && sendMode == SpeakSendMode.Automatic && interaction.countdown == ticket) {
                val now = SystemClock.elapsedRealtime()
                if (!ticket.matches(draft.text, interaction.version, draft.composition != null, receiver.receptionScope)) { cancelAutoSend(); return@launch }
                secondsRemaining = ticket.secondsRemaining(now)
                val consumed = interaction.consumeDue(draft.text, draft.composition != null, receiver.receptionScope, now)
                if (consumed != null) { interaction = consumed; onAutoSend(); return@launch }
                delay(100)
            }
        }
    }

    fun markSubmitted(): Boolean {
        if (busy || requestLocked || draftRestoring || !receiver.ready || draft.text.isBlank() || draft.composition != null ||
            runCatching { seeyuer.sayin.data.normalizedText(draft.text) }.isFailure) return false
        if (submitted != null) draftId = UUID.randomUUID().toString()
        cancelAutoSend(); submitted = SubmittedDraft(draftId, draft); return true
    }

    /** The uncertain request stays immutable in the ledger; this does not retry it or release the desktop gate. */
    fun newDraft() {
        if (busy || submitted?.state != "uncertain" || !receiver.ready) return
        submitted = null; changeDraft(TextFieldValue())
    }

    override fun close() { cancelAutoSend(); recognition.close(); scope.cancel() }
}

internal data class SubmittedDraft(val id: String, val value: TextFieldValue, val state: String = "saved")
