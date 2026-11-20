package seeyuer.sayin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import seeyuer.sayin.designsystem.YuePalette
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.PhraseLibrary
import seeyuer.sayin.data.normalizedText
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.ui.assist.AssistDismissReason
import seeyuer.sayin.ui.assist.AssistPanel
import seeyuer.sayin.ui.assist.AssistTab
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeakPage(
    state: ClientState,
    session: SpeakSession,
    phrases: PhraseLibrary,
    onSend: () -> Unit,
    onConnect: () -> Unit,
    cloudConfig: CloudConfiguration? = null,
    onConfigureCloud: () -> Unit = {},
    connectionOpen: Boolean = false,
    quick: Boolean = false,
    onExpand: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onRequestAction: (String, String) -> Unit = { _, _ -> },
    onImage: () -> Unit = {},
    onConfigurePolish: () -> Unit = {},
    pageActive: Boolean = true,
    autoRecordVersion: Int = 0,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val owner = remember { Any() }
    var showPreview by remember { mutableStateOf(false) }
    var showTranslationPreparation by remember { mutableStateOf(false) }
    var showPolish by remember { mutableStateOf(false) }
    var showAssist by remember { mutableStateOf(false) }
    var assistGeneration by remember { mutableIntStateOf(0) }
    var assistTicket by remember { mutableStateOf<AssistInsertion?>(null) }
    var assistTab by remember { mutableStateOf(AssistTab.Clipboard) }
    var assistNotice by remember { mutableStateOf<Int?>(null) }
    var assistWasOpen by remember { mutableStateOf(false) }
    var toolsShown by remember { mutableStateOf(true) }
    var assistEntryFocused by remember { mutableStateOf(false) }
    val assistEntryFocus = remember { FocusRequester() }
    val presentation = remember(context) { context.getSharedPreferences("presentation", 0) }
    var focused by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    var startAfterPermission by remember { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val draft = session.displayedDraft
    val asr = session.asr
    val currentCloud by rememberUpdatedState(cloudConfig)
    val source = session.source
    val normalized = remember(draft.text) { runCatching { normalizedText(draft.text) }.getOrNull() }
    val effectiveSendMode = session.effectiveSendMode()
    val automaticPolicy = effectiveSendMode == SpeakSendMode.Automatic
    val sendable = normalized != null && draft.text.isNotBlank() && (draft.composition == null || automaticPolicy) && !session.requestLocked && !session.draftRestoring &&
        !session.draftStorageIssue && session.canSendRepresentation
    val preparing = session.preparingToRecord || source == SpeakInputSource.Local && session.model in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
    val activeCountdown = session.interaction.countdown
    val countdownProgress by produceState(0f, activeCountdown) {
        val ticket = activeCountdown
        if (ticket == null) {
            value = 0f
            return@produceState
        }
        while (true) {
            val remaining = (ticket.deadlineMillis - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            value = (remaining / ticket.durationMillis.toFloat()).coerceIn(0f, 1f)
            if (remaining <= 0L) break
            kotlinx.coroutines.delay(40)
        }
    }
    val draftLabel = stringResource(R.string.mobile_draft)
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var cancelArmed by remember { mutableStateOf(false) }
    LaunchedEffect(asr.phase, session.recordingStartedAtMillis, session.recordingDurationMillis) {
        if (asr.phase != RecognitionPhase.Listening) recordingSeconds = (session.recordingDurationMillis / 1000).toInt()
        while (asr.phase == RecognitionPhase.Listening) {
            recordingSeconds = ((session.recordingDurationMillis + android.os.SystemClock.elapsedRealtime() - session.recordingStartedAtMillis) / 1000).toInt()
            kotlinx.coroutines.delay(250)
        }
    }
    DisposableEffect(lifecycle, session, connectionOpen, pageActive) {
        fun update() { resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); session.setVisible(owner, resumed && !connectionOpen && pageActive) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); session.setVisible(owner, false) }
    }
    fun start(holdToSpeak: Boolean = false) {
        focusManager.clearFocus(); keyboard?.hide(); showPreview = false
        if (session.source == SpeakInputSource.Cloud && currentCloud == null) onConfigureCloud()
        else session.start(currentCloud, holdToSpeak)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.microphoneDenied = !granted
        permissionGranted = granted
        if (!granted) permissionPending = false
    }
    LaunchedEffect(permissionPending, permissionGranted, resumed, connectionOpen) {
        if (permissionPending && permissionGranted && resumed && !connectionOpen) {
            permissionPending = false
            permissionGranted = false
            if (startAfterPermission) start()
        }
    }
    fun connect() {
        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); onConnect()
    }
    fun savedAssistTab(): AssistTab =
        if (presentation.getString("assist_tab", null) == "phrases") AssistTab.Phrases else AssistTab.Clipboard
    fun openAssist() {
        if (!session.assistEditable) return
        val ticket = session.beginAssistInsertion() ?: return
        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide()
        assistTicket = ticket
        assistTab = savedAssistTab()
        assistGeneration++
        showAssist = true
    }
    fun closeAssist(reason: AssistDismissReason) {
        if (!showAssist) return
        showAssist = false
        assistTicket = null
        session.invalidateAssist()
        assistNotice = when (reason) {
            AssistDismissReason.User -> null
            AssistDismissReason.DraftChanged -> R.string.assist_draft_changed
            AssistDismissReason.NotEditable -> R.string.assist_not_editable
        }
    }
    LaunchedEffect(assistNotice) {
        val current = assistNotice
        if (current != null) { kotlinx.coroutines.delay(4_000); if (assistNotice == current) assistNotice = null }
    }
    LaunchedEffect(showAssist) {
        if (showAssist) { assistWasOpen = true; return@LaunchedEffect }
        if (!assistWasOpen) return@LaunchedEffect
        assistWasOpen = false
        if (!session.assistEditable) return@LaunchedEffect
        // The sheet is torn down in its own overlay window; the Activity regains input focus a few
        // frames later, so retry briefly instead of assuming one frame is enough.
        if (!toolsShown) return@LaunchedEffect
        var attempts = 0
        while (attempts++ < 40) {
            withFrameNanos { }
            if (assistEntryFocused) return@LaunchedEffect
            runCatching { assistEntryFocus.requestFocus() }
            if (assistEntryFocused) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
    LaunchedEffect(connectionOpen, showTranslationPreparation) {
        if (showAssist && (connectionOpen || showTranslationPreparation)) closeAssist(AssistDismissReason.User)
    }
    DisposableEffect(Unit) { onDispose { if (showAssist) session.invalidateAssist() } }
    fun edit() { session.edit(); showPreview = false; focus.requestFocus(); keyboard?.show() }
    fun translate() {
        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); showPreview = false
        if (session.translation.complete || session.translationModelReady) session.toggleTranslation() else showTranslationPreparation = true
    }
    val hint = when {
        session.draftRestoring -> stringResource(R.string.speak_restoring_draft)
        session.modelPreparation != null && !session.busy -> stringResource(
            if (session.modelPreparation!!.stage == seeyuer.sayin.translation.ModelPreparationStage.Verifying) R.string.translation_preparing_verify else R.string.translation_preparing,
            (100 * session.modelPreparation!!.bytes / session.modelPreparation!!.total.coerceAtLeast(1)).toInt())
        session.modelPreparationFailed && !session.busy -> stringResource(R.string.translation_prepare_failed)
        session.translationStopping -> stringResource(R.string.translation_stopping)
        session.translation.running -> {
            val progress = session.translation.progress
            when (progress?.stage) {
                seeyuer.sayin.translation.TranslationStage.Verifying -> stringResource(R.string.translation_verifying, (progress.fraction * 100).toInt())
                seeyuer.sayin.translation.TranslationStage.Loading -> stringResource(R.string.translation_loading, (progress.fraction * 100).toInt())
                else -> stringResource(R.string.translation_generating)
            }
        }
        session.translation.end != null && !session.translation.complete -> stringResource(translationEndText(session.translation.end))
        preparing && source == SpeakInputSource.Cloud -> stringResource(R.string.cloud_connecting)
        preparing -> stringResource(if (session.model == LocalModelPhase.Warming) R.string.speak_model_warming else R.string.speak_model_loading)
        asr.busy -> stringResource(when (asr.phase) {
            RecognitionPhase.Loading -> if (source == SpeakInputSource.Cloud) R.string.cloud_connecting else R.string.asr_loading
            RecognitionPhase.Listening -> if (source == SpeakInputSource.Cloud) R.string.speak_cloud_listening else R.string.speak_local_listening
            RecognitionPhase.Finishing -> R.string.asr_finishing
            else -> R.string.asr_cancelling
        })
        session.microphoneDenied && source.isRecognition -> stringResource(R.string.asr_permission_denied)
        source == SpeakInputSource.Local && session.model == LocalModelPhase.Failed -> stringResource(R.string.asr_model_error)
        asr.issue == RecognitionIssue.TooSlow && source == SpeakInputSource.Cloud -> stringResource(R.string.cloud_too_slow)
        asr.issue != null && source.isRecognition -> stringResource(recognitionIssueText(checkNotNull(asr.issue)))
        asr.finished && asr.transcript.isEmpty() && source.isRecognition -> stringResource(R.string.asr_no_speech)
        draft.text.isNotEmpty() && normalized == null -> stringResource(R.string.mobile_invalid_draft)
        source == SpeakInputSource.Cloud && cloudConfig == null -> stringResource(R.string.cloud_setup_hint)
        source == SpeakInputSource.Cloud -> stringResource(R.string.speak_cloud_idle)
        source == SpeakInputSource.Local && session.model == LocalModelPhase.Ready -> stringResource(R.string.speak_local_ready)
        source == SpeakInputSource.Local -> stringResource(R.string.speak_local_cold)
        else -> stringResource(R.string.speak_keyboard_idle)
    }
    val haptics = LocalHapticFeedback.current
    var previousRecordingPhase by remember { mutableStateOf(asr.phase) }
    LaunchedEffect(asr.phase) {
        if (asr.phase == RecognitionPhase.Listening || previousRecordingPhase == RecognitionPhase.Listening)
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        previousRecordingPhase = asr.phase
    }
    val clipboard = LocalClipboard.current
    val uiScope = rememberCoroutineScope()
    var injectedFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(session.submitted?.id, session.submitted?.state) {
        injectedFeedback = session.submitted?.state == "injected"
        if (injectedFeedback) { kotlinx.coroutines.delay(900); injectedFeedback = false }
    }
    var clearNotice by remember { mutableStateOf(false) }
    LaunchedEffect(session.undoDraft) {
        clearNotice = session.undoDraft != null
        if (clearNotice) { kotlinx.coroutines.delay(6000); clearNotice = false }
    }
    val voiceSource = if (source.isRecognition) source else session.lastVoiceSource
    fun microphone(holdToSpeak: Boolean = false) {
        when {
            session.asr.phase == RecognitionPhase.Listening -> session.finishRecording()
            session.busy -> Unit
            else -> {
                session.cancelAutoSend()
                val engine = if (session.source.isRecognition) session.source else session.lastVoiceSource
                if (!session.source.isRecognition) session.selectSource(engine)
                if (engine == SpeakInputSource.Cloud && currentCloud == null) onConfigureCloud()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    session.microphoneDenied = false; start(holdToSpeak)
                } else {
                    startAfterPermission = !holdToSpeak
                    permissionPending = true
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    var autoRecordConsumed by remember { mutableIntStateOf(0) }
    LaunchedEffect(autoRecordVersion, resumed, connectionOpen, pageActive, permissionPending) {
        if (autoRecordVersion <= 0 || autoRecordConsumed >= autoRecordVersion) return@LaunchedEffect
        if (!resumed || connectionOpen || !pageActive || permissionPending) return@LaunchedEffect
        if (session.busy || session.requestLocked || session.draftRestoring || session.translation.running ||
            session.translationStopping || session.translationPreparing) return@LaunchedEffect
        autoRecordConsumed = autoRecordVersion
        microphone()
    }
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("page-Speak")) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 300.dp * fontScale
        val minimal = maxHeight < 170.dp * fontScale
        val inlineEditingActions = maxHeight < 144.dp * fontScale
        val controlSize = if (minimal) 48.dp else 56.dp
        val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val showHeader = maxHeight >= 260.dp * fontScale
        val showTools = maxHeight >= 240.dp * fontScale
        val toolbarWidth = 48.dp * if (quick) 5 else 6
        val cardMargin = if (showTools) ((maxWidth - toolbarWidth - 28.dp) / 2)
            .coerceIn(8.dp, 20.dp) else 20.dp
        val insetBudget = (maxWidth - toolbarWidth - cardMargin * 2).coerceIn(8.dp, 28.dp)
        val cardStartInset = if (showTools) (insetBudget / 2 + 2.dp).coerceAtMost(insetBudget - 4.dp) else 16.dp
        val cardEndInset = if (showTools) insetBudget - cardStartInset else 12.dp
        SideEffect { toolsShown = showTools }
        val busy = session.busy
        val locked = session.requestLocked || session.draftRestoring
        val toolsEnabled = !busy && !locked && !session.translation.running && !session.translationStopping && draft.composition == null
        val color = MiuixTheme.colorScheme
        Column(Modifier.fillMaxSize()) {
            if (showHeader) SmallTopAppBar(
                title = "",
                navigationIcon = {
                    Text(if (quick) stringResource(R.string.app_name) else stringResource(R.string.nav_speak),
                        style = MiuixTheme.textStyles.title3,
                        fontSize = if (quick) 20.sp else 30.sp,
                        fontWeight = if (quick) FontWeight.SemiBold else FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (!quick) {
                        ConnectionStatusButton(state, ::connect)
                    } else {
                        ConnectionStatusBadge(state)
                        DraftIcon(VoiceLinkIcon.ArrowsDiagonal, R.string.quick_expand, "expand-quick") {
                            session.cancelAutoSend(); onExpand()
                        }
                        DraftIcon(VoiceLinkIcon.Close, R.string.quick_collapse, "collapse-quick") {
                            session.cancelAutoSend(); onDismiss()
                        }
                    }
                },
                color = MiuixTheme.colorScheme.background,
                defaultWindowInsetsPadding = false,
                titlePadding = 8.dp,
                navigationIconPadding = 20.dp,
                actionIconPadding = 20.dp,
            )
            Column(Modifier.weight(1f).fillMaxWidth()
                .padding(start = cardMargin, end = cardMargin,
                    top = if (minimal) 0.dp else if (quick) 0.dp else 16.dp,
                    bottom = if (minimal) 0.dp else if (quick) 16.dp else 16.dp)
                .clip(RoundedCornerShape(28.dp)).background(color.surface)
                .padding(start = cardStartInset, end = cardEndInset,
                    top = if (showTools) 10.dp else 0.dp, bottom = if (minimal) 0.dp else 6.dp)) {
                if (!inlineEditingActions) DraftInformation(state, draft, effectiveSendMode) {
                    if (!showTools) DraftIcon(VoiceLinkIcon.Clipboard, R.string.assist_open, "open-assist-fallback",
                        session.assistEditable && !busy && !locked) { openAssist() }
                    DraftIcon(VoiceLinkIcon.Undo, R.string.speak_undo, "undo-draft", session.canUndo, onClick = session::undo)
                    DraftIcon(VoiceLinkIcon.Redo, R.string.speak_redo, "redo-draft", session.canRedo, onClick = session::redo)
                }
                if (!inlineEditingActions && !minimal) Spacer(Modifier.height(6.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPreview && normalized != null) Text(normalized,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("draft-preview"), fontSize = 22.sp, lineHeight = 35.sp)
                    else BasicTextField(draft, session::changeDisplayedDraft,
                        Modifier.fillMaxSize().focusRequester(focus).onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) session.edit() else session.cancelImeSend()
                        }.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Z) {
                                if (event.isShiftPressed) session.redo() else session.undo(); true
                            } else false
                        }.testTag("draft").semantics { contentDescription = draftLabel },
                        readOnly = busy || locked || session.translationShown && !session.translation.complete,
                        cursorBrush = SolidColor(color.primary),
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = if (quick) 18.sp else 22.sp,
                            lineHeight = if (quick) 28.sp else 35.sp, color = color.onSurface),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize()) {
                                if (draft.text.isEmpty() && !focused) Box(
                                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.speak_draft_placeholder),
                                        Modifier.testTag("draft-placeholder"),
                                        fontSize = 13.sp, lineHeight = 19.sp,
                                        color = color.onSurfaceSecondary.copy(alpha = .85f),
                                        textAlign = TextAlign.Center, maxLines = 2)
                                }
                                inner()
                            }
                        })
                }
                val exceptional = session.draftRestoring || session.draftStorageIssue || session.microphoneDenied || asr.issue != null ||
                    preparing || session.model == LocalModelPhase.Failed || session.translation.running || session.translationStopping || session.translationPreparing ||
                    session.modelPreparationFailed || session.translation.end != null && !session.translation.complete || draft.text.isNotEmpty() && normalized == null
                if (exceptional) Text(if (session.draftStorageIssue) stringResource(R.string.speak_draft_storage_issue) else hint,
                    Modifier.fillMaxWidth().testTag("draft-hint").semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp,
                    color = color.onSurfaceSecondary)
                if (!compact) PolishStatus(session)
                if (session.microphoneDenied && !compact) TextButton(stringResource(R.string.asr_open_settings), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, minHeight = 48.dp, colors = plainTextButtonColors())
                session.submitted?.takeIf { it.state != "injected" }?.let { request ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(receiptText(request.state)), Modifier.weight(1f).testTag("current-receipt"), fontSize = 12.sp)
                        if (request.state in setOf("saved", "accepted", "held", "executing", "review")) TextButton(
                            stringResource(R.string.speak_query), { onRequestAction(request.id, "query") }, minHeight = 48.dp,
                            enabled = state.connection == Connection.ONLINE, colors = plainTextButtonColors())
                        if (request.state == "held" && state.ready) TextButton(stringResource(R.string.release_request),
                            { onRequestAction(request.id, "release") }, minHeight = 48.dp, colors = plainTextButtonColors())
                        if (request.state == "uncertain" && state.ready) TextButton(stringResource(R.string.speak_new_draft),
                            session::newDraft, minHeight = 48.dp, colors = plainTextButtonColors())
                    }
                    if (request.state == "review") TextButton(stringResource(R.string.speak_keep_draft), {
                        onRequestAction(request.id, "discard")
                    }, modifier = Modifier.testTag("keep-draft"), minHeight = 48.dp, colors = plainTextButtonColors())
                    if (request.state == "uncertain") Text(stringResource(R.string.uncertain_warning), fontSize = 12.sp, color = color.onSurfaceSecondary)
                    if (request.reason == "automatic_target_changed") Text(
                        stringResource(R.string.receipt_automatic_target_changed), fontSize = 12.sp, color = color.onSurfaceSecondary,
                    )
                }
                if (showTools) {
                    FlowRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        DraftIcon(VoiceLinkIcon.Clipboard, R.string.assist_open, "open-assist",
                            session.assistEditable && !busy && !locked,
                            modifier = Modifier.focusRequester(assistEntryFocus)
                                .onFocusChanged { assistEntryFocused = it.isFocused }.focusable()) {
                            openAssist()
                        }
                        DraftIcon(VoiceLinkIcon.Photo, R.string.image_choose, "choose-image",
                            state.paired && state.storageHealthy && !busy && !locked) {
                            session.cancelAutoSend(); onImage()
                        }
                        DraftIcon(VoiceLinkIcon.Translate,
                            when {
                                session.translation.running || session.translationPreparing -> R.string.translation_cancel
                                session.translationShown -> R.string.translation_show_original
                                session.translation.complete -> R.string.translation_show_cached
                                else -> R.string.translation_action
                            }, "translate-draft",
                            session.canTranslateDraft || session.translation.running || session.translationPreparing,
                            progress = session.translation.running || session.translationPreparing, selected = session.translationShown) {
                            when { session.translation.running -> session.stopTranslation(); session.translationPreparing -> session.cancelModelPreparation(); else -> translate() }
                        }
                        DraftIcon(VoiceLinkIcon.Ai, R.string.polish_title, "polish-draft",
                            session.canOpenPolish, progress = session.polishState.running) {
                            session.cancelAutoSend(); showPolish = true
                        }
                        if (!quick) DraftIcon(VoiceLinkIcon.Copy, R.string.speak_copy_draft, "copy-draft", draft.text.isNotEmpty() && !session.translation.running && !session.translationStopping) {
                            session.cancelAutoSend(); uiScope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", draft.text))) }
                        }
                        DraftIcon(VoiceLinkIcon.Clear, R.string.speak_clear_draft, "clear-draft", toolsEnabled && draft.text.isNotEmpty()) { session.clearDraft() }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = if (minimal) 0.dp else 10.dp).height(controlSize), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (inlineEditingActions) 0.dp else 10.dp)) {
                if (inlineEditingActions) {
                    if (!showTools) DraftIcon(VoiceLinkIcon.Clipboard, R.string.assist_open, "open-assist-fallback",
                        session.assistEditable && !busy && !locked) { openAssist() }
                    DraftIcon(VoiceLinkIcon.Undo, R.string.speak_undo, "undo-draft", session.canUndo, onClick = session::undo)
                    DraftIcon(VoiceLinkIcon.Redo, R.string.speak_redo, "redo-draft", session.canRedo, onClick = session::redo)
                }
                Button({
                    if (busy) session.cancelRecording() else if (focused) { focusManager.clearFocus(); keyboard?.hide() } else edit()
                }, Modifier.size(controlSize).testTag(if (busy) "cancel-recording" else "open-keyboard"), enabled = !locked &&
                        (busy || !session.translation.running && !session.translationStopping && !session.translationPreparing),
                    cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(color = if (color.background == YuePalette.Neutral100) YuePalette.Neutral200 else YuePalette.Neutral850,
                        contentColor = color.onSurfaceSecondary)) {
                    Icon((if (busy) VoiceLinkIcon.Close else if (focused) VoiceLinkIcon.Microphone else VoiceLinkIcon.Input).vector,
                        stringResource(if (busy) R.string.speak_abort else if (focused) R.string.speak_finish_input else R.string.mobile_open_keyboard), Modifier.size(22.dp))
                }
                val empty = draft.text.isEmpty() && !busy && !session.requestLocked && !injectedFeedback
                val listening = asr.phase == RecognitionPhase.Listening
                val pending = session.submitted?.state in setOf("saved", "accepted", "held", "executing", "review")
                val waitingForReceipt = session.submitted?.state in setOf("saved", "accepted", "executing")
                val uncertain = session.submitted?.state == "uncertain"
                var slowFinal by remember { mutableStateOf(false) }
                LaunchedEffect(asr.phase) {
                    slowFinal = false
                    if (asr.phase == RecognitionPhase.Finishing) { kotlinx.coroutines.delay(450); slowFinal = true }
                }
                Row(Modifier.weight(1f).height(controlSize).clip(RoundedCornerShape(28.dp))
                    .background(if (cancelArmed) YuePalette.Neutral700 else color.primary).padding(if (minimal) 0.dp else 4.dp).testTag("voice-bar"),
                    verticalAlignment = Alignment.CenterVertically) {
                    val micLabel = when { preparing || asr.phase == RecognitionPhase.Loading -> R.string.speak_preparing
                        listening -> R.string.asr_finish; else -> R.string.speak_start_short }
                    val micDescription = stringResource(micLabel)
                    val tag = if (voiceSource == SpeakInputSource.Cloud) "start-cloud-asr" else "start-local-asr"
                    val micEnabled = !locked && !permissionPending && !session.translation.running && !session.translationStopping &&
                        (!busy || listening)
                    val callbacks = rememberUpdatedState(Triple<(Boolean) -> Unit, () -> Unit, () -> Unit>(::microphone,
                        { session.finishRecording() }, { session.cancelRecording() }))
                    val armedCallback = rememberUpdatedState<(Boolean) -> Unit>({ cancelArmed = it })
                    val enabledCallback = rememberUpdatedState(micEnabled)
                    val gesture = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!enabledCallback.value) return@awaitEachGesture
                            down.consume()
                            var held = false
                            var armed = false
                            try {
                                val released = withTimeoutOrNull(300) { waitForUpOrCancellation() != null }
                                if (released == true) callbacks.value.first(false)
                                else if (released == null && currentEvent.changes.any { it.id == down.id && it.pressed }) {
                                    held = true
                                    if (!session.busy) callbacks.value.first(true)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        armed = change.position.y - down.position.y < -56.dp.toPx()
                                        armedCallback.value(armed)
                                        if (change.isConsumed) { callbacks.value.third(); held = false; break }
                                        change.consume()
                                        if (!change.pressed) {
                                            if (armed || session.asr.phase != RecognitionPhase.Listening) callbacks.value.third() else callbacks.value.second()
                                            held = false; break
                                        }
                                    }
                                }
                            } finally {
                                if (held) callbacks.value.third()
                                armedCallback.value(false)
                            }
                        }
                    }
                    Box(Modifier.then(if (empty) Modifier.weight(1f) else Modifier.width(48.dp)).height(48.dp).clip(RoundedCornerShape(24.dp))
                        .then(gesture).testTag(tag).semantics {
                            role = Role.Button; contentDescription = micDescription
                            if (!micEnabled) disabled() else onClick { microphone(); true }
                        }, contentAlignment = Alignment.Center) {
                        if (preparing || asr.phase == RecognitionPhase.Loading) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp, colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color.onPrimary, backgroundColor = Color.Transparent))
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Crossfade(if (listening) VoiceLinkIcon.Stop else VoiceLinkIcon.Microphone, animationSpec = tween(160)) { icon ->
                                Icon(icon.vector, null, Modifier.size(24.dp), tint = color.onPrimary.copy(alpha = if (micEnabled) 1f else .4f))
                            }
                            if (empty) Text(stringResource(R.string.speak_start_short), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color.onPrimary)
                        }
                    }
                    if (!empty) Column(Modifier.weight(1f).height(44.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        when {
                            activeCountdown != null -> Unit
                            session.waitingForImeCommit -> Text(stringResource(R.string.speak_confirm_ime),
                                color = color.onPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            asr.phase == RecognitionPhase.Finishing -> Text(stringResource(R.string.asr_finishing),
                                color = color.onPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            session.interaction.review == DraftReview.TargetUnavailable -> Text(stringResource(
                                if (state.ready && !state.autoTextSupported) R.string.speak_auto_update_desktop else R.string.speak_auto_target_unavailable),
                                color = color.onPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            session.interaction.review == DraftReview.TargetChanged -> Text(stringResource(R.string.receipt_automatic_target_changed),
                                color = color.onPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            automaticPolicy && state.ready && sendable && !busy -> Text(
                                stringResource(R.string.speak_send_after_delay, session.autoSendSeconds),
                                color = color.onPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            cancelArmed -> Text(stringResource(R.string.speak_release_cancel), color = color.onPrimary, fontSize = 14.sp, maxLines = 1)
                            preparing || asr.phase == RecognitionPhase.Loading -> Text(stringResource(R.string.speak_preparing), color = color.onPrimary, fontSize = 14.sp, maxLines = 1)
                            else -> {
                                VoiceWaveform(asr.audioLevel, listening, color.onPrimary)
                                Text(if (uncertain) stringResource(R.string.speak_review_short) else buildString {
                                    if (listening) {
                                        append(stringResource(R.string.speak_listening_short))
                                        append(" · ")
                                    }
                                    if (recordingSeconds > 0 || listening) append("%02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60))
                                    if (!listening) {
                                        if (isNotEmpty()) append(" · ")
                                        append(stringResource(R.string.speak_count_short, draft.text.codePointCount(0, draft.text.length)))
                                    }
                                }, fontSize = if (listening) 12.sp else 14.sp, color = color.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (!empty) {
                        val allowSend = if (listening) session.recordingHasText && state.ready else sendable && !busy
                        val sendLabel = when {
                            uncertain -> stringResource(R.string.speak_review_short)
                            listening && session.recordingUsesAutoSend -> stringResource(R.string.speak_finish_auto_send)
                            listening -> stringResource(R.string.speak_finish_send)
                            automaticPolicy && state.ready -> stringResource(R.string.speak_send_after_delay, session.autoSendSeconds)
                            else -> stringResource(R.string.speak_send_to_computer)
                        }
                        val sendDescription = when {
                            pending -> stringResource(receiptText(checkNotNull(session.submitted).state))
                            slowFinal -> stringResource(R.string.asr_finishing)
                            injectedFeedback -> stringResource(R.string.receipt_injected)
                            else -> sendLabel
                        }
                        val cancelDescription = stringResource(R.string.speak_cancel_send)
                        Box(Modifier.size(48.dp)) {
                            IconButton({
                                when {
                                    activeCountdown != null -> session.cancelAutoSend()
                                    session.waitingForImeCommit -> session.cancelImeSend()
                                    uncertain -> connect()
                                    listening -> session.finishRecording(andSend = !session.recordingUsesAutoSend)
                                    !state.ready -> connect()
                                    automaticPolicy -> session.requestAutoSend()
                                    else -> onSend()
                                }
                            }, Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                                .background(if (allowSend || pending || activeCountdown != null) color.onPrimary.copy(alpha = .12f) else Color.Transparent)
                                .testTag(if (activeCountdown != null || session.waitingForImeCommit) "cancel-auto-send" else "send-draft")
                                .alpha(if (activeCountdown != null) 1f else if (preparing || asr.phase == RecognitionPhase.Loading || listening && !session.recordingHasText) 0f else 1f)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = if (activeCountdown != null || session.waitingForImeCommit) cancelDescription else sendDescription
                                    if (activeCountdown == null && (cancelArmed || !(allowSend || uncertain))) disabled()
                                },
                                enabled = if (activeCountdown != null) true else !cancelArmed && (allowSend || uncertain)) {
                                if (activeCountdown != null) {
                                    Text(stringResource(R.string.speak_countdown_number, session.secondsRemaining),
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color.onPrimary)
                                } else if (session.waitingForImeCommit) Icon(VoiceLinkIcon.Close.vector, null, Modifier.size(20.dp), tint = color.onPrimary)
                                else if (waitingForReceipt || slowFinal) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp, colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color.onPrimary, backgroundColor = Color.Transparent))
                                else Crossfade(when { uncertain -> VoiceLinkIcon.Alert; injectedFeedback -> VoiceLinkIcon.Check
                                    pending -> VoiceLinkIcon.Alert
                                    session.submitted?.state == "failed" -> VoiceLinkIcon.Retry; else -> VoiceLinkIcon.ArrowUp }, animationSpec = tween(160)) { icon ->
                                    Icon(icon.vector, null, Modifier.size(24.dp),
                                        tint = color.onPrimary.copy(alpha = if (allowSend || uncertain || injectedFeedback) 1f else .3f))
                                }
                            }
                            if (activeCountdown != null) CountdownRing(countdownProgress, Modifier.matchParentSize().padding(1.dp), alpha = 1f)
                        }
                    }
                }
            }
        }
        if (clearNotice) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
            .background(color.onSurface, RoundedCornerShape(28.dp)).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.speak_cleared), fontSize = 13.sp, color = color.surface)
            TextButton(stringResource(R.string.speak_undo), { session.undoClear() }, Modifier.testTag("undo-clear"),
                minHeight = 48.dp, colors = plainTextButtonColors())
        }
        assistNotice?.let { message ->
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp).padding(horizontal = 16.dp)
                .background(color.onSurface, RoundedCornerShape(28.dp)).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }.testTag("assist-dismiss-notice")) {
                Text(stringResource(message), fontSize = 13.sp, color = color.surface)
            }
        }
    }
    TranslationPreparationSheet(session, showTranslationPreparation) { showTranslationPreparation = false }
    PolishSheet(session, showPolish, { showPolish = false }, onConfigurePolish)
    AssistPanel(
        show = showAssist,
        openGeneration = assistGeneration,
        session = session,
        phrases = phrases,
        ticket = assistTicket,
        initialTab = assistTab,
        onTicket = { assistTicket = it },
        onTab = { tab ->
            assistTab = tab
            presentation.edit().putString("assist_tab", if (tab == AssistTab.Phrases) "phrases" else "clipboard").apply()
        },
        onDismiss = ::closeAssist,
    )

}

