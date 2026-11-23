package seeyuer.sayin.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.AutoSendDestination
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.ReceptionScope
import seeyuer.sayin.data.receptionScope
import seeyuer.sayin.data.SavedSpeakingDraft
import seeyuer.sayin.data.autoSendDestination
import seeyuer.sayin.polish.*
import seeyuer.sayin.translation.*
import seeyuer.sayin.models.*
import seeyuer.sayin.BuildConfig
import okhttp3.Call

/** One process-local speaking session, independent of any page, Activity or entry point.
 * A send ticket is deliberately ephemeral: changing visibility can never restore it.
 */
internal class SpeakSession(
    context: Context,
    initialDraft: TextFieldValue = TextFieldValue(),
    private val onDraftChanged: (TextFieldValue) -> Unit = {},
    val recognition: RecognitionEngine = SpeechRecognition(context),
    private val translator: Translator = SerialTranslator(),
    private val catalog: TranslationCatalog = TranslationCatalog.read(context),
    translationModel: TranslationModel = catalog.models.firstOrNull {
        it.id == context.getSharedPreferences("presentation", 0).getString("translation_model", null)
    } ?: catalog.defaultModel,
    modelStore: TranslationModelStore? = null,
) : Closeable {
    private val preferences = context.getSharedPreferences("presentation", 0)
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val polish = PolishManager(appContext)
    var polishState by mutableStateOf(PolishState()); private set
    private var polishRequest: PolishRequest? = null

    private class PolishRequest {
        @Volatile var cancelled = false
            private set
        @Volatile private var call: Call? = null

        fun attach(value: Call) {
            call = value
            if (cancelled) value.cancel()
        }

        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }
    private var voiceOrigin = false
    private var typedSinceVoice = false
    private var recordingStartedEmpty = false
    private val localCatalog = LocalModelCatalog(context, catalog)
    private val storage = LocalModelStorage(context, localCatalog)
    var currentRecognitionModel by mutableStateOf(
        localCatalog.recognition.firstOrNull { it.id == preferences.getString("recognition_model", null) }
            ?: localCatalog.recognition.first { it.id == localCatalog.defaultRecognitionId },
    ); private set
    var currentTranslationModel by mutableStateOf(translationModel); private set
    val translationModels get() = catalog.models
    private var translationStore = modelStore ?: createTranslationStore(translationModel)
    val localModels = LocalModelManager(localCatalog, storage, scope, ::refreshModels)
    var changingModel by mutableStateOf(false); private set

    private fun createTranslationStore(model: TranslationModel): TranslationModelStore {
        val directory = storage.directory(localCatalog.translation.single { it.id == model.id })
        return TranslationModelStore(appContext, model, importedFile = directory.resolve(model.fileName))
    }

    private fun refreshModels(imported: List<LocalModel>) {
        translationModelReady = translationStore.ready
        if (!translationModelReady) {
            val candidates = imported.filter { it.category == ModelCategory.Translation }
            val preferred = candidates.firstOrNull { it.id == localCatalog.defaultTranslationId } ?: candidates.firstOrNull()
            preferred?.let { setTranslationModel(it.id) }
        }
        if (!recognitionInstalled) {
            val candidates = imported.filter { it.category == ModelCategory.Recognition }
            val preferred = candidates.firstOrNull { it.id == localCatalog.defaultRecognitionId } ?: candidates.firstOrNull()
            preferred?.let { setRecognitionModel(it) }
        }
    }

    val recognitionInstalled get() = currentRecognitionModel.id in localModels.installed || BuildConfig.LOCAL_MODEL_FIXTURES
    private val batchRecognition get() = source == SpeakInputSource.Local && currentRecognitionModel.id != localCatalog.defaultRecognitionId
    val recordingCanFinish get() = recordingHasText || batchRecognition
    val canChangeModels get() = !busy && !requestLocked && !translation.running && !translationStopping &&
        !changingModel && !localModels.busy

    var draft by mutableStateOf(initialDraft); private set
    var draftId by mutableStateOf(UUID.randomUUID().toString()); private set
    var source by mutableStateOf(runCatching { SpeakInputSource.valueOf(preferences.getString("input_source", "Cloud")!!) }.getOrDefault(SpeakInputSource.Cloud)); private set
    var lastVoiceSource by mutableStateOf(runCatching { SpeakInputSource.valueOf(preferences.getString("voice_source", "Cloud")!!) }
        .getOrDefault(SpeakInputSource.Cloud).takeIf { it.isRecognition } ?: SpeakInputSource.Cloud); private set
    var sendMode by mutableStateOf(if (preferences.getBoolean("auto_send", false)) SpeakSendMode.Automatic else SpeakSendMode.Manual); private set
    var continuousDictation by mutableStateOf(preferences.getBoolean("continuous_dictation", false)); private set
    /** Delay between the final text and the automatic send; it is a default policy, not a per-app rule. */
    var autoSendSeconds by mutableIntStateOf(
        preferences.getInt("auto_send_seconds", SpeakInteraction.DEFAULT_DELAY_SECONDS)
            .coerceIn(SpeakInteraction.MIN_DELAY_SECONDS, SpeakInteraction.MAX_DELAY_SECONDS),
    ); private set
    var appSendPolicies by mutableStateOf(loadAppSendPolicies()); private set
    var interaction by mutableStateOf(SpeakInteraction()); private set
    var asr by mutableStateOf(RecognitionState()); private set
    var model by mutableStateOf(LocalModelPhase.Unloaded); private set
    var recordingStartedAtMillis by mutableStateOf(0L); private set
    var autoStopped by mutableStateOf(false); private set
    var recordingDurationMillis by mutableStateOf(0L); private set
    private val history = DraftHistory<SpeakingSnapshot>()
    private var historyRevision by mutableIntStateOf(0)
    private var recordingSnapshot: SpeakingSnapshot? = null
    private var recordingDurationBefore = 0L
    private var preparationGeneration = 0L
    private var sendAfterFinal = false
    private var expectedRecognitionSession = 0L
    private val historyEnabled get() = !busy && !requestLocked && !draftRestoring && !draftStorageIssue &&
        !translation.running && !translationStopping && !translationPreparing && !polishState.running &&
        displayedDraft.composition == null
    val canUndo get() = historyRevision.let { historyEnabled && history.canUndo }
    val canRedo get() = historyRevision.let { historyEnabled && history.canRedo }
    val recordingHasText get() = recognitionHadText && asr.transcript.isNotBlank()
    var receiver by mutableStateOf(ClientState()); private set
    var secondsRemaining by mutableStateOf(SpeakInteraction.DEFAULT_DELAY_SECONDS); private set
    var preparingToRecord by mutableStateOf(false); private set
    var microphoneDenied by mutableStateOf(false)
    var undoDraft by mutableStateOf<TextFieldValue?>(null); private set
    var submitted by mutableStateOf<SubmittedDraft?>(null); private set
    var draftStorageIssue by mutableStateOf(false)
    var draftRestoring by mutableStateOf(false)
    private var translationState by mutableStateOf(DraftTranslation())
    /** Content revision of [translation]; every translation mutation advances it independently of [interaction]. */
    var translationRevision by mutableStateOf(0L); private set
    var translation: DraftTranslation
        get() = translationState
        private set(value) {
            if (translationState == value) return
            translationState = value
            translationRevision++
            invalidateAssist()
        }
    var translationShown by mutableStateOf(false); private set
    /** Monotonic generation of the single active insertion ticket. */
    var assistEpoch by mutableStateOf(0L); private set
    var translationModelReady by mutableStateOf(translationStore.ready); private set
    var translationStopping by mutableStateOf(false); private set
    val translationPreparing get() = changingModel || localModels.deleting != null
    val canTranslateDraft get() = !busy && !requestLocked && !draftRestoring && !draftStorageIssue &&
        draft.text.isNotBlank() && draft.composition == null && !translation.running && !translationStopping && !translationPreparing &&
        !polishState.running && model !in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
    val displayedDraft get() = if (translationShown) translation.value else draft
    val sendingTranslation get() = translationShown

    val canOpenPolish get() = visible && !busy && !requestLocked && !draftRestoring && !draftStorageIssue &&
        !translation.running && !translationStopping && !translationPreparing && !polishState.running &&
        displayedDraft.composition == null && displayedDraft.text.isNotBlank() && !translationShown
    val canPolish get() = canOpenPolish && polish.ready
    val polishConfirmationPending get() = polishState.confirmationPending
    fun suggestedPolishMode(): PolishMode = when {
        draft.selection.min != draft.selection.max -> PolishMode.Text
        voiceOrigin && !typedSinceVoice -> PolishMode.Voice
        else -> PolishMode.Text
    }

    val canSendRepresentation get() = !translationStopping && !translation.running && !translationPreparing && !polishState.running &&
        (!translationShown || translation.complete && translation.ticket!!.matches(draftId, interaction.version))
    /** One insertion may start now: draft editable, and no recorder, translation, restore or request contention. */
    val assistEditable get() = visible && !busy && !requestLocked && !draftRestoring && !draftStorageIssue &&
        !translation.running && !translationStopping && !translationPreparing && !polishState.running &&
        displayedDraft.composition == null && (!translationShown || translation.complete)
    private val assistRepresentation get() = if (translationShown) DraftRepresentation.Translation else DraftRepresentation.Original
    private fun assistRevision(value: DraftRepresentation) =
        if (value == DraftRepresentation.Translation) translationRevision else interaction.version

    /** Captures the frozen position and advances the generation; only the newest ticket is accepted. */
    fun beginAssistInsertion(): AssistInsertion? {
        if (!assistEditable) return null
        invalidateAssist()
        val value = displayedDraft
        // A missing editable position is resolved at open time, never guessed again during insert.
        val range = DraftAssist.selection(value.text, value.selection.start, value.selection.end)
            ?: TextRange(value.text.length, value.text.length)
        val representation = assistRepresentation
        return AssistInsertion(draftId, representation, assistRevision(representation), range.min, range.max, assistEpoch)
    }

    /** Executes exactly one undoable edit. Every check and the full-result validation run before any state changes. */
    fun insertAssist(ticket: AssistInsertion, text: String): AssistInsertResult {
        if (!assistEditable) return AssistInsertResult.NotEditable
        val representation = assistRepresentation
        if (ticket.epoch != assistEpoch || ticket.representation != representation || ticket.draftId != draftId ||
            ticket.revision != assistRevision(representation)) return AssistInsertResult.Stale
        val value = displayedDraft
        val range = DraftAssist.selection(value.text, ticket.start, ticket.end) ?: return AssistInsertResult.Stale
        val result = DraftAssist.compose(value.text, range, text)
        if (!DraftAssist.acceptable(result)) return AssistInsertResult.Rejected
        val caret = DraftAssist.caret(range, text)
        cancelAutoSend()
        undoDraft = null
        submitted = null
        history.breakGroup()
        record(snapshot())
        history.breakGroup()
        if (representation == DraftRepresentation.Original) {
            interaction = interaction.inputChanged()
            draftId = UUID.randomUUID().toString()
            typedSinceVoice = true
            voiceOrigin = false
            invalidateTranslation()
            draft = TextFieldValue(result, TextRange(caret))
            onDraftChanged(draft)
        } else {
            translation = translation.copy(value = TextFieldValue(result, TextRange(caret)))
        }
        invalidateAssist()
        return AssistInsertResult.Inserted(caret)
    }

    /** Any edit, recording, visibility, request or translation change makes captured tickets stale. */
    fun invalidateAssist() { assistEpoch++ }
    val savedState get(): SavedSpeakingDraft {
        val request = submitted?.takeIf { it.state != "injected" }
        return SavedSpeakingDraft(draftId, draft.text, draft.selection.start, draft.selection.end,
            request?.id, request?.value?.text, request?.state,
            translation.takeIf { it.complete }?.value?.text,
            translation.takeIf { it.complete }?.ticket?.modelSha256,
            translation.takeIf { it.complete }?.ticket?.templateRevision,
            translationShown && translation.complete, request?.translated ?: false)
    }
    var onSendReady: (AutoSendDestination?) -> Unit = {}
    var requestMicrophoneService: (() -> Unit)? = null
    private var insertion: RecognitionInsertion? = null
    private var originalDraft: TextFieldValue? = null
    private var acceptRecognition = false
    private var recognitionHadText = false
    private var explicitFinish = false
    private var recordingScope: ReceptionScope? = null
    private var recordingMode = SpeakSendMode.Manual
    val recordingUsesAutoSend get() = recordingMode == SpeakSendMode.Automatic
    private var recordingDestination: AutoSendDestination? = null
    private var finishOnSilence = false
    private var imeDestination by mutableStateOf<AutoSendDestination?>(null)
    val waitingForImeCommit get() = imeDestination != null
    private var pendingCloud: CloudConfiguration? = null
    private var countdownJob: Job? = null
    private var translationJob: Job? = null
    private var backgroundRelease: Job? = null
    private var waitingForTranslationStop = false
    private var uiLanguage = context.resources.configuration.locales.toLanguageTags()
    private val visibleOwners = mutableSetOf<Any>()
    private val visible get() = visibleOwners.isNotEmpty()
    val busy get() = asr.busy || preparingToRecord
    val requestLocked get() = submitted?.state in setOf("saved", "accepted", "held", "executing", "review", "uncertain")

    init {
        (recognition as? SpeechRecognition)?.localArtifact = {
            currentRecognitionModel.takeIf { it.id in localModels.installed }
                ?.let { LocalVoiceArtifact(it.id, storage.directory(it)) }
        }
        polish.onConfigurationChanged = {
            cancelPolish()
            polishState = PolishState(confirmationPending = polishState.confirmationPending)
            invalidateTranslation()
        }
        scope.launch { recognition.state.collect { result ->
            if (result.phase == RecognitionPhase.Listening && asr.phase != RecognitionPhase.Listening) recordingStartedAtMillis = SystemClock.elapsedRealtime()
            if (acceptRecognition && asr.phase == RecognitionPhase.Listening && result.phase != RecognitionPhase.Listening)
                recordingDurationMillis = recordingDurationBefore + (SystemClock.elapsedRealtime() - recordingStartedAtMillis).coerceAtLeast(0)
            asr = result
            val range = insertion
            if (acceptRecognition && range != null && result.session == expectedRecognitionSession && result.session != 0L) {
                if (result.transcript.isNotEmpty() || recognitionHadText) {
                    recognitionHadText = result.transcript.isNotEmpty()
                    val value = if (result.transcript.isEmpty()) originalDraft ?: TextFieldValue(range.original, TextRange(range.start, range.end))
                        else TextFieldValue(range.text(result.transcript), TextRange(range.cursor(result.transcript)))
                    changeDraft(value, fromRecognition = true)
                }
                if (!result.busy) {
                    acceptRecognition = false
                    recordingSnapshot?.takeIf { it.draft.text != draft.text }?.let { record(it) }
                    recordingSnapshot = null
                    val direct = sendAfterFinal && recordingScope != null && recordingScope == receiver.receptionScope
                    sendAfterFinal = false
                    if (result.finished && explicitFinish && result.transcript.isNotBlank() && visible) {
                        when {
                            direct -> onSendReady(null)
                            recordingMode == SpeakSendMode.Automatic -> completeInput(
                                recordingMode, recordingDestination, fromRecording = true,
                            )
                            else -> {
                                completeInput(SpeakSendMode.Manual)
                                if (shouldAutoPolish(recordingStartedEmpty, typedSinceVoice, polish.autoPolishBound)) {
                                    startPolish(PolishMode.Voice)
                                }
                            }
                        }
                    }
                    explicitFinish = false
                } else if (result.phase == RecognitionPhase.Listening && result.noSpeech && visible &&
                    recognition.state.value.let { it.session == result.session && it.noSpeech }) {
                    finishRecording(auto = false)
                } else if (result.phase == RecognitionPhase.Listening && result.speechPause && recordingCanFinish && finishOnSilence &&
                    visible && recognition.state.value.let { it.session == result.session && it.speechPause }) {
                    finishRecording(auto = true)
                }
            }
        } }
        scope.launch { recognition.model.collect {
            model = it
            if (preparingToRecord && it == LocalModelPhase.Ready) requestRecording()
            if (it == LocalModelPhase.Failed || it == LocalModelPhase.Missing) preparingToRecord = false
        } }
        localModels.start()
    }

    fun setVisible(owner: Any, value: Boolean) {
        if (value) { visibleOwners.add(owner); backgroundRelease?.cancel() } else {
            visibleOwners.remove(owner)
            invalidateAssist()
            cancelAutoSend()
            recordingDestination = null
            if (!visible) preparingToRecord = false
            if (!visible) {
                backgroundRelease?.cancel()
                backgroundRelease = scope.launch {
                    // Allows an Activity/quick-card handover to retain the same task owner.
                    delay(500)
                    if (!visible) {
                        cancelPolish(); stopTranslation(); translator.release()
                    }
                }
            }
        }
    }

    fun updateReceiver(value: ClientState) {
        val hadCountdown = interaction.countdown != null || waitingForImeCommit
        val receptionChanged = receiver.receptionScope != value.receptionScope
        if (receptionChanged) cancelAutoSend()
        if (receiver.autoSendDestination != value.autoSendDestination) {
            if (!receptionChanged) cancelCountdown()
            if (hadCountdown) interaction = interaction.copy(review = DraftReview.TargetChanged)
            recordingDestination = null
        }
        receiver = value
        val request = submitted ?: return
        if (value.rejectedDraft == request.id) {
            submitted = request.copy(state = "failed")
            if (submitted != request) invalidateAssist()
            return
        }
        val item = value.history.firstOrNull { it.id == request.id } ?: return
        submitted = request.copy(state = item.state, reason = item.reason)
        if (submitted != request) invalidateAssist()
        if (item.state == "injected" && request.state != "injected") { history.clear(); historyRevision++; undoDraft = null }
        if (item.state == "injected" && request.translated && draftId == request.id) {
            // Retaining the original must not retain an already consumed request identity after restart.
            draftId = UUID.randomUUID().toString()
            translation.ticket?.let { translation = translation.copy(ticket = it.copy(draftId = draftId)) }
        }
        if (item.state == "injected" && !request.translated && draftId == request.id && draft.text == request.value.text) {
            changeDraft(TextFieldValue(), fromRecognition = true)
            recordingDurationMillis = 0L
        }
    }

    fun restore(saved: SavedSpeakingDraft) {
        invalidateTranslation(); cancelAutoSend(); history.clear(); historyRevision++; draftId = saved.id
        voiceOrigin = false; typedSinceVoice = saved.text.isNotBlank()
        polishState = PolishState()
        draft = TextFieldValue(saved.text, TextRange(saved.start, saved.end))
        if (saved.translationText != null) {
            translation = DraftTranslation(TranslationTicket(UUID.randomUUID().toString(), draftId, interaction.version, draft.text,
                checkNotNull(saved.translationModelSha256), checkNotNull(saved.translationTemplate)), TextFieldValue(saved.translationText), end = TranslationEnd.Complete)
            translationShown = saved.translationShown
        }
        submitted = saved.requestId?.let { SubmittedDraft(it, TextFieldValue(saved.requestText.orEmpty()), saved.requestState ?: "saved", saved.requestTranslated) }
        // The ledger, not this snapshot, remains authoritative about execution.
        updateReceiver(receiver)
        invalidateAssist()
    }

    fun changeDraft(value: TextFieldValue, fromRecognition: Boolean = false) {
        if (draft == value || !fromRecognition && (requestLocked || draftRestoring)) return
        if (!fromRecognition) autoStopped = false
        if (!fromRecognition && value.text != draft.text) {
            if (!requestLocked) submitted = null
            val composing = draft.composition != null || value.composition != null
            record(snapshot(), if (composing) "composition" else if (value.text.length < draft.text.length) "delete" else "edit",
                composing = draft.composition != null)
        }
        if (value.text != draft.text) {
            invalidateTranslation()
            if (fromRecognition && recordingStartedEmpty) {
                voiceOrigin = true
                typedSinceVoice = false
            } else if (!fromRecognition) {
                voiceOrigin = false
                typedSinceVoice = true
            }
        }
        if (!fromRecognition) { cancelAutoSend(); if (asr.busy) cancelRecording(restore = false) }
        if (value.text != draft.text) {
            interaction = interaction.inputChanged()
            draftId = UUID.randomUUID().toString()
        }
        val compositionEnded = draft.composition != null && value.composition == null
        draft = value
        if (compositionEnded) history.breakGroup()
        if (!fromRecognition) undoDraft = null
        onDraftChanged(value)
        invalidateAssist()
    }

    fun selectSource(value: SpeakInputSource) {
        if (busy || requestLocked || value == source) return
        stopTranslation(); cancelAutoSend(); source = value; recognition.clearResult()
        if (value.isRecognition) lastVoiceSource = value
        preferences.edit().putString("input_source", value.name).putString("voice_source", lastVoiceSource.name).apply()
        invalidateAssist()
    }

    fun changeSendMode(value: SpeakSendMode) {
        if (busy || requestLocked) return
        cancelAutoSend(); sendMode = value
        preferences.edit().putBoolean("auto_send", value == SpeakSendMode.Automatic).apply()
    }

    fun changeContinuousDictation(value: Boolean) {
        if (busy || requestLocked) return
        cancelAutoSend()
        continuousDictation = value
        preferences.edit().putBoolean("continuous_dictation", value).apply()
    }

    /** Wait time belongs to automatic sending; changing it never enables or disables the default policy. */
    fun changeAutoSendSeconds(value: Int) {
        if (busy || requestLocked) return
        val clamped = value.coerceIn(SpeakInteraction.MIN_DELAY_SECONDS, SpeakInteraction.MAX_DELAY_SECONDS)
        if (clamped == autoSendSeconds) return
        cancelAutoSend(); autoSendSeconds = clamped
        preferences.edit().putInt("auto_send_seconds", clamped).apply()
    }

    /** Per-app rules are a local delivery preference, never a target/authorization boundary. */
    fun setAppSendPolicy(application: String, policy: AppSendPolicy) {
        if (application.isBlank()) return
        cancelAutoSend()
        appSendPolicies = if (policy == AppSendPolicy.Inherit) appSendPolicies - application
        else appSendPolicies + (application to policy)
        val json = JSONObject()
        appSendPolicies.forEach { (name, value) -> json.put(name, value.name) }
        preferences.edit().putString("app_send_policies", json.toString()).apply()
    }

    fun clearAppSendPolicies() {
        if (appSendPolicies.isEmpty()) return
        cancelAutoSend()
        appSendPolicies = emptyMap()
        preferences.edit().remove("app_send_policies").apply()
    }

    /** The current rule only picks auto/manual delivery; scope and target validation still run at send time. */
    fun effectiveSendMode(): SpeakSendMode {
        val application = receiver.foregroundApplication?.name
        return when (application?.let { appSendPolicies[it] } ?: AppSendPolicy.Inherit) {
            AppSendPolicy.Automatic -> SpeakSendMode.Automatic
            AppSendPolicy.Manual -> SpeakSendMode.Manual
            AppSendPolicy.Inherit -> sendMode
        }
    }

    private fun loadAppSendPolicies(): Map<String, AppSendPolicy> {
        val raw = preferences.getString("app_send_policies", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { application ->
                    val policy = runCatching { AppSendPolicy.valueOf(json.getString(application)) }.getOrNull()
                    if (policy == AppSendPolicy.Automatic || policy == AppSendPolicy.Manual) put(application, policy)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun start(cloud: CloudConfiguration?, holdToSpeak: Boolean = false) {
        if (!visible || busy || translationPreparing || requestLocked || draftRestoring || !source.isRecognition || (source == SpeakInputSource.Cloud && cloud == null)) return
        if (source == SpeakInputSource.Local && recognition is SpeechRecognition && !recognitionInstalled) return
        autoStopped = false
        cancelPolish()
        invalidateAssist()
        recordingStartedEmpty = draft.text.isBlank()
        recordingSnapshot = snapshot()
        invalidateTranslation(); cancelAutoSend(); undoDraft = null; submitted = null; explicitFinish = false
        history.breakGroup(); recordingDurationBefore = recordingDurationMillis
        val generation = ++preparationGeneration
        val selection = draft.selection
        originalDraft = draft.copy(composition = null)
        insertion = RecognitionInsertion(draft.text, selection.min, selection.max)
        recordingScope = receiver.receptionScope
        recordingMode = effectiveSendMode()
        recordingDestination = receiver.autoSendDestination
        finishOnSilence = RecordingEndpointPolicy.finishOnSilence(holdToSpeak, continuousDictation)
        pendingCloud = if (source == SpeakInputSource.Cloud) cloud else null
        preparingToRecord = true
        waitingForTranslationStop = true
        scope.launch {
            translator.release()
            if (generation != preparationGeneration) return@launch
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
        expectedRecognitionSession = recognition.state.value.session
        pendingCloud = null
    }

    fun microphoneServiceFailed() { preparingToRecord = false; acceptRecognition = false; asr = asr.copy(issue = RecognitionIssue.Microphone) }
    fun finishRecording(andSend: Boolean = false, auto: Boolean = false) {
        if (asr.phase != RecognitionPhase.Listening) return
        autoStopped = auto
        invalidateAssist()
        sendAfterFinal = andSend && recordingMode == SpeakSendMode.Manual && recordingCanFinish && visible &&
            recordingScope != null && recordingScope == receiver.receptionScope
        explicitFinish = true
        if (!visible) recordingDestination = null
        recognition.finish()
    }

    fun cancelRecording(restore: Boolean = true) {
        invalidateAssist()
        autoStopped = false
        acceptRecognition = false; explicitFinish = false; preparingToRecord = false; pendingCloud = null
        preparationGeneration++; waitingForTranslationStop = false
        cancelAutoSend(); recognition.cancel()
        if (restore) recordingSnapshot?.let { restoreSnapshot(it) }
        recordingSnapshot = null; insertion = null; originalDraft = null
    }

    fun clearDraft() {
        if (busy || requestLocked || draftRestoring) return
        cancelPolish()
        val old = draft
        history.breakGroup()
        changeDraft(TextFieldValue()); recordingDurationMillis = 0L
        history.breakGroup(); undoDraft = old.takeIf { it.text.isNotEmpty() }; submitted = null
        voiceOrigin = false; typedSinceVoice = false
        polishState = PolishState()
        invalidateAssist()
    }
    fun undoClear() { if (undoDraft != null) undo() }
    fun undo() {
        if (canUndo) history.undo(snapshot())?.let {
            restoreSnapshot(it); historyRevision++
            polishState = polishState.copy(confirmationPending = false)
        }
    }
    fun redo() {
        if (canRedo) history.redo(snapshot())?.let {
            restoreSnapshot(it); historyRevision++
            polishState = polishState.copy(confirmationPending = false)
        }
    }
    fun startPolish(mode: PolishMode, targetLanguage: String? = null,
                    translateStyle: PolishTranslateStyle? = null) {
        if (!canPolish) return
        val config = polish.config ?: run {
            polishState = PolishState(outcome = PolishOutcome.Failure(PolishFailure.MissingConfig))
            return
        }
        val language = if (mode == PolishMode.Translate) targetLanguage ?: polish.targetLanguage else null
        if (mode == PolishMode.Translate && PolishPrompts.validateTargetLanguage(language.orEmpty()) != null) {
            polishState = PolishState(outcome = PolishOutcome.Failure(PolishFailure.InvalidResponse))
            return
        }
        val selection = draft.selection
        val selected = selection.min != selection.max
        val start = if (selected) selection.min else 0
        val end = if (selected) selection.max else draft.text.length
        val sourceText = draft.text.substring(start, end)
        if (sourceText.isBlank()) return
        val appName = receiver.foregroundApplication?.name
        val scene = polish.sceneFor(appName)
        val source = when {
            selected -> PolishSource.Selection
            voiceOrigin && !typedSinceVoice -> PolishSource.LocalAsr
            voiceOrigin && typedSinceVoice -> PolishSource.Mixed
            else -> PolishSource.Ime
        }
        val custom = polish.customRewrite.takeIf { mode == PolishMode.Custom }
        val terms = polish.hotwords.takeIf { mode == PolishMode.Translate }
        val userStyle = polish.customRewrite.takeIf { mode != PolishMode.Custom }
        val style = if (mode == PolishMode.Translate) {
            translateStyle ?: PolishPrompts.autoTranslateStyle(appName)
        } else null
        val system = PolishPrompts.system(mode, language, appName, custom, scene, polish.hotwords,
            source = source, style = style, terms = terms, userStyle = userStyle)
        val ticket = PolishTicket(
            id = UUID.randomUUID().toString(), draftId = draftId, version = interaction.version,
            start = start, end = end, sourceText = sourceText, mode = mode, targetLanguage = language,
            configId = config.id, configRevision = config.revision,
            configFingerprint = config.fingerprint, promptHash = PolishPrompts.hash(system),
            source = source, style = style?.id, promptVersion = PolishPrompts.PROMPT_VERSION,
            guardRevision = PolishPrompts.GUARD_REVISION,
        )
        polishRequest?.cancel()
        val request = PolishRequest()
        polishRequest = request
        polishState = PolishState(ticket = ticket, running = true, confirmationPending = polishState.confirmationPending)
        val useStreaming = polish.streamingPreview
        val hotwords = polish.hotwords
        scope.launch {
            val accounting = runCatching {
                polish.beginCall(config, if (mode == PolishMode.Translate) PolishCallPurpose.Translation
                else PolishCallPurpose.Polish)
            }.getOrNull()
            val result = if (accounting == null) {
                PolishCallResult.Failure(PolishFailure.Unknown, requestSent = false)
            } else withContext(NonCancellable + Dispatchers.IO) {
                if (request.cancelled) return@withContext PolishCallResult.Failure(PolishFailure.Unknown, requestSent = false)
                val client = PolishClient(config)
                if (useStreaming) {
                    client.callStreaming(
                        mode = mode, targetLanguage = language, appName = appName, scene = scene,
                        customRewrite = custom, text = sourceText, hotwords = hotwords,
                        source = source, style = style, terms = terms, userStyle = userStyle,
                        onCall = request::attach,
                        onPartial = { partial ->
                            scope.launch {
                                if (polishState.running && polishState.ticket?.id == ticket.id) {
                                    polishState = polishState.copy(preview = partial)
                                }
                            }
                        },
                    )
                } else {
                    client.call(
                        mode = mode, targetLanguage = language, appName = appName, scene = scene,
                        customRewrite = custom, text = sourceText, hotwords = hotwords,
                        source = source, style = style, terms = terms, userStyle = userStyle,
                        onCall = request::attach,
                    )
                }
            }
            withContext(NonCancellable) {
                if (accounting != null) runCatching { polish.finishCall(accounting, result, request.cancelled) }
            }
            if (polishRequest !== request || !isActive || polishState.ticket?.id != ticket.id || !polishState.running) return@launch
            polishRequest = null
            when (result) {
                is PolishCallResult.Success -> {
                    if (draftId != ticket.draftId || interaction.version != ticket.version ||
                        polish.config?.id != ticket.configId ||
                        polish.config?.revision != ticket.configRevision ||
                        polish.config?.fingerprint != ticket.configFingerprint ||
                        draft.text.substring(ticket.start, ticket.end) != ticket.sourceText) {
                        polishState = PolishState(ticket = ticket, outcome = PolishOutcome.Cancelled)
                        return@launch
                    }
                    val next = if (ticket.start == ticket.end) result.text
                    else draft.text.replaceRange(ticket.start, ticket.end, result.text)
                    val original = draft.text
                    if (next == original) {
                        polishState = PolishState(ticket = ticket, outcome = PolishOutcome.Success(mode, result.durationMillis),
                            originalText = original, resultText = next)
                        return@launch
                    }
                    val caret = if (ticket.start == ticket.end) result.text.length else ticket.start + result.text.length
                    history.breakGroup()
                    changeDraft(TextFieldValue(next, TextRange(caret)))
                    history.breakGroup()
                    polishState = PolishState(
                        ticket = ticket, running = false,
                        outcome = PolishOutcome.Success(mode, result.durationMillis),
                        confirmationPending = true, originalText = original, resultText = next,
                    )
                }
                is PolishCallResult.Failure -> {
                    polishState = PolishState(
                        ticket = ticket, running = false,
                        outcome = PolishOutcome.Failure(result.kind),
                        confirmationPending = polishState.confirmationPending,
                    )
                }
            }
        }
    }

    fun cancelPolish() {
        if (!polishState.running) return
        polishRequest?.cancel()
        polishRequest = null
        polishState = PolishState(ticket = polishState.ticket, outcome = PolishOutcome.Cancelled,
            confirmationPending = polishState.confirmationPending)
    }

    private fun snapshot() = SpeakingSnapshot(draft.copy(composition = null),
        translation.takeIf { it.complete }?.let { it.copy(value = it.value.copy(composition = null)) } ?: DraftTranslation(), translationShown && translation.complete, recordingDurationMillis)
    private fun record(value: SpeakingSnapshot, kind: String? = null, composing: Boolean = false) {
        history.record(value, kind, SystemClock.elapsedRealtime(), composing); historyRevision++
    }
    private fun restoreSnapshot(value: SpeakingSnapshot) {
        cancelAutoSend(); invalidateTranslation(); undoDraft = null
        interaction = interaction.inputChanged(); draftId = UUID.randomUUID().toString()
        polishState = polishState.copy(confirmationPending = false)
        voiceOrigin = false; typedSinceVoice = value.draft.text.isNotBlank()
        draft = value.draft; recordingDurationMillis = value.duration
        translation = value.translation.let { old -> old.copy(ticket = old.ticket?.copy(
            id = UUID.randomUUID().toString(), draftId = draftId, version = interaction.version)) }
        translationShown = value.translationShown
        onDraftChanged(draft)
        invalidateAssist()
    }
    fun edit() { cancelAutoSend(); interaction = interaction.edit() }
    private fun cancelCountdown() {
        imeDestination = null
        countdownJob?.cancel()
        countdownJob = null
        interaction = interaction.cancelCountdown()
    }

    fun cancelAutoSend() {
        sendAfterFinal = false
        recordingDestination = null
        cancelCountdown()
    }

    fun requestAutoSend() {
        cancelAutoSend()
        if (!visible || busy || requestLocked || draftRestoring || draftStorageIssue || !canSendRepresentation ||
            polishState.confirmationPending || effectiveSendMode() != SpeakSendMode.Automatic) return
        val destination = receiver.autoSendDestination
        if (displayedDraft.composition != null && destination != null) imeDestination = destination
        else completeInput(SpeakSendMode.Automatic, destination)
    }

    fun cancelImeSend() {
        imeDestination = null
    }

    fun completeInput(
        mode: SpeakSendMode = effectiveSendMode(),
        destination: AutoSendDestination? = receiver.autoSendDestination,
        fromRecording: Boolean = false,
    ) {
        cancelAutoSend()
        if (!visible || busy || requestLocked || draftRestoring || draftStorageIssue || !canSendRepresentation) return
        if (fromRecording && translationShown) return
        // An AI-rewritten draft may be sent by an explicit user action, but must never
        // re-enter the automatic countdown without that action.
        val effectiveMode = if (mode == SpeakSendMode.Automatic && polishState.confirmationPending) SpeakSendMode.Manual else mode
        val value = displayedDraft
        val currentDestination = destination?.takeIf { it == receiver.autoSendDestination }
        interaction = interaction.finish(value.text, value.composition != null, effectiveMode, currentDestination,
            SystemClock.elapsedRealtime(), autoSendSeconds * 1000L)
        invalidateAssist()
        val ticket = interaction.countdown ?: return
        countdownJob = scope.launch {
            while (visible && interaction.countdown == ticket) {
                val now = SystemClock.elapsedRealtime()
                val current = displayedDraft
                if (!ticket.matches(current.text, interaction.version, current.composition != null, receiver.autoSendDestination)) {
                    cancelCountdown()
                    return@launch
                }
                secondsRemaining = ticket.secondsRemaining(now)
                val consumed = interaction.consumeDue(current.text, current.composition != null, receiver.autoSendDestination, now)
                if (consumed != null) {
                    interaction = consumed
                    countdownJob = null
                    onSendReady(ticket.destination)
                    return@launch
                }
                delay(minOf(100, ticket.deadlineMillis - now))
            }
        }
    }

    fun markSubmitted(): Boolean {
        val value = displayedDraft
        if (busy || requestLocked || draftRestoring || draftStorageIssue || !canSendRepresentation || !receiver.ready || value.text.isBlank() || value.composition != null ||
            runCatching { seeyuer.sayin.data.normalizedText(value.text) }.isFailure) return false
        polishState = polishState.copy(confirmationPending = false)
        if (submitted != null) {
            draftId = UUID.randomUUID().toString()
            translation.ticket?.let { translation = translation.copy(ticket = it.copy(draftId = draftId)) }
        }
        cancelAutoSend(); submitted = SubmittedDraft(draftId, value, translated = sendingTranslation); invalidateAssist(); return true
    }

    fun toggleTranslation() {
        if (!translation.running && !canTranslateDraft) return
        when {
            translation.running -> stopTranslation()
            translation.complete && translation.ticket?.matches(draftId, interaction.version) == true -> {
                cancelAutoSend(); translationShown = !translationShown; invalidateAssist()
            }
            else -> translateDraft()
        }
    }

    fun changeDisplayedDraft(value: TextFieldValue) {
        val committedDestination = imeDestination.takeIf { displayedDraft.composition != null && value.composition == null }
        if (!translationShown) {
            changeDraft(value)
            committedDestination?.let { completeInput(SpeakSendMode.Automatic, it) }
            return
        }
        if (!translation.complete || requestLocked || draftRestoring) return
        if (value.text != translation.value.text) record(snapshot(),
            if (value.composition != null || translation.value.composition != null) "translated-composition" else "translated-edit",
            composing = translation.value.composition != null)
        val compositionEnded = translation.value.composition != null && value.composition == null
        cancelAutoSend(); translation = translation.copy(value = value)
        if (compositionEnded) history.breakGroup()
        committedDestination?.let { completeInput(SpeakSendMode.Automatic, it) }
    }

    fun translateDraft() {
        if (!canTranslateDraft) return
        cancelAutoSend()
        val before = snapshot()
        val ticket = TranslationTicket(UUID.randomUUID().toString(), draftId, interaction.version, draft.text,
            currentTranslationModel.sha256, currentTranslationModel.templateRevision)
        translation = DraftTranslation(ticket = ticket, running = true)
        translationShown = false
        translationJob = scope.launch {
            val updates = MutableStateFlow<TranslationProgress?>(null)
            val observer = launch {
                updates.collect { update ->
                    if (translation.running && translation.accepts(ticket.id, draftId, interaction.version)) {
                        translation = translation.copy(progress = update)
                    }
                }
            }
            try {
                recognition.releaseLocal()
                val bundled = if (translationStore.prepared) null else translationStore.openBundled()
                val result = when {
                    bundled != null && translator is BundledTranslator -> translator.translate(bundled, currentTranslationModel, ticket.source,
                        onProgress = { progress -> updates.value = progress })
                    else -> {
                        bundled?.close()
                        translator.translate(translationStore.file, currentTranslationModel, ticket.source,
                            onProgress = { progress -> updates.value = progress })
                    }
                }
                if (translation.running && translation.accepts(ticket.id, draftId, interaction.version)) {
                    translation = translation.copy(running = false, end = result.end,
                        value = TextFieldValue(if (result.end == TranslationEnd.Complete) result.text else ""))
                    translationShown = translation.complete
                    if (translation.complete) record(before)
                    if (result.end == TranslationEnd.ModelMissing || result.end == TranslationEnd.Integrity) translationModelReady = false
                }
            } catch (cancelled: CancellationException) {
                if (translation.running && translation.ticket?.id == ticket.id) {
                    translation = DraftTranslation(end = TranslationEnd.Cancelled); translationShown = false
                }
                throw cancelled
            } catch (_: Exception) {
                if (translation.running && translation.accepts(ticket.id, draftId, interaction.version)) {
                    translation = DraftTranslation(end = TranslationEnd.Error); translationShown = false
                }
            } finally { observer.cancel(); if (translation.ticket?.id == ticket.id) translation = translation.copy(running = false) }
        }
    }

    fun stopTranslation() {
        if (!translation.running && translationJob?.isActive != true) return
        translator.cancel(); translationJob?.cancel()
        translation = DraftTranslation(end = TranslationEnd.Cancelled)
        translationShown = false
        translationStopping = true
        scope.launch { translator.release(); translationStopping = false }
    }

    private fun invalidateTranslation() {
        stopTranslation()
        translation = DraftTranslation(); translationShown = false
        invalidateAssist()
    }

    fun releaseTranslation() { stopTranslation(); scope.launch { translator.release() } }

    fun uiLanguageChanged(tags: String) {
        if (tags == uiLanguage) return
        uiLanguage = tags; cancelAutoSend(); releaseTranslation()
    }

    fun selectTranslationModel(id: String): Boolean {
        if (!canChangeModels) return false
        if (id == currentTranslationModel.id) return true
        if (!setTranslationModel(id)) return false
        changingModel = true
        scope.launch {
            try { translator.release() } finally { changingModel = false }
        }
        return true
    }

    private fun setTranslationModel(id: String): Boolean {
        val model = catalog.models.firstOrNull { it.id == id } ?: return false
        cancelAutoSend()
        currentTranslationModel = model
        translationStore = createTranslationStore(model)
        translationModelReady = translationStore.ready
        preferences.edit().putString("translation_model", id).apply()
        return true
    }

    fun selectRecognitionModel(model: LocalModel): Boolean {
        if (!canChangeModels || model.id !in localModels.installed) return false
        if (model == currentRecognitionModel) return true
        cancelAutoSend()
        changingModel = true
        scope.launch {
            try {
                recognition.releaseLocal()
                setRecognitionModel(model)
            } finally {
                changingModel = false
            }
        }
        return true
    }

    private fun setRecognitionModel(model: LocalModel) {
        currentRecognitionModel = model
        preferences.edit().putString("recognition_model", model.id).apply()
    }

    fun deleteLocalModel(model: LocalModel) {
        if (!canChangeModels) return
        cancelAutoSend()
        localModels.delete(model) {
            when (model.id) {
                currentRecognitionModel.id -> recognition.releaseLocal()
                currentTranslationModel.id -> translator.release()
            }
        }
    }

    /** The uncertain request stays immutable in the ledger; this does not retry it or release the desktop gate. */
    fun newDraft() {
        if (busy || submitted?.state != "uncertain" || !receiver.ready) return
        submitted = null; changeDraft(TextFieldValue(), fromRecognition = true)
        recordingDurationMillis = 0L
        history.clear(); historyRevision++; undoDraft = null
        voiceOrigin = false; typedSinceVoice = false
        polishState = PolishState()
        invalidateAssist()
    }

    override fun close() {
        cancelAutoSend(); polishRequest?.cancel(); translator.close(); recognition.close(); scope.cancel()
    }
}

internal data class SubmittedDraft(
    val id: String,
    val value: TextFieldValue,
    val state: String = "saved",
    val translated: Boolean = false,
    val reason: String? = null,
)

private data class SpeakingSnapshot(val draft: TextFieldValue, val translation: DraftTranslation, val translationShown: Boolean, val duration: Long)
