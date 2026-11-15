package seeyuer.sayin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import seeyuer.sayin.designsystem.YuePalette
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.normalizedText
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeakPage(
    state: ClientState,
    session: SpeakSession,
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
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val owner = remember { Any() }
    var showPreview by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showTranslationPreparation by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val draft = session.displayedDraft
    val asr = session.asr
    val currentCloud by rememberUpdatedState(cloudConfig)
    val source = session.source
    val normalized = remember(draft.text) { runCatching { normalizedText(draft.text) }.getOrNull() }
    val sendable = normalized != null && draft.text.isNotBlank() && draft.composition == null && !session.requestLocked && !session.draftRestoring &&
        !session.draftStorageIssue && session.canSendRepresentation
    val preparing = session.preparingToRecord || source == SpeakInputSource.Local && session.model in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
    val activeCountdown = session.interaction.countdown
    val autoLabel = stringResource(R.string.speak_auto_send)
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
    DisposableEffect(lifecycle, session, connectionOpen) {
        fun update() { resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); session.setVisible(owner, resumed && !connectionOpen) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); session.setVisible(owner, false) }
    }
    fun start() {
        focusManager.clearFocus(); keyboard?.hide(); showPreview = false
        if (session.source == SpeakInputSource.Cloud && currentCloud == null) onConfigureCloud()
        else session.start(currentCloud)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.microphoneDenied = !granted
        permissionGranted = granted
        if (!granted) permissionPending = false
    }
    LaunchedEffect(permissionPending, permissionGranted, resumed, connectionOpen) {
        if (permissionPending && permissionGranted && resumed && !connectionOpen) {
            permissionPending = false; permissionGranted = false; start()
        }
    }
    fun connect() {
        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); onConnect()
    }
    fun edit() { session.edit(); showPreview = false; focus.requestFocus(); keyboard?.show() }
    fun translate() {
        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); showPreview = false
        if (session.translationModelReady) session.translateDraft() else showTranslationPreparation = true
    }
    val hint = when {
        session.draftRestoring -> stringResource(R.string.speak_restoring_draft)
        session.modelPreparation != null && !session.busy -> stringResource(
            if (session.modelPreparation!!.stage == seeyuer.sayin.translation.ModelPreparationStage.Verifying) R.string.translation_preparing_verify else R.string.translation_preparing,
            (100 * session.modelPreparation!!.bytes / session.modelPreparation!!.total.coerceAtLeast(1)).toInt())
        session.modelPreparationFailed && !session.busy -> stringResource(R.string.translation_prepare_failed)
        session.translationShown && session.translationStopping -> stringResource(R.string.translation_stopping)
        session.translationShown && session.translation.running -> {
            val progress = session.translation.progress
            when (progress?.stage) {
                seeyuer.sayin.translation.TranslationStage.Verifying -> stringResource(R.string.translation_verifying, (progress.fraction * 100).toInt())
                seeyuer.sayin.translation.TranslationStage.Loading -> stringResource(R.string.translation_loading, (progress.fraction * 100).toInt())
                else -> stringResource(R.string.translation_generating)
            }
        }
        session.translationShown -> stringResource(translationEndText(session.translation.end))
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
    var readyNotice by remember { mutableStateOf(false) }
    var lastModel by remember { mutableStateOf(session.model) }
    LaunchedEffect(session.model) {
        val becameReady = session.model == LocalModelPhase.Ready && lastModel in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
        lastModel = session.model
        if (becameReady) { readyNotice = true; kotlinx.coroutines.delay(2000); readyNotice = false }
    }
    val voiceSource = if (source.isRecognition) source else session.lastVoiceSource
    fun microphone() {
        when {
            session.asr.phase == RecognitionPhase.Listening -> session.finishRecording()
            session.busy -> Unit
            else -> {
                val engine = if (session.source.isRecognition) session.source else session.lastVoiceSource
                if (!session.source.isRecognition) session.selectSource(engine)
                if (engine == SpeakInputSource.Cloud && currentCloud == null) onConfigureCloud()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    session.microphoneDenied = false; start()
                } else { permissionPending = true; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
    }
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("page-Speak")) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 300.dp * fontScale
        val minimal = maxHeight < 170.dp * fontScale
        val controlSize = if (minimal) 48.dp else 56.dp
        val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val showHeader = !imeOpen && maxHeight >= 260.dp * fontScale
        val showTools = maxHeight >= 240.dp * fontScale
        val busy = session.busy
        val locked = session.requestLocked || session.draftRestoring
        val toolsEnabled = !busy && !locked && !session.translation.running && !session.translationStopping && draft.composition == null
        val color = MiuixTheme.colorScheme
        val menu = buildList {
            add(DropdownItem(stringResource(R.string.image_choose), enabled = state.paired && state.storageHealthy && !busy && !locked,
                onClick = { session.cancelAutoSend(); onImage() }))
            add(DropdownItem(stringResource(R.string.speak_recognition_settings), enabled = !busy && !locked,
                icon = { Icon(VoiceLinkIcon.Settings.vector, null, it.size(20.dp)) }, onClick = { showSources = true }))
            add(DropdownItem(autoLabel, icon = { Icon(VoiceLinkIcon.Automatic.vector, null, it.size(20.dp)) }, selected = session.sendMode == SpeakSendMode.Automatic, enabled = !busy && !locked,
                onClick = { session.changeSendMode(if (session.sendMode == SpeakSendMode.Automatic) SpeakSendMode.Manual else SpeakSendMode.Automatic) }))
            if (!showTools) {
                add(DropdownItem(stringResource(R.string.speak_undo), enabled = session.canUndo, onClick = session::undo))
                add(DropdownItem(stringResource(R.string.speak_redo), enabled = session.canRedo, onClick = session::redo))
            }
            if (normalized != null && normalized != draft.text && !busy) add(DropdownItem(
                stringResource(if (showPreview) R.string.speak_edit else R.string.speak_preview), onClick = {
                    session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); showPreview = !showPreview
                }))
            if (quick) add(DropdownItem(stringResource(R.string.quick_expand), onClick = { session.cancelAutoSend(); onExpand() }))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            if (showHeader) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.sayin_brand), null, Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_name), Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                WindowIconDropdownMenu(DropdownEntry(menu), Modifier.size(48.dp).testTag("speak-more"),
                    onExpandedChange = { if (it) { session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide() } },
                    minHeight = 48.dp, minWidth = 48.dp) {
                    Icon(VoiceLinkIcon.More.vector, stringResource(R.string.speak_more), Modifier.size(22.dp))
                }
                ConnectionChip(state, ::connect, Modifier.widthIn(max = 160.dp), compact = true)
                if (quick) DraftIcon(VoiceLinkIcon.ChevronDown, R.string.quick_collapse, "collapse-quick") { session.cancelAutoSend(); onDismiss() }
            }
            Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(color.surface)
                .padding(start = 16.dp, end = 12.dp, top = if (showTools) 10.dp else 0.dp, bottom = if (minimal) 0.dp else 6.dp)) {
                if (showTools) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (session.sendMode == SpeakSendMode.Automatic) Icon(VoiceLinkIcon.Automatic.vector, autoLabel,
                        Modifier.size(18.dp).testTag("auto-send-indicator"), tint = color.primary)
                    if (preparing) Text(hint, Modifier.weight(1f).testTag("model-preparation-notice").semantics { liveRegion = LiveRegionMode.Polite },
                        color = color.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (readyNotice) Text(stringResource(R.string.speak_local_ready), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        color = color.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Spacer(Modifier.weight(1f))
                    DraftIcon(VoiceLinkIcon.Undo, R.string.speak_undo, "undo-draft", session.canUndo, onClick = session::undo)
                    DraftIcon(VoiceLinkIcon.Redo, R.string.speak_redo, "redo-draft", session.canRedo, onClick = session::redo)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPreview && normalized != null) Text(normalized,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("draft-preview"), fontSize = 22.sp, lineHeight = 35.sp)
                    else BasicTextField(draft, session::changeDisplayedDraft,
                        Modifier.fillMaxSize().focusRequester(focus).onFocusChanged {
                            focused = it.isFocused; if (it.isFocused) session.edit()
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
                                if (draft.text.isEmpty() && !focused) Canvas(Modifier.fillMaxWidth().height(32.dp).align(Alignment.Center).alpha(.26f)) {
                                    val points = listOf(10f to 16f, 90f to 16f, 96f to 11f, 102f to 21f, 108f to 7f,
                                        114f to 27f, 120f to 11f, 126f to 21f, 132f to 16f, 218f to 16f)
                                    val path = Path()
                                    points.forEachIndexed { index, (x, y) ->
                                        if (index == 0) path.moveTo(x / 300f * size.width, y / 32f * size.height)
                                        else path.lineTo(x / 300f * size.width, y / 32f * size.height)
                                    }
                                    drawPath(path, color.onSurfaceSecondary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                                }
                                inner()
                            }
                        })
                }
                if (activeCountdown != null) Row(Modifier.fillMaxWidth().testTag("auto-countdown"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.speak_countdown, session.secondsRemaining), Modifier.weight(1f), fontSize = 12.sp, color = color.primary)
                    DraftIcon(VoiceLinkIcon.Close, R.string.speak_cancel_send, "cancel-auto-send") { session.cancelAutoSend() }
                }
                val exceptional = session.draftRestoring || session.draftStorageIssue || session.microphoneDenied || asr.issue != null ||
                    session.model == LocalModelPhase.Failed || session.translation.running || session.translationStopping || session.translationPreparing ||
                    session.modelPreparationFailed || session.translationShown && !session.translation.complete || draft.text.isNotEmpty() && normalized == null
                if (exceptional && !compact) Text(if (session.draftStorageIssue) stringResource(R.string.speak_draft_storage_issue) else hint,
                    Modifier.fillMaxWidth().testTag("draft-hint").semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp,
                    color = color.onSurfaceSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (session.microphoneDenied && !compact) TextButton(stringResource(R.string.asr_open_settings), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, minHeight = 48.dp, colors = plainTextButtonColors())
                session.submitted?.takeIf { it.state != "injected" }?.let { request ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(receiptText(request.state)), Modifier.weight(1f).testTag("current-receipt"), fontSize = 12.sp,
                            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (request.state in setOf("saved", "accepted", "held", "executing", "review")) TextButton(
                            stringResource(R.string.speak_query), { onRequestAction(request.id, "query") }, minHeight = 48.dp, colors = plainTextButtonColors())
                        if (request.state == "held" && state.ready) TextButton(stringResource(R.string.release_request),
                            { onRequestAction(request.id, "release") }, minHeight = 48.dp, colors = plainTextButtonColors())
                        if (request.state == "uncertain" && state.ready) TextButton(stringResource(R.string.speak_new_draft),
                            session::newDraft, minHeight = 48.dp, colors = plainTextButtonColors())
                    }
                    if (request.state == "uncertain" && !compact) Text(stringResource(R.string.uncertain_warning), fontSize = 12.sp, color = color.onSurfaceSecondary)
                }
                if (!minimal) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft.text.isEmpty() || session.translation.ticket != null && availableWidth < 350.dp) "" else draft.text.codePointCount(0, draft.text.length).toString(), Modifier.weight(1f), fontSize = 13.sp, color = color.onSurfaceSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (session.translation.ticket != null) Row(Modifier.background(color.background, RoundedCornerShape(16.dp))) {
                        listOf(false, true).forEach { translated ->
                            val selected = session.translationShown == translated
                            Box(Modifier.size(48.dp).selectable(selected, role = Role.Tab, onClick = { session.showTranslation(translated) })
                                .testTag(if (translated) "translation-english" else "translation-original"), contentAlignment = Alignment.Center) {
                                Box(Modifier.height(28.dp).fillMaxWidth().background(if (selected) color.surface else Color.Transparent, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Text(stringResource(if (translated) R.string.speak_translation_short else R.string.speak_original_short),
                                        fontSize = 12.sp, color = if (selected) color.primary else color.onSurfaceSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (normalized != null && normalized != draft.text && !busy && session.translation.ticket == null) DraftIcon(VoiceLinkIcon.Input,
                        if (showPreview) R.string.speak_edit else R.string.speak_preview, "normalization-toggle") {
                        session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); showPreview = !showPreview
                    }
                    DraftIcon(VoiceLinkIcon.Translate,
                        if (session.translation.running || session.translationPreparing) R.string.asr_cancel else R.string.translation_action, "translate-draft",
                        session.canTranslateDraft || session.translation.running || session.translationPreparing,
                        progress = session.translation.running || session.translationPreparing) {
                        when { session.translation.running -> session.stopTranslation(); session.translationPreparing -> session.cancelModelPreparation(); else -> translate() }
                    }
                    DraftIcon(VoiceLinkIcon.Copy, R.string.speak_copy_draft, "copy-draft", draft.text.isNotEmpty() && !session.translation.running && !session.translationStopping) {
                        session.cancelAutoSend(); uiScope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", draft.text))) }
                    }
                    DraftIcon(VoiceLinkIcon.Clear, R.string.speak_clear_draft, "clear-draft", toolsEnabled && draft.text.isNotEmpty()) { session.clearDraft() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = if (minimal) 0.dp else 8.dp, bottom = if (minimal) 0.dp else 10.dp).height(controlSize), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    val callbacks = rememberUpdatedState(Triple<() -> Unit, () -> Unit, () -> Unit>(::microphone,
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
                                if (released == true) callbacks.value.first()
                                else if (released == null && currentEvent.changes.any { it.id == down.id && it.pressed }) {
                                    held = true
                                    if (!session.busy) callbacks.value.first()
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
                            cancelArmed -> Text(stringResource(R.string.speak_release_cancel), color = color.onPrimary, fontSize = 14.sp, maxLines = 1)
                            preparing || asr.phase == RecognitionPhase.Loading -> Text(stringResource(R.string.speak_preparing), color = color.onPrimary, fontSize = 14.sp, maxLines = 1)
                            else -> {
                                VoiceWaveform(asr.audioLevel, listening, color.onPrimary)
                                Text(if (uncertain) stringResource(R.string.speak_review_short) else buildString {
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
                        val sendLabel = when { uncertain -> R.string.speak_review_short; listening -> R.string.speak_finish_send; else -> R.string.speak_send_to_computer }
                        val sendDescription = stringResource(when { pending -> receiptText(checkNotNull(session.submitted).state)
                            slowFinal -> R.string.asr_finishing; injectedFeedback -> R.string.receipt_injected; else -> sendLabel })
                        IconButton({
                            session.cancelAutoSend()
                            if (uncertain) connect()
                            else if (listening) session.finishRecording(andSend = true)
                            else if (state.ready) onSend() else connect()
                        }, Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                            .background(if (allowSend || pending) color.onPrimary.copy(alpha = .12f) else Color.Transparent)
                            .testTag("send-draft").alpha(if (preparing || asr.phase == RecognitionPhase.Loading || listening && !session.recordingHasText) 0f else 1f).semantics {
                                role = Role.Button; contentDescription = sendDescription
                                if (cancelArmed || !(allowSend || uncertain)) disabled()
                            }, enabled = !cancelArmed && (allowSend || uncertain)) {
                            if (pending || slowFinal) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp, colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color.onPrimary, backgroundColor = Color.Transparent))
                            else Crossfade(when { uncertain -> VoiceLinkIcon.Alert; injectedFeedback -> VoiceLinkIcon.Check
                                session.submitted?.state == "failed" -> VoiceLinkIcon.Retry; else -> VoiceLinkIcon.ArrowUp }, animationSpec = tween(160)) { icon ->
                                Icon(icon.vector, null, Modifier.size(24.dp),
                                    tint = color.onPrimary.copy(alpha = if (allowSend || uncertain || injectedFeedback) 1f else .3f))
                            }
                        }
                    }
                }
                if (!showHeader && !imeOpen && availableHeight >= 180.dp) WindowIconDropdownMenu(DropdownEntry(menu),
                    Modifier.size(48.dp).testTag("speak-more"), onExpandedChange = { if (it) session.cancelAutoSend() }) {
                    Icon(VoiceLinkIcon.More.vector, stringResource(R.string.speak_more))
                }
            }
        }
        if (clearNotice) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
            .background(color.onSurface, RoundedCornerShape(28.dp)).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.speak_cleared), fontSize = 13.sp, color = color.surface)
            TextButton(stringResource(R.string.speak_undo), { session.undoClear() }, Modifier.testTag("undo-clear"),
                minHeight = 48.dp, colors = plainTextButtonColors())
        }
    }
    TranslationPreparationSheet(session, showTranslationPreparation) { showTranslationPreparation = false }
    OverlayBottomSheet(show = showSources, onDismissRequest = { showSources = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.speak_recognition_settings), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            RecognitionChoices(voiceSource, cloudConfig != null) {
                session.selectSource(it); showSources = false
                if (it == SpeakInputSource.Cloud && cloudConfig == null) onConfigureCloud()
            }
        }
    }
}