@Composable
private fun CountdownRing(progress: Float, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val color = MiuixTheme.colorScheme.onPrimary
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = color.copy(alpha = .25f * alpha),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = -90f,
            sweepAngle = (360f * progress).coerceIn(0f, 360f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun DraftInformation(state: ClientState, draft: androidx.compose.ui.text.input.TextFieldValue,
                             sendMode: SpeakSendMode,
                             actions: @Composable RowScope.() -> Unit) {
    val color = MiuixTheme.colorScheme
    val connected = state.connection == Connection.ONLINE && !state.paused && state.active
    // The card header identifies the computer with the device-desktop icon; the user-visible text
    // is only the current application name, while accessibility keeps the full sentence.
    val application = when {
        state.connection != Connection.ONLINE -> stringResource(R.string.speak_computer_offline)
        state.paused -> stringResource(R.string.speak_computer_paused)
        !state.active || state.foregroundApplication == null -> stringResource(R.string.speak_computer_unknown)
        else -> stringResource(R.string.speak_computer_current, state.foregroundApplication.name)
    }
    val visibleName = when {
        state.connection != Connection.ONLINE -> stringResource(R.string.speak_computer_offline)
        state.paused -> stringResource(R.string.speak_computer_paused)
        !state.active || state.foregroundApplication == null -> stringResource(R.string.speak_computer_unknown)
        else -> state.foregroundApplication.name
    }
    val generic = if (connected) stringResource(R.string.speak_computer_connected) else application
    val count = draftCharacterCount(draft)
    // The voice bar already shows the draft length next to Send. The header only adds a number when
    // it answers a different question: how much is selected, or how close we are to the limit.
    val countWorthShowing = !draft.selection.collapsed || count >= 3600
    val countText = when {
        !draft.selection.collapsed -> pluralStringResource(R.plurals.speak_selected_count, count, count)
        count >= 3600 -> stringResource(R.string.speak_limit_count, count, 4000)
        else -> pluralStringResource(R.plurals.speak_character_count, count, count)
    }
    val description = stringResource(R.string.speak_computer_description, application)
    val density = LocalDensity.current
    val textStyle = MiuixTheme.textStyles.main.copy(fontSize = 12.sp)
    val measurer = rememberTextMeasurer()
    val countWidth = with(density) { measurer.measure(countText, textStyle).size.width.toDp() } + 12.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val applicationIcon = state.foregroundApplication?.icon.takeIf { connected }
        val leadingWidth = 18.dp + 6.dp +
            (if (applicationIcon != null) 18.dp + 6.dp else 0.dp)
        val showPolicy = connected && state.foregroundApplication != null && !countWorthShowing && maxWidth >= 260.dp
        val policyWidth = if (showPolicy) 42.dp else 0.dp
        val showCount = countWorthShowing &&
            maxWidth >= 96.dp + leadingWidth + policyWidth + 112.dp * density.fontScale + countWidth
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            // device-desktop: the computer this draft is sent to. It doubles as the fallback when the
            // computer did not report an application icon.
            Box(Modifier.size(18.dp).testTag("foreground-application-fallback")) {
                Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(18.dp).testTag("desktop-indicator"),
                    tint = color.onSurfaceSecondary)
            }
            Spacer(Modifier.width(6.dp))
            if (applicationIcon != null) {
                Image(applicationIcon.asImageBitmap(), null, Modifier.size(18.dp).testTag("foreground-application-icon"))
                Spacer(Modifier.width(6.dp))
            }
            Text(if (connected && state.foregroundApplication != null) visibleName else generic,
                Modifier.weight(1f).testTag("foreground-application")
                .clearAndSetSemantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
                fontSize = 12.sp, color = color.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showPolicy) {
                Spacer(Modifier.width(6.dp))
                SpeakPolicyChip(sendMode)
            }
            if (showCount) Text(countText, Modifier.padding(start = 8.dp).testTag("draft-character-count"),
                fontSize = 12.sp, color = if (count >= 3600) color.primary else color.onSurfaceSecondary,
                maxLines = 1)
            actions()
        }
    }
}

