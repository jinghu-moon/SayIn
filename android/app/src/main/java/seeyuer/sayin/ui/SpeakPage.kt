package seeyuer.sayin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
    val source = session.source
    val normalized = remember(draft.text) { runCatching { normalizedText(draft.text) }.getOrNull() }
    val sendable = normalized != null && draft.text.isNotBlank() && draft.composition == null && !session.requestLocked && !session.draftRestoring &&
        !session.draftStorageIssue && session.canSendRepresentation
    val preparing = session.preparingToRecord || source == SpeakInputSource.Local && session.model in setOf(LocalModelPhase.Loading, LocalModelPhase.Warming)
    val activeCountdown = session.interaction.countdown
    val autoLabel = stringResource(R.string.speak_auto_send)
    var recordingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(asr.phase, session.recordingStartedAtMillis) {
        while (asr.phase == RecognitionPhase.Listening) {
            recordingSeconds = ((android.os.SystemClock.elapsedRealtime() - session.recordingStartedAtMillis) / 1000).toInt()
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
        if (source == SpeakInputSource.Cloud && cloudConfig == null) onConfigureCloud()
        else session.start(cloudConfig)
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
        session.interaction.review != null -> stringResource(reviewText(checkNotNull(session.interaction.review)))
        source == SpeakInputSource.Cloud && cloudConfig == null -> stringResource(R.string.cloud_setup_hint)
        source == SpeakInputSource.Cloud -> stringResource(R.string.speak_cloud_idle)
        source == SpeakInputSource.Local && session.model == LocalModelPhase.Ready -> stringResource(R.string.speak_local_ready)
        source == SpeakInputSource.Local -> stringResource(R.string.speak_local_cold)
        else -> stringResource(R.string.speak_keyboard_idle)
    }
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("page-Speak")) {
        val tight = maxHeight < 430.dp
        val smallControls = tight || quick
        val collapsedSources = tight && (WindowInsets.ime.getBottom(LocalDensity.current) > 0 || maxHeight < 220.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = if (quick) 12.dp else 16.dp, vertical = if (tight) 0.dp else 8.dp)
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (tight) 12.dp else 18.dp)) {
                if (quick && !focused) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ConnectionChip(state, ::connect, Modifier.weight(1f))
                    IconButton({ session.cancelAutoSend(); onDismiss() }, Modifier.size(48.dp).testTag("collapse-quick")) {
                        Icon(VoiceLinkIcon.ChevronDown.vector, stringResource(R.string.quick_collapse))
                    }
                }
                if (!tight) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!tight) Text(if (asr.phase == RecognitionPhase.Listening) stringResource(R.string.speak_listening_time, recordingSeconds / 60, recordingSeconds % 60)
                        else stringResource(if (session.translationShown) R.string.translation_english else if (session.busy) R.string.speak_recording else R.string.mobile_draft),
                        Modifier.weight(1f), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    else Spacer(Modifier.weight(1f))
                    Text(autoLabel, Modifier.weight(if (tight) 4f else 2f), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2)
                    Switch(session.sendMode == SpeakSendMode.Automatic,
                        { session.changeSendMode(if (it) SpeakSendMode.Automatic else SpeakSendMode.Manual) },
                        Modifier.testTag("send-mode").semantics { contentDescription = autoLabel }, enabled = !session.busy && !session.requestLocked)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPreview && normalized != null) Text(normalized,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("draft-preview"), fontSize = 22.sp, lineHeight = 34.sp)
                    else BasicTextField(draft, { session.changeDisplayedDraft(it) },
                        Modifier.fillMaxSize().focusRequester(focus).onFocusChanged {
                            focused = it.isFocused; if (it.isFocused) session.edit()
                        }.testTag("draft"), readOnly = session.busy || session.requestLocked || session.draftRestoring || session.translationShown && !session.translation.complete,
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = if (quick) 18.sp else 22.sp,
                            lineHeight = if (quick) 28.sp else 34.sp, color = MiuixTheme.colorScheme.onSurface),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize()) {
                                if (draft.text.isEmpty()) Text(stringResource(R.string.speak_draft_placeholder),
                                    fontSize = if (quick) 17.sp else 19.sp, lineHeight = 30.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                inner()
                            }
                        })
                }
                if (!collapsedSources) TranslationControls(session, smallControls, ::translate)
                if (activeCountdown != null) Row(Modifier.fillMaxWidth().testTag("auto-countdown"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.speak_countdown, session.secondsRemaining), Modifier.weight(1f), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary)
                    TextButton(stringResource(R.string.speak_cancel_send), { session.cancelAutoSend() },
                        Modifier.testTag("cancel-auto-send"), minHeight = 48.dp, colors = plainTextButtonColors())
                }
                if (!tight) HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                Row(Modifier.fillMaxWidth().padding(vertical = if (tight) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preparing) CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                    else if (source == SpeakInputSource.Local && session.model == LocalModelPhase.Ready && !asr.busy && asr.issue == null)
                        Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(14.dp))
                    Text(hint, Modifier.weight(1f).testTag("draft-hint").semantics { liveRegion = LiveRegionMode.Polite },
                        maxLines = if (tight) 1 else 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    if (!session.busy && normalized != null && normalized != draft.text) TextButton(
                        stringResource(if (showPreview) R.string.speak_edit else R.string.speak_preview), {
                            session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide(); showPreview = !showPreview
                        }, Modifier.testTag("normalization-toggle"), minHeight = 48.dp, minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 4.dp), colors = plainTextButtonColors())
                    else Text(pluralStringResource(R.plurals.speak_character_count,
                        draft.text.codePointCount(0, draft.text.length), draft.text.codePointCount(0, draft.text.length)),
                        fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (session.microphoneDenied && source.isRecognition) TextButton(stringResource(R.string.asr_open_settings), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, minHeight = 48.dp, colors = plainTextButtonColors())
                session.submitted?.let { request ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(receiptText(request.state)), Modifier.weight(1f).testTag("current-receipt"), fontSize = 13.sp)
                        if (request.state in setOf("saved", "accepted", "held", "executing", "review")) TextButton(
                            stringResource(R.string.speak_query), { onRequestAction(request.id, "query") }, minHeight = 48.dp, colors = plainTextButtonColors())
                    }
                    if (request.state == "held" && state.ready) TextButton(stringResource(R.string.release_request),
                        { onRequestAction(request.id, "release") }, minHeight = 48.dp, colors = plainTextButtonColors())
                    if (request.state == "uncertain" && state.ready) TextButton(stringResource(R.string.speak_new_draft),
                        { session.newDraft() }, minHeight = 48.dp, colors = plainTextButtonColors())
                    if (request.state == "uncertain") Text(stringResource(R.string.uncertain_warning), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (!state.ready && !tight) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.speak_offline_saved, stringResource(connectionChipText(state.connectionPresentation))),
                        Modifier.weight(1f), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2)
                    TextButton(stringResource(R.string.speak_view_connection), ::connect, minHeight = 48.dp,
                        insideMargin = PaddingValues(horizontal = 6.dp), colors = plainTextButtonColors())
                }
                if (session.undoDraft != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.speak_cleared), Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(stringResource(R.string.speak_undo), { session.undoClear() }, Modifier.testTag("undo-clear"), minHeight = 48.dp, colors = plainTextButtonColors())
                }
                if (session.draftStorageIssue) Text(stringResource(R.string.speak_draft_storage_issue), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error)
            }
            Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(horizontal = 12.dp, vertical = if (tight) 4.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 10.dp)) {
                if (!quick && !collapsedSources) InputSourcePicker(source, !session.busy && !session.requestLocked, tight) {
                    session.selectSource(it); focusManager.clearFocus(); keyboard?.hide(); showPreview = false
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val menu = buildList {
                            if (session.busy) add(DropdownItem(stringResource(R.string.speak_abort), onClick = { session.cancelRecording() }))
                            else add(DropdownItem(stringResource(R.string.speak_clear_draft), enabled = draft.text.isNotEmpty() && !session.requestLocked && !session.draftRestoring,
                                onClick = { session.clearDraft() }))
                            if (session.submitted?.state == "uncertain" && state.ready) add(DropdownItem(
                                stringResource(R.string.speak_new_draft), onClick = { session.newDraft() }))
                            if (tight) add(DropdownItem(stringResource(R.string.speak_auto_send), selected = session.sendMode == SpeakSendMode.Automatic,
                                enabled = !session.busy && !session.requestLocked, onClick = {
                                    session.changeSendMode(if (session.sendMode == SpeakSendMode.Automatic) SpeakSendMode.Manual else SpeakSendMode.Automatic)
                                }))
                            if (quick || collapsedSources) {
                                add(DropdownItem(stringResource(R.string.speak_source_title), enabled = !session.busy && !session.requestLocked, onClick = { showSources = true }))
                            }
                            if (collapsedSources) {
                                if (session.translation.ticket != null) {
                                    add(DropdownItem(stringResource(R.string.translation_original), selected = !session.translationShown, onClick = { session.showTranslation(false) }))
                                    add(DropdownItem(stringResource(R.string.translation_english), selected = session.translationShown, onClick = { session.showTranslation(true) }))
                                }
                                if (session.translation.running || session.translationStopping) add(DropdownItem(stringResource(R.string.asr_cancel),
                                    enabled = !session.translationStopping, onClick = { session.stopTranslation() }))
                                else if (session.translationPreparing) add(DropdownItem(stringResource(R.string.asr_cancel), onClick = { session.cancelModelPreparation() }))
                                else add(DropdownItem(stringResource(R.string.translation_action), enabled = session.canTranslateDraft, onClick = ::translate))
                            }
                            if (quick) {
                                add(DropdownItem(stringResource(R.string.quick_expand), onClick = { session.cancelAutoSend(); onExpand() }))
                            }
                        }
                        WindowIconDropdownMenu(DropdownEntry(menu), Modifier.size(48.dp).testTag("speak-more"),
                            onExpandedChange = { if (it) { session.cancelAutoSend(); focusManager.clearFocus(); keyboard?.hide() } },
                            backgroundColor = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 24.dp, minHeight = 48.dp, minWidth = 48.dp) {
                            Icon(VoiceLinkIcon.More.vector, stringResource(R.string.speak_more), Modifier.size(23.dp))
                        }
                        if (!tight && !focused) ActionCaption(R.string.speak_more)
                    }
                    val inputLabel = when {
                        preparing -> R.string.speak_preparing
                        asr.phase == RecognitionPhase.Loading -> R.string.asr_cancel
                        asr.phase == RecognitionPhase.Listening -> R.string.asr_finish
                        asr.busy -> R.string.asr_finishing
                        source == SpeakInputSource.Keyboard && focused -> R.string.speak_finish_input
                        source == SpeakInputSource.Keyboard -> R.string.mobile_open_keyboard
                        source == SpeakInputSource.Cloud && cloudConfig == null -> R.string.cloud_configure
                        draft.text.isNotEmpty() -> R.string.speak_continue
                        else -> R.string.speak_start_local
                    }
                    SpeakRoundAction(if (asr.busy) VoiceLinkIcon.Stop else if (source.isRecognition) VoiceLinkIcon.Microphone else VoiceLinkIcon.Input,
                        inputLabel, if (source.isRecognition) if (source == SpeakInputSource.Cloud) "start-cloud-asr" else "start-local-asr" else "open-keyboard",
                        Modifier.weight(1.3f), enabled = !session.requestLocked && !session.draftRestoring && !permissionPending && !preparing && asr.phase !in setOf(RecognitionPhase.Finishing, RecognitionPhase.Cancelling),
                        primary = true, hero = !tight, compact = smallControls, showLabel = !tight && !focused) {
                        when {
                            asr.phase == RecognitionPhase.Loading -> session.cancelRecording()
                            asr.phase == RecognitionPhase.Listening -> session.finishRecording()
                            source == SpeakInputSource.Keyboard && focused -> { focusManager.clearFocus(); keyboard?.hide() }
                            source == SpeakInputSource.Keyboard -> edit()
                            source == SpeakInputSource.Cloud && cloudConfig == null -> onConfigureCloud()
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> { session.microphoneDenied = false; start() }
                            else -> { permissionPending = true; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                        }
                    }
                    SpeakRoundAction(VoiceLinkIcon.Send, if (session.translationShown) R.string.translation_send else R.string.speak_send_to_computer, "send-draft", Modifier.weight(1f),
                        enabled = sendable && !session.busy, primary = true, compact = true, showLabel = !tight && !focused) {
                        session.cancelAutoSend(); if (state.ready) onSend() else connect()
                    }
                }
            }
        }
    }
    TranslationPreparationSheet(session, showTranslationPreparation) { showTranslationPreparation = false }
    OverlayBottomSheet(show = showSources, onDismissRequest = { showSources = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.speak_source_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            InputSourcePicker(source) { session.selectSource(it); showSources = false }
        }
    }
}