@Composable
private fun DraftIcon(icon: VoiceLinkIcon, label: Int, tag: String, enabled: Boolean = true, progress: Boolean = false, onClick: () -> Unit) {
    val description = stringResource(label)
    IconButton(onClick, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description; role = Role.Button; if (!enabled) disabled() }, enabled = enabled) {
        if (progress) CircularProgressIndicator(size = 19.dp, strokeWidth = 2.dp)
        else Icon(icon.vector, null, Modifier.size(21.dp),
            tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = if (enabled) 1f else .25f))
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
private fun RecognitionChoices(source: SpeakInputSource, cloudConfigured: Boolean, onSelect: (SpeakInputSource) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(SpeakInputSource.Local, SpeakInputSource.Cloud).forEach { item ->
            val selected = source == item
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (selected) MiuixTheme.colorScheme.background else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { onSelect(item) })
                .testTag("input-source-${item.name}").padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon((if (item == SpeakInputSource.Local) VoiceLinkIcon.Phone else VoiceLinkIcon.Cloud).vector, null, Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (item == SpeakInputSource.Local) R.string.speak_offline_recognition else R.string.speak_cloud_recognition),
                        Modifier.testTag("input-source-label-${item.name}"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, softWrap = true, maxLines = 2)
                    Text(stringResource(if (item == SpeakInputSource.Local) R.string.speak_offline_description
                        else if (cloudConfigured) R.string.speak_cloud_description else R.string.cloud_setup_hint), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (selected) Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun plainTextButtonColors() = ButtonDefaults.textButtonColors(color = Color.Transparent,
    textColor = MiuixTheme.colorScheme.primary)

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