@Composable
private fun SpeakPolicyChip(mode: SpeakSendMode) {
    val automatic = mode == SpeakSendMode.Automatic
    val background = if (automatic) MiuixTheme.colorScheme.primary.copy(alpha = .11f)
    else MiuixTheme.colorScheme.surfaceContainerHigh
    val foreground = if (automatic) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
    val description = stringResource(
        R.string.speak_policy_description,
        stringResource(if (automatic) R.string.settings_policy_auto else R.string.settings_policy_manual),
    )
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = description }
            .testTag("foreground-policy"),
    ) {
        Text(
            stringResource(if (automatic) R.string.speak_policy_auto_short else R.string.speak_policy_manual_short),
            fontSize = 10.sp, fontWeight = FontWeight.Medium, color = foreground,
        )
    }
}

internal fun draftCharacterCount(draft: androidx.compose.ui.text.input.TextFieldValue): Int =
    if (draft.selection.collapsed) draft.text.codePointCount(0, draft.text.length)
    else draft.text.codePointCount(draft.selection.min, draft.selection.max)

@Composable
private fun DraftIcon(icon: VoiceLinkIcon, label: Int, tag: String, enabled: Boolean = true, progress: Boolean = false,
                      modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val description = stringResource(label)
    IconButton(onClick, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
        .testTag(tag).semantics { contentDescription = description; role = Role.Button; this.selected = selected; if (!enabled) disabled() }
        .then(modifier), enabled = enabled) {
        if (progress) CircularProgressIndicator(size = 19.dp, strokeWidth = 2.dp)
        else Icon(icon.vector, null, Modifier.size(21.dp),
            tint = (if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary).copy(alpha = if (enabled) 1f else .25f))
    }
}