@Composable
private fun ActionCaption(label: Int) = Text(stringResource(label), fontSize = 11.sp,
    modifier = Modifier.padding(top = 6.dp), maxLines = 2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
    textAlign = androidx.compose.ui.text.style.TextAlign.Center)

@Composable
private fun SpeakRoundAction(icon: VoiceLinkIcon, label: Int, tag: String, modifier: Modifier,
    enabled: Boolean, primary: Boolean, hero: Boolean = false, compact: Boolean = false, showLabel: Boolean = true, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick, Modifier.size(if (hero && !compact) 84.dp else if (hero) 56.dp else 48.dp).testTag(tag),
            enabled = enabled, minHeight = 48.dp, cornerRadius = 48.dp, insideMargin = PaddingValues(0.dp),
            colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
            Icon(icon.vector, stringResource(label), Modifier.size(if (hero && !compact) 34.dp else 24.dp))
        }
        if (showLabel) ActionCaption(label)
    }
}

@Composable
private fun InputSourcePicker(source: SpeakInputSource, enabled: Boolean = true, compact: Boolean = false, onSelect: (SpeakInputSource) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showIcons = !compact && maxWidth >= 320.dp * fontScale
        Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SpeakInputSource.entries.forEach { item ->
                val selected = source == item
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (selected) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent, RoundedCornerShape(12.dp))
                    .selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(item) })
                    .testTag("input-source-${item.name}").padding(horizontal = if (showIcons) 8.dp else 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
                    if (showIcons) Icon((when (item) { SpeakInputSource.Local -> VoiceLinkIcon.Phone; SpeakInputSource.Cloud -> VoiceLinkIcon.Cloud; SpeakInputSource.Keyboard -> VoiceLinkIcon.Input }).vector,
                        null, Modifier.size(18.dp), tint = color)
                    if (showIcons) Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (item == SpeakInputSource.Local) R.string.speak_source_local_short else sourceLabel(item)),
                        modifier = Modifier.testTag("input-source-label-${item.name}"),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = if (compact) 12.sp else 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = color)
                }
            }
        }
    }
}

@Composable
internal fun plainTextButtonColors() = ButtonDefaults.textButtonColors(color = Color.Transparent,
    textColor = MiuixTheme.colorScheme.primary)

private fun reviewText(review: DraftReview): Int = when (review) {
    DraftReview.Manual, DraftReview.Automatic -> R.string.speak_review_ready
    DraftReview.Empty -> R.string.speak_draft_placeholder
    DraftReview.Invalid -> R.string.mobile_invalid_draft
    DraftReview.Composing -> R.string.speak_composing
    DraftReview.Edited -> R.string.speak_review_edited
    DraftReview.Offline -> R.string.speak_review_offline
}

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
