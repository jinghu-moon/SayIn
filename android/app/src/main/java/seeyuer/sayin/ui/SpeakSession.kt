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
import kotlinx.coroutines.flow.MutableStateFlow
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.ReceptionScope
import seeyuer.sayin.data.receptionScope
import seeyuer.sayin.data.SavedSpeakingDraft
import seeyuer.sayin.translation.*
import java.io.File
import java.io.InputStream

/** One process-local speaking session, independent of any page, Activity or entry point.
 * A send ticket is deliberately ephemeral: changing visibility can never restore it.
 */
internal class SpeakSession(
    context: Context,
    initialDraft: TextFieldValue = TextFieldValue(),
    private val onDraftChanged: (TextFieldValue) -> Unit = {},
    val recognition: RecognitionEngine = SpeechRecognition(context),
    private val translator: Translator = SerialTranslator(),
    private val translationModel: TranslationModel = TranslationCatalog.read(context).defaultModel,
    private val modelStore: TranslationModelStore = TranslationModelStore(context, translationModel),
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
    var translation by mutableStateOf(DraftTranslation()); private set
    var translationShown by mutableStateOf(false); private set
    var modelPreparation by mutableStateOf<ModelPreparationProgress?>(null); private set
    var translationModelReady by mutableStateOf(modelStore.prepared); private set
    var modelPreparationFailed by mutableStateOf(false); private set
    var translationStopping by mutableStateOf(false); private set
    val translationPreparing get() = modelPreparation != null
    val canTranslateDraft get() = !busy && !requestLocked && !draftRestoring && !draftStorageIssue &&
        draft.text.isNotBlank() && draft.composition == null && !translation.running && !translationStopping && !translationPreparing &&
        model !in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
    val displayedDraft get() = if (translationShown) translation.value else draft
    val sendingTranslation get() = translationShown
    val canSendRepresentation get() = !translationStopping && !translation.running && !translationPreparing &&
        (!translationShown || translation.complete && translation.ticket!!.matches(draftId, interaction.version))
    val savedState get(): SavedSpeakingDraft {
        val request = submitted?.takeIf { it.state != "injected" }
        return SavedSpeakingDraft(draftId, draft.text, draft.selection.start, draft.selection.end,
            request?.id, request?.value?.text, request?.state,
            translation.takeIf { it.complete }?.value?.text,
            translation.takeIf { it.complete }?.ticket?.modelSha256,
            translation.takeIf { it.complete }?.ticket?.templateRevision,
            translationShown && translation.complete, request?.translated ?: false)
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
    private var translationJob: Job? = null
    private var preparationJob: Job? = null
    private var backgroundRelease: Job? = null
    private var waitingForTranslationStop = false
    private var uiLanguage = context.resources.configuration.locales.toLanguageTags()
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
        if (value) { visibleOwners.add(owner); backgroundRelease?.cancel() } else {
            visibleOwners.remove(owner)
            cancelAutoSend()
            if (explicitFinish) recordingMayAutoSend = false
            if (!visible) preparingToRecord = false
            if (!visible) {
                backgroundRelease?.cancel()
                backgroundRelease = scope.launch {
                    // Allows an Activity/quick-card handover to retain the same task owner.
                    delay(500)
                    if (!visible) { stopTranslation(); preparationJob?.cancel(); translator.release() }
                }
            }
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
        if (item.state == "injected" && request.translated && draftId == request.id) {
            // Retaining the original must not retain an already consumed request identity after restart.
            draftId = UUID.randomUUID().toString()
            translation.ticket?.let { translation = translation.copy(ticket = it.copy(draftId = draftId)) }
        }
        if (item.state == "injected" && !request.translated && draftId == request.id && draft.text == request.value.text) {
            changeDraft(TextFieldValue())
        }
    }

    fun restore(saved: SavedSpeakingDraft) {
        invalidateTranslation(); cancelAutoSend(); draftId = saved.id
        draft = TextFieldValue(saved.text, TextRange(saved.start, saved.end))
        if (saved.translationText != null) {
            translation = DraftTranslation(TranslationTicket(UUID.randomUUID().toString(), draftId, interaction.version, draft.text,
                checkNotNull(saved.translationModelSha256), checkNotNull(saved.translationTemplate)), TextFieldValue(saved.translationText), end = TranslationEnd.Complete)
            translationShown = saved.translationShown
        }
        submitted = saved.requestId?.let { SubmittedDraft(it, TextFieldValue(saved.requestText.orEmpty()), saved.requestState ?: "saved", saved.requestTranslated) }
        // The ledger, not this snapshot, remains authoritative about execution.
        updateReceiver(receiver)
    }

    fun changeDraft(value: TextFieldValue, fromRecognition: Boolean = false) {
        if (draft == value) return
        invalidateTranslation()
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
        stopTranslation(); cancelAutoSend(); source = value; recognition.clearResult()
        preferences.edit().putString("input_source", value.name).apply()
        if (value == SpeakInputSource.Local) scope.launch { translator.release(); if (source == value) recognition.prepareLocal() }
    }

    fun changeSendMode(value: SpeakSendMode) {
        if (busy || requestLocked) return
        cancelAutoSend(); recordingMayAutoSend = false; sendMode = value
        preferences.edit().putBoolean("auto_send", value == SpeakSendMode.Automatic).apply()
    }

    fun start(cloud: CloudConfiguration?) {
        if (!visible || busy || requestLocked || draftRestoring || !source.isRecognition || (source == SpeakInputSource.Cloud && cloud == null)) return
        invalidateTranslation(); cancelAutoSend(); undoDraft = null; submitted = null; explicitFinish = false
        val selection = draft.selection
        originalDraft = draft.copy(composition = null)
        insertion = RecognitionInsertion(draft.text, selection.min, selection.max)
        recordingScope = receiver.receptionScope; recordingMayAutoSend = true
        pendingCloud = if (source == SpeakInputSource.Cloud) cloud else null
        preparingToRecord = true
        waitingForTranslationStop = true
        scope.launch {
            translator.release()
            waitingForTranslationStop = false
            if (preparingToRecord && visible) {
                if (source == SpeakInputSource.Local && model != LocalModelPhase.Ready) recognition.prepareLocal() else requestRecording()
            }
        }
    }

    private fun requestRecording() {
        if (waitingForTranslationStop) return
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
        if (translationShown || translation.ticket != null || translationStopping) return
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
        val value = displayedDraft
        if (busy || requestLocked || draftRestoring || draftStorageIssue || !canSendRepresentation || !receiver.ready || value.text.isBlank() || value.composition != null ||
            runCatching { seeyuer.sayin.data.normalizedText(value.text) }.isFailure) return false
        if (submitted != null) {
            draftId = UUID.randomUUID().toString()
            translation.ticket?.let { translation = translation.copy(ticket = it.copy(draftId = draftId)) }
        }
        cancelAutoSend(); submitted = SubmittedDraft(draftId, value, translated = sendingTranslation); return true
    }

    fun showTranslation(value: Boolean) { cancelAutoSend(); translationShown = value && translation.ticket != null }

    fun changeDisplayedDraft(value: TextFieldValue) {
        if (!translationShown) { changeDraft(value); return }
        if (!translation.complete || requestLocked || draftRestoring) return
        cancelAutoSend(); translation = translation.copy(value = value)
    }

    fun translateDraft() {
        if (!canTranslateDraft) return
        cancelAutoSend(); recordingMayAutoSend = false
        val ticket = TranslationTicket(UUID.randomUUID().toString(), draftId, interaction.version, draft.text,
            translationModel.sha256, translationModel.templateRevision)
        translation = DraftTranslation(ticket = ticket, running = true)
        translationShown = true
        translationJob = scope.launch {
            val updates = MutableStateFlow(TranslationStream())
            val observer = launch {
                updates.collect { update ->
                    if (translation.running && translation.accepts(ticket.id, draftId, interaction.version)) {
                        translation = translation.copy(progress = update.progress,
                            value = update.partial?.let(::TextFieldValue) ?: translation.value)
                    }
                }
            }
            try {
                val result = translator.translate(modelStore.file, translationModel, ticket.source,
                    onProgress = { progress -> updates.value = updates.value.copy(progress = progress) },
                    onPartial = { text -> updates.value = updates.value.copy(partial = text) })
                if (translation.accepts(ticket.id, draftId, interaction.version)) {
                    translation = translation.copy(running = false, end = result.end, value = TextFieldValue(result.text))
                    if (result.end == TranslationEnd.ModelMissing || result.end == TranslationEnd.Integrity) translationModelReady = false
                }
            } finally { observer.cancel(); if (translation.ticket?.id == ticket.id) translation = translation.copy(running = false) }
        }
    }

    fun stopTranslation() {
        if (!translation.running && translationJob?.isActive != true) return
        translator.cancel(); translationJob?.cancel()
        translation = translation.copy(running = false, end = TranslationEnd.Cancelled)
        translationStopping = true
        scope.launch { translator.release(); translationStopping = false }
    }

    private fun invalidateTranslation() {
        stopTranslation()
        translation = DraftTranslation(); translationShown = false
    }

    fun releaseTranslation() { stopTranslation(); preparationJob?.cancel(); scope.launch { translator.release() } }

    fun uiLanguageChanged(tags: String) {
        if (tags == uiLanguage) return
        uiLanguage = tags; cancelAutoSend(); releaseTranslation()
    }

    fun prepareTranslationModel(input: (() -> InputStream)? = null) {
        if (busy || translationPreparing || translation.running || translationStopping) return
        cancelAutoSend(); modelPreparationFailed = false
        modelPreparation = ModelPreparationProgress(if (input == null) ModelPreparationStage.Downloading else ModelPreparationStage.Copying, 0, translationModel.bytes)
        preparationJob = scope.launch {
            val updates = MutableStateFlow(checkNotNull(modelPreparation))
            val observer = launch { updates.collect { modelPreparation = it } }
            try {
                translator.release()
                val progress: (ModelPreparationProgress) -> Unit = { value -> updates.value = value }
                if (input == null) modelStore.download(progress) else withContext(Dispatchers.IO) { modelStore.import(input(), progress) }
                translationModelReady = modelStore.prepared
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { modelPreparationFailed = true }
            finally { observer.cancel(); modelPreparation = null; preparationJob = null }
        }
    }
    fun cancelModelPreparation() { preparationJob?.cancel() }

    /** The uncertain request stays immutable in the ledger; this does not retry it or release the desktop gate. */
    fun newDraft() {
        if (busy || submitted?.state != "uncertain" || !receiver.ready) return
        submitted = null; changeDraft(TextFieldValue())
    }

    override fun close() { cancelAutoSend(); translator.close(); recognition.close(); scope.cancel() }
}

internal data class SubmittedDraft(val id: String, val value: TextFieldValue, val state: String = "saved", val translated: Boolean = false)
private data class TranslationStream(val progress: TranslationProgress? = null, val partial: String? = null)