@Composable
private fun VoiceWaveform(level: Float, listening: Boolean, color: Color) {
    val height by animateFloatAsState(if (listening) 1f else 0f, tween(180))
    var levels by remember { mutableStateOf(List(23) { 0f }) }
    LaunchedEffect(level, listening) {
        if (listening) levels = levels.drop(1) + level.coerceIn(0f, 1f)
    }
    if (height > 0f) Canvas(Modifier.fillMaxWidth().height((22 * height).dp).alpha(height)) {
        val step = minOf(5.dp.toPx(), size.width / levels.size)
        val left = (size.width - step * (levels.size - 1)) / 2
        levels.forEachIndexed { index, sample ->
            val bar = (3.dp.toPx() + sample * 17.dp.toPx()).coerceAtMost(size.height)
            drawLine(color, androidx.compose.ui.geometry.Offset(left + index * step, (size.height - bar) / 2),
                androidx.compose.ui.geometry.Offset(left + index * step, (size.height + bar) / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun plainTextButtonColors() = ButtonDefaults.textButtonColors(
    color = Color.Transparent,
    disabledColor = Color.Transparent,
    textColor = MiuixTheme.colorScheme.primary,
    disabledTextColor = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .45f),
)

private fun recognitionIssueText(issue: RecognitionIssue): Int = when (issue) {
    RecognitionIssue.Permission -> R.string.asr_permission_denied
    RecognitionIssue.Model -> R.string.asr_model_error
    RecognitionIssue.Microphone -> R.string.asr_microphone_error
    RecognitionIssue.TooSlow -> R.string.asr_too_slow
    RecognitionIssue.Recognition -> R.string.asr_recognition_error
    RecognitionIssue.CloudConfiguration -> R.string.cloud_error_configuration
    RecognitionIssue.CloudNetwork -> R.string.cloud_error_network
    RecognitionIssue.CloudAuthentication -> R.string.cloud_error_authentication
    RecognitionIssue.CloudAccessDenied -> R.string.cloud_error_access_denied
    RecognitionIssue.CloudQuota -> R.string.cloud_error_quota
    RecognitionIssue.CloudTimeout -> R.string.cloud_error_timeout
    RecognitionIssue.CloudProtocol -> R.string.cloud_error_protocol
    RecognitionIssue.CloudService -> R.string.cloud_error_service
}

internal fun sourceLabel(source: SpeakInputSource): Int = when (source) {
    SpeakInputSource.Local -> R.string.speak_source_local
    SpeakInputSource.Cloud -> R.string.speak_source_cloud
    SpeakInputSource.Keyboard -> R.string.speak_source_keyboard
}
