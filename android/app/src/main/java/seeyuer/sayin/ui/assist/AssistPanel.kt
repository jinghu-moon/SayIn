package seeyuer.sayin.ui.assist

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import seeyuer.sayin.R
import seeyuer.sayin.data.AndroidClipboard
import seeyuer.sayin.data.ClipboardInvalidReason
import seeyuer.sayin.data.ClipboardRead
import seeyuer.sayin.data.PhraseLibrary
import seeyuer.sayin.data.PhraseRules
import seeyuer.sayin.data.PhraseWriteResult
import seeyuer.sayin.data.StoredPhrase
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.AssistInsertResult
import seeyuer.sayin.ui.AssistInsertion
import seeyuer.sayin.ui.DraftAssist
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The two visible input sources of one combo panel. */
internal enum class AssistTab { Clipboard, Phrases }

/** Why the panel closed, so the page can explain a rejected insertion after the overlay is gone. */
internal enum class AssistDismissReason { User, DraftChanged, NotEditable }

/**
 * The single "Clipboard and phrases" overlay. It is hosted by [seeyuer.sayin.ui.SpeakPage] so the
 * entry button can take accessibility focus back when this closes.
 *
 * The panel itself uses the Miuix native bottom sheet. The clear-all/discard confirmation is a
 * separate full-window dialog: while it is open the sheet window is covered by a modal window, so
 * the panel is inert, Escape/back cancels only the confirmation and the default focus stays on the
 * safe (cancel/keep) button. The sheet alone cannot give that guarantee because its back/scrim
 * handling is shared for the whole sheet.
 */
@Composable
internal fun AssistPanel(
    show: Boolean,
    openGeneration: Int,
    session: SpeakSession,
    phrases: PhraseLibrary,
    ticket: AssistInsertion?,
    initialTab: AssistTab,
    onTicket: (AssistInsertion?) -> Unit,
    onTab: (AssistTab) -> Unit,
    onDismiss: (AssistDismissReason) -> Unit,
) {
    var modal by remember { mutableStateOf(false) }
    LaunchedEffect(show) { if (!show) modal = false }
    OverlayBottomSheet(
        show = show,
        onDismissRequest = { onDismiss(AssistDismissReason.User) },
        cornerRadius = 26.dp,
        outsideMargin = DpSize(8.dp, 8.dp),
        insideMargin = DpSize(0.dp, 0.dp),
        allowDismiss = !modal,
    ) {
        if (show) {
            key(openGeneration) {
                AssistPanelContent(
                    session = session,
                    phrases = phrases,
                    ticket = ticket,
                    initialTab = initialTab,
                    onTicket = onTicket,
                    onTab = onTab,
                    onDismiss = onDismiss,
                    onModalChange = { modal = it },
                )
            }
        }
    }
}

private enum class AssistMode { List, Editor, Viewer }

private data class EditorDraft(val id: String?, val original: String, val text: String)

private data class PendingConfirm(
    val kind: ConfirmKind,
    val revision: Long = 0,
    val clip: ClipboardRead? = null,
    val count: Int = 0,
)

private enum class ConfirmKind { ClearClipboard, ClearPhrases, ClearRoot, DiscardBack, DiscardClose }

private data class PhraseUndo(val phrase: StoredPhrase, val index: Int)

private data class PhraseDrag(val id: String, val revision: Long)

@Composable
private fun AssistPanelContent(
    session: SpeakSession,
    phrases: PhraseLibrary,
    ticket: AssistInsertion?,
    initialTab: AssistTab,
    onTicket: (AssistInsertion?) -> Unit,
    onTab: (AssistTab) -> Unit,
    onDismiss: (AssistDismissReason) -> Unit,
    onModalChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val libraryState by phrases.state.collectAsStateWithLifecycle()
    val libraryIssue by phrases.issue.collectAsStateWithLifecycle()
    val libraryReady by phrases.ready.collectAsStateWithLifecycle()
    val reader = remember(context) { AndroidClipboard(context) }
    val panelTitle = stringResource(R.string.assist_open)
    val movedTemplate = stringResource(R.string.assist_moved)

    var tab by remember { mutableStateOf(initialTab) }
    val rootClipboard = remember(tab) { RootClipboardState() }
    var mode by remember { mutableStateOf(AssistMode.List) }
    var editor by remember { mutableStateOf<EditorDraft?>(null) }
    var viewerText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editorRejection by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var undo by remember { mutableStateOf<PhraseUndo?>(null) }
    var moduleHistory by remember { mutableStateOf(false) }
    var moduleModal by remember { mutableStateOf(false) }
    var clipboardRead by remember { mutableStateOf<ClipboardRead?>(null) }
    var clipboardLoading by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var readAttempt by remember { mutableIntStateOf(0) }
    var readGeneration by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var stopRequested by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(libraryState.phrases) }
    var drag by remember { mutableStateOf<PhraseDrag?>(null) }
    var dragEpoch by remember { mutableIntStateOf(0) }
    var orderSaving by remember { mutableStateOf(false) }
    var orderRevision by remember { mutableLongStateOf(libraryState.revision) }
    var pendingGripFocus by remember { mutableStateOf<String?>(null) }
    val gripFocusers = remember { mutableMapOf<String, FocusRequester>() }
    val editorFocus = remember { FocusRequester() }
    val confirmCancelFocus = remember { FocusRequester() }
    val phraseList = rememberLazyListState()

    SideEffect { onModalChange(mode != AssistMode.List || drag != null || moduleModal) }
    DisposableEffect(lifecycle, rootClipboard) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    readGeneration++
                    clipboardRead = null
                    rootClipboard.clear()
                    viewerText = ""
                    if (mode == AssistMode.Viewer) mode = AssistMode.List
                    revealed = false
                    confirm = null
                    if (event == Lifecycle.Event.ON_STOP) stopRequested = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { rootClipboard.clear(); lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            readGeneration++
            clipboardRead = null
            keyboard?.hide()
        }
    }
    // A gesture owns only a preview. Invalidate it if another entry changes the library;
    // never apply an order built from an older revision to the new contents.
    LaunchedEffect(libraryState, orderSaving) {
        if (drag?.revision?.let { it != libraryState.revision } == true) {
            drag = null
            dragEpoch++
            notice = R.string.assist_reorder_failed
        }
        if (drag == null && !orderSaving) {
            working = libraryState.phrases
            orderRevision = libraryState.revision
        }
    }
    LaunchedEffect(notice) {
        val current = notice
        if (current != null) {
            delay(2_500)
            if (notice == current) notice = null
        }
    }
    LaunchedEffect(undo) {
        val current = undo
        if (current != null) {
            delay(6_000)
            if (undo == current) undo = null
        }
    }
    LaunchedEffect(pendingGripFocus, working) {
        val id = pendingGripFocus ?: return@LaunchedEffect
        withFrameNanos { }
        gripFocusers[id]?.requestFocus()
        pendingGripFocus = null
    }
    // One read per panel open, plus one per switch back to the clipboard tab and per explicit
    // retry. The Miuix sheet hosts its content in a non-focusable popup-style window, so the
    // Activity window is the authority for "this app currently owns input focus"; the read also
    // waits for the soft keyboard to be gone, as required by the design's open sequence.
    LaunchedEffect(tab, readAttempt, resumed) {
        if (tab != AssistTab.Clipboard || !resumed) {
            readGeneration++
            clipboardRead = null
            revealed = false
            clipboardLoading = false
            return@LaunchedEffect
        }
        val generation = ++readGeneration
        clipboardLoading = true
        clipboardRead = null
        revealed = false
        val ready = awaitClipboardWindow(context, view)
        if (generation != readGeneration) return@LaunchedEffect
        if (ready) {
            val result = reader.read(true)
            if (generation == readGeneration) {
                clipboardRead = result
                clipboardLoading = false
            }
        } else {
            clipboardRead = ClipboardRead.Unavailable
            clipboardLoading = false
        }
    }

    fun close(reason: AssistDismissReason = AssistDismissReason.User) {
        keyboard?.hide()
        focusManager.clearFocus()
        onDismiss(reason)
    }
    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            stopRequested = false
            close(AssistDismissReason.User)
        }
    }

    fun insert(text: String, closeOnSuccess: Boolean) {
        val opening = ticket
        if (opening == null) {
            close(AssistDismissReason.DraftChanged)
            return
        }
        when (session.insertAssist(opening, text)) {
            is AssistInsertResult.Inserted -> {
                if (closeOnSuccess) close() else {
                    val next = session.beginAssistInsertion()
                    if (next == null) close(AssistDismissReason.NotEditable) else {
                        onTicket(next)
                        notice = R.string.assist_pasted
                    }
                }
            }
            AssistInsertResult.Stale -> close(AssistDismissReason.DraftChanged)
            AssistInsertResult.NotEditable -> close(AssistDismissReason.NotEditable)
            AssistInsertResult.Rejected -> notice = R.string.assist_insert_failed
        }
    }

    fun copy(text: String) {
        scope.launch {
            try {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
                notice = R.string.assist_copied
            } catch (failure: Exception) {
                notice = R.string.assist_copy_failed
            }
        }
    }

    fun openEditor(id: String?, text: String, original: String) {
        if (!libraryReady || libraryIssue) {
            notice = R.string.assist_library_issue
            return
        }
        if (id == null && libraryState.phrases.size >= PhraseRules.LIMIT_ITEMS) {
            notice = R.string.assist_phrase_full
            return
        }
        editorRejection = null
        editor = EditorDraft(id, original, text)
        mode = AssistMode.Editor
        keyboard?.show()
    }

    fun unlessEditor(closePanel: Boolean) {
        keyboard?.hide()
        editor = null
        editorRejection = null
        mode = AssistMode.List
        if (closePanel) close()
    }

    fun leaveEditor(closePanel: Boolean) {
        val draft = editor ?: run {
            mode = AssistMode.List
            return
        }
        if (draft.text == draft.original) {
            unlessEditor(closePanel)
        } else {
            keyboard?.hide()
            confirm = PendingConfirm(if (closePanel) ConfirmKind.DiscardClose else ConfirmKind.DiscardBack)
        }
    }

    fun validateEditor(draft: EditorDraft): Int? {
        if (draft.text.isBlank()) return R.string.assist_phrase_empty
        return when (PhraseRules.validate(draft.text)) {
            PhraseWriteResult.SAVED -> {
                val duplicate = libraryState.phrases.any {
                    it.id != draft.id && PhraseRules.duplicateKey(it.text) == PhraseRules.duplicateKey(draft.text)
                }
                if (duplicate) R.string.assist_phrase_duplicate else null
            }
            PhraseWriteResult.TOO_LONG -> R.string.assist_phrase_too_long
            PhraseWriteResult.INVALID -> R.string.assist_phrase_invalid
            PhraseWriteResult.FULL -> R.string.assist_phrase_full
            PhraseWriteResult.DUPLICATE -> R.string.assist_phrase_duplicate
            PhraseWriteResult.EMPTY -> R.string.assist_phrase_empty
            PhraseWriteResult.CONFLICT, PhraseWriteResult.UNAVAILABLE -> R.string.assist_save_failed
        }
    }

    fun saveEditor() {
        val draft = editor ?: return
        val rejection = validateEditor(draft)
        if (rejection != null) {
            editorRejection = rejection
            return
        }
        saving = true
        scope.launch {
            val result = withContext(NonCancellable) {
                if (draft.id == null) phrases.add(draft.text) else phrases.update(draft.id, draft.text)
            }
            saving = false
            when (result) {
                PhraseWriteResult.SAVED -> {
                    keyboard?.hide()
                    editor = null
                    editorRejection = null
                    mode = AssistMode.List
                    tab = AssistTab.Phrases
                    onTab(AssistTab.Phrases)
                    notice = R.string.assist_saved
                }
                PhraseWriteResult.DUPLICATE -> editorRejection = R.string.assist_phrase_duplicate
                PhraseWriteResult.FULL -> notice = R.string.assist_phrase_full
                PhraseWriteResult.UNAVAILABLE -> notice = R.string.assist_save_failed
                PhraseWriteResult.CONFLICT -> notice = R.string.assist_save_failed
                PhraseWriteResult.EMPTY -> editorRejection = R.string.assist_phrase_empty
                PhraseWriteResult.TOO_LONG -> editorRejection = R.string.assist_phrase_too_long
                PhraseWriteResult.INVALID -> editorRejection = R.string.assist_phrase_invalid
            }
        }
    }

    fun deletePhrase(phrase: StoredPhrase, index: Int) {
        scope.launch {
            when (withContext(NonCancellable) { phrases.delete(phrase.id) }) {
                PhraseWriteResult.SAVED -> {
                    undo = PhraseUndo(phrase, index)
                    notice = R.string.assist_deleted
                }
                else -> notice = R.string.assist_delete_failed
            }
        }
    }

    fun undoDelete() {
        val current = undo ?: return
        scope.launch {
            when (withContext(NonCancellable) { phrases.restore(current.phrase, current.index) }) {
                PhraseWriteResult.SAVED -> {
                    undo = null
                    notice = R.string.assist_restored
                }
                else -> notice = R.string.assist_restore_failed
            }
        }
    }

    fun commitOrder(revision: Long = orderRevision) {
        // Capture the released order now, before the coroutine or any library notification runs.
        val ids = working.map { it.id }
        if (ids == phrases.state.value.phrases.map { it.id }) return
        orderSaving = true
        scope.launch {
            withContext(NonCancellable) {
                val result = phrases.reorder(ids, revision)
                working = phrases.state.value.phrases
                orderRevision = phrases.state.value.revision
                orderSaving = false
                notice = if (result == PhraseWriteResult.SAVED) R.string.assist_reordered
                    else R.string.assist_reorder_failed
            }
        }
    }

    fun moveGrip(id: String, delta: Int) {
        if (libraryIssue || !libraryReady || orderSaving || drag != null) return
        val index = working.indexOfFirst { it.id == id }
        val to = index + delta
        if (index < 0 || to < 0 || to >= working.size) return
        working = working.toMutableList().apply { add(to, removeAt(index)) }
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        val announce = String.format(Locale.getDefault(), movedTemplate, to + 1, working.size)
        view.announceForAccessibility(announce)
        pendingGripFocus = id
        commitOrder()
    }

    fun finishDrag(commit: Boolean) {
        val current = drag ?: return
        drag = null
        if (commit) commitOrder(current.revision) else {
            working = phrases.state.value.phrases
            orderRevision = phrases.state.value.revision
        }
    }

    fun onBack(): Boolean {
        if (drag != null) {
            finishDrag(false)
            dragEpoch++
            return true
        }
        if (mode == AssistMode.Editor) {
            leaveEditor(closePanel = false)
            return true
        }
        if (mode == AssistMode.Viewer) {
            mode = AssistMode.List
            return true
        }
        return false
    }

    BackHandler(enabled = confirm == null) { if (!onBack()) close() }

    val lightTheme = MiuixTheme.colorScheme.background == YuePalette.Neutral100
    val scrimColor = if (lightTheme) YuePalette.Neutral1000.copy(alpha = 0.2f) else YuePalette.Neutral1000.copy(alpha = 0.5f)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        // The overlay window already shrinks above the soft keyboard, so sizing is a fraction of the
        // real available height: the list shows a compact sheet, while the editor and the full-text
        // viewer take all of it so the field grows instead of collapsing to one line.
        val bounded = constraints.hasBoundedHeight && maxHeight != Dp.Infinity
        val fallbackHeight = (LocalWindowInfo.current.containerDpSize.height * 0.58f).coerceAtLeast(280.dp)
        val minHeight = (280.dp * (1f + (fontScale - 1f) * 0.5f))
            .coerceAtMost(if (bounded) maxHeight else fallbackHeight)
        val fraction = when (mode) {
            AssistMode.List -> 0.58f
            // Leave room for the drag handle and the rounded top edge below the status bar.
            AssistMode.Editor -> 0.94f
            AssistMode.Viewer -> 0.90f
        }
        val heightModifier = if (bounded) {
            Modifier.fillMaxHeight(fraction)
        } else {
            Modifier.height((fallbackHeight * fraction / 0.58f)
                .coerceAtMost(LocalWindowInfo.current.containerDpSize.height * 0.94f))
        }
        Column(
            Modifier.fillMaxWidth().then(heightModifier).heightIn(min = minHeight)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .testTag("assist-panel").semantics { paneTitle = panelTitle },
        ) {
            AssistHeader(
                mode = mode,
                tab = tab,
                editorTitle = when {
                    editor == null -> R.string.assist_editor_add_title
                    editor?.id == null -> R.string.assist_editor_add_title
                    else -> R.string.assist_editor_edit_title
                },
                onTab = {
                    tab = it
                    onTab(it)
                    undo = null
                    notice = null
                },
                onAdd = { openEditor(null, "", "") },
                onBack = {
                    if (mode == AssistMode.Editor) leaveEditor(closePanel = false) else mode = AssistMode.List
                },
                onClose = {
                    if (mode == AssistMode.Editor) leaveEditor(closePanel = true) else close()
                },
            )
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth()) {
                notice?.let { message ->
                    Text(
                        stringResource(message),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("assist-notice").semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (mode) {
                        AssistMode.List -> when (tab) {
                            AssistTab.Clipboard -> RootClipboardTab(
                                state = rootClipboard,
                                onAvailable = { moduleHistory = it },
                                onModal = { moduleModal = it },
                                onInsert = { insert(it, closeOnSuccess = true) },
                                onCopy = { copy(it) },
                                onPaste = { insert(it, closeOnSuccess = false) },
                                onSaveAsPhrase = { openEditor(null, it, "") },
                                onView = { viewerText = it; mode = AssistMode.Viewer },
                            ) { ClipboardTab(
                                read = clipboardRead,
                                loading = clipboardLoading,
                                revealed = revealed,
                                onReveal = { revealed = true },
                                onRetry = { readAttempt++ },
                                onInsert = { insert(it, closeOnSuccess = true) },
                                onCopy = { copy(it) },
                                onPaste = { insert(it, closeOnSuccess = false) },
                                onSaveAsPhrase = { openEditor(null, it, "") },
                                onView = { viewerText = it; mode = AssistMode.Viewer },
                            ) }
                            AssistTab.Phrases -> key(dragEpoch) {
                                PhraseTab(
                                    phrases = working,
                                    ready = libraryReady,
                                    issue = libraryIssue,
                                    emptyAction = { openEditor(null, "", "") },
                                    listState = phraseList,
                                    reorderEnabled = !orderSaving,
                                    onInsert = { insert(it, closeOnSuccess = true) },
                                    onCopy = { copy(it.text) },
                                    onPaste = { insert(it.text, closeOnSuccess = false) },
                                    onView = { viewerText = it.text; mode = AssistMode.Viewer },
                                    onEdit = { openEditor(it.id, it.text, it.text) },
                                    onDelete = { phrase ->
                                        val index = working.indexOfFirst { it.id == phrase.id }
                                        if (index >= 0) deletePhrase(phrase, index)
                                    },
                                    onMove = { from, to ->
                                        if (drag?.id == from) {
                                            val fromIndex = working.indexOfFirst { it.id == from }
                                            val toIndex = working.indexOfFirst { it.id == to }
                                            if (fromIndex >= 0 && toIndex >= 0) {
                                                working = working.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onGripDown = { id ->
                                        drag = PhraseDrag(id, orderRevision)
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    },
                                    onGripEnd = { released -> finishDrag(released) },
                                    onGripKey = { id, delta -> moveGrip(id, delta) },
                                    onGripFocuser = { id, requester -> gripFocusers[id] = requester },
                                )
                            }
                        }
                        AssistMode.Editor -> {
                            val draft = editor
                            if (draft != null) EditorBody(
                                draft = draft,
                                error = validateEditor(draft),
                                rejection = editorRejection,
                                saving = saving,
                                focus = editorFocus,
                                onText = { text ->
                                    editorRejection = null
                                    editor = draft.copy(text = text)
                                },
                                onSave = { saveEditor() },
                            )
                        }
                        AssistMode.Viewer -> ViewerBody(viewerText)
                    }
                }
            }

            if (mode == AssistMode.List && (tab != AssistTab.Clipboard || !moduleHistory)) {
                AssistFooter(
                    tab = tab,
                    issue = libraryIssue,
                    ready = libraryReady,
                    clipboardRead = clipboardRead,
                    phraseCount = libraryState.phrases.size,
                    undo = undo,
                    onUndo = { undoDelete() },
                    onClear = {
                        confirm = when (tab) {
                            AssistTab.Clipboard -> PendingConfirm(ConfirmKind.ClearClipboard, clip = clipboardRead)
                            AssistTab.Phrases -> PendingConfirm(
                                ConfirmKind.ClearPhrases,
                                revision = libraryState.revision,
                                count = libraryState.phrases.size,
                            )
                        }
                    },
                )
            }
            if (mode == AssistMode.Editor) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val draft = editor ?: return@Column
                    Button(
                        { saveEditor() },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assist-save"),
                        enabled = !saving && validateEditor(draft) == null,
                        cornerRadius = 24.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(stringResource(R.string.assist_save)) }
                }
            }
        }
    }

    // The confirmation is a separate window, so a clipboard read there would be refused. After the
    // confirmation closes, wait for this window's focus to return, then verify the clip is still the
    // one the user confirmed before clearing it.
    suspend fun clearConfirmedClipboard(pending: PendingConfirm) {
        if (!awaitClipboardWindow(context, view)) {
            notice = R.string.assist_clear_failed
            return
        }
        val latest = reader.read(true)
        if (latest != pending.clip) {
            clipboardRead = latest
            revealed = false
            notice = R.string.assist_changed
            return
        }
        try {
            val manager = context.getSystemService(ClipboardManager::class.java)
                ?: throw IllegalStateException("Clipboard manager unavailable")
            manager.clearPrimaryClip()
            clipboardRead = ClipboardRead.Empty
            revealed = false
            notice = R.string.assist_cleared_clipboard
        } catch (failure: Exception) {
            notice = R.string.assist_clear_failed
        }
    }

    val currentConfirm = confirm
    if (currentConfirm != null) {
        AssistConfirmDialog(
            confirm = currentConfirm,
            scrimColor = scrimColor,
            cancelFocus = confirmCancelFocus,
            onCancel = { confirm = null },
            onConfirm = {
                confirm = null
                when (currentConfirm.kind) {
                    ConfirmKind.ClearRoot -> Unit // Owned by RootClipboardTab.
                    ConfirmKind.ClearClipboard -> scope.launch { clearConfirmedClipboard(currentConfirm) }
                    ConfirmKind.ClearPhrases -> scope.launch {
                        when (withContext(NonCancellable) { phrases.clearAll(currentConfirm.revision) }) {
                            PhraseWriteResult.SAVED -> {
                                undo = null
                                notice = R.string.assist_cleared_phrases
                            }
                            PhraseWriteResult.CONFLICT -> notice = R.string.assist_changed
                            else -> notice = R.string.assist_clear_failed
                        }
                    }
                    ConfirmKind.DiscardBack -> unlessEditor(closePanel = false)
                    ConfirmKind.DiscardClose -> unlessEditor(closePanel = true)
                }
            },
        )
    }
}

@Composable
private fun AssistHeader(
    mode: AssistMode,
    tab: AssistTab,
    editorTitle: Int,
    onTab: (AssistTab) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == AssistMode.List) {
            Row(Modifier.weight(1f).height(48.dp).selectableGroup()) {
                AssistTabButton(R.string.assist_tab_clipboard, tab == AssistTab.Clipboard, "assist-tab-clipboard") {
                    onTab(AssistTab.Clipboard)
                }
                AssistTabButton(R.string.assist_tab_phrases, tab == AssistTab.Phrases, "assist-tab-phrases") {
                    onTab(AssistTab.Phrases)
                }
            }
            if (tab == AssistTab.Phrases) {
                IconButton(onAdd, Modifier.size(48.dp).testTag("assist-add")) {
                    Icon(VoiceLinkIcon.Plus.vector, stringResource(R.string.assist_add_phrase), Modifier.size(22.dp))
                }
            }
        } else {
            IconButton(onBack, Modifier.size(48.dp).testTag("assist-back")) {
                Icon(VoiceLinkIcon.ArrowLeft.vector, stringResource(R.string.assist_back), Modifier.size(22.dp))
            }
            Text(
                stringResource(if (mode == AssistMode.Editor) editorTitle else R.string.assist_preview_title),
                Modifier.weight(1f).padding(horizontal = 8.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClose, Modifier.size(48.dp).testTag("assist-close")) {
            Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.assist_close), Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AssistTabButton(label: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val color = MiuixTheme.colorScheme
    val underline = 3.dp
    val inset = 10.dp
    Box(
        Modifier.height(48.dp).widthIn(min = 64.dp).padding(horizontal = 2.dp)
            .drawBehind {
                if (selected) {
                    val insetPx = inset.toPx()
                    drawRect(
                        color.primary,
                        topLeft = Offset(insetPx, size.height - underline.toPx()),
                        size = Size((size.width - insetPx * 2).coerceAtLeast(0f), underline.toPx()),
                    )
                }
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(label),
            modifier = Modifier.padding(horizontal = inset),
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) color.primary else color.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ClipboardTab(
    read: ClipboardRead?,
    loading: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    onRetry: () -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
    onSaveAsPhrase: (String) -> Unit,
    onView: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
    ) {
        when (val value = read) {
            null -> {
                if (loading) AssistLoading(R.string.assist_reading_clipboard)
            }
            ClipboardRead.Empty -> AssistEmpty(
                R.string.assist_clipboard_empty_title, R.string.assist_clipboard_empty_help,
                tag = "assist-clipboard-empty",
            )
            ClipboardRead.NoText -> AssistEmpty(
                R.string.assist_clipboard_notext_title, R.string.assist_clipboard_notext_help,
                tag = "assist-clipboard-notext",
            )
            ClipboardRead.TooLong -> AssistEmpty(
                R.string.assist_clipboard_toolong_title, R.string.assist_clipboard_toolong_help,
                tag = "assist-clipboard-toolong",
            )
            ClipboardRead.Unavailable -> AssistEmpty(
                R.string.assist_clipboard_unavailable_title, R.string.assist_clipboard_unavailable_help,
                action = R.string.assist_retry,
                tag = "assist-clipboard-unavailable",
                onAction = onRetry,
            )
            is ClipboardRead.Invalid -> AssistEmpty(
                R.string.assist_clipboard_invalid_title,
                when (value.reason) {
                    ClipboardInvalidReason.TOO_MANY_CODE_POINTS, ClipboardInvalidReason.TOO_MANY_BYTES -> R.string.assist_invalid_too_long
                    ClipboardInvalidReason.UNSUPPORTED_CHARACTERS -> R.string.assist_invalid_characters
                    ClipboardInvalidReason.MALFORMED_UNICODE -> R.string.assist_invalid_unicode
                },
                tag = "assist-clipboard-invalid",
            )
            is ClipboardRead.Text -> {
                if (value.sensitive && !revealed) {
                    SensitiveClipboardRow(onReveal)
                } else {
                    AssistRow(
                        text = value.text,
                        multiple = value.multiple,
                        onInsert = { onInsert(value.text) },
                        menuItems = buildList {
                            add(DropdownItem(stringResource(R.string.assist_copy), onClick = { onCopy(value.text) }))
                            add(DropdownItem(stringResource(R.string.assist_paste), onClick = { onPaste(value.text) }))
                            add(DropdownItem(stringResource(R.string.assist_view_full), onClick = { onView(value.text) }))
                            if (!value.sensitive) {
                                add(DropdownItem(stringResource(R.string.assist_save_as_phrase), onClick = { onSaveAsPhrase(value.text) }))
                            }
                        },
                        tag = "assist-clipboard-row",
                    )
                }
            }
        }
    }
}

@Composable
private fun SensitiveClipboardRow(onReveal: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(8.dp).testTag("assist-clipboard-sensitive"),
    ) {
        Text(
            stringResource(R.string.assist_sensitive_hidden),
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            stringResource(R.string.assist_show),
            onReveal,
            Modifier.heightIn(min = 48.dp).testTag("assist-show-sensitive"),
            minHeight = 48.dp,
            colors = buttonTextColors(),
        )
    }
}

@Composable
private fun PhraseTab(
    phrases: List<StoredPhrase>,
    ready: Boolean,
    issue: Boolean,
    emptyAction: () -> Unit,
    listState: LazyListState,
    reorderEnabled: Boolean,
    onInsert: (String) -> Unit,
    onCopy: (StoredPhrase) -> Unit,
    onPaste: (StoredPhrase) -> Unit,
    onView: (StoredPhrase) -> Unit,
    onEdit: (StoredPhrase) -> Unit,
    onDelete: (StoredPhrase) -> Unit,
    onMove: (String, String) -> Unit,
    onGripDown: (String) -> Unit,
    onGripEnd: (released: Boolean) -> Unit,
    onGripKey: (String, Int) -> Unit,
    onGripFocuser: (String, FocusRequester) -> Unit,
) {
    if (issue) {
        AssistEmpty(R.string.assist_library_issue, null, tag = "assist-library-issue")
        return
    }
    if (!ready) {
        AssistLoading(R.string.assist_loading)
        return
    }
    if (phrases.isEmpty()) {
        AssistEmpty(
            R.string.assist_no_phrases, R.string.assist_no_phrases_help,
            action = R.string.assist_add_phrase, tag = "assist-phrases-empty", onAction = emptyAction,
        )
        return
    }
    val reorderState = rememberReorderableLazyListState(listState, scrollThreshold = 48.dp) { from, to ->
        onMove(from.key as String, to.key as String)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("assist-phrase-list"),
        contentPadding = PaddingValues(8.dp),
    ) {
        itemsIndexed(phrases, key = { _, phrase -> phrase.id }) { index, phrase ->
            ReorderableItem(reorderState, key = phrase.id) { dragging ->
                // Reorderable calls onDragStopped for both release and cancellation (including
                // disposal). Only a real pointer-up may persist the preview order.
                var released by remember { mutableStateOf(false) }
                val gestureDetector = remember {
                    DragGestureDetector { start, end, cancel, drag ->
                        detectVerticalDragGestures(
                            onDragStart = { released = false; start(it) },
                            onDragEnd = { released = true; end() },
                            onDragCancel = cancel,
                            onVerticalDrag = { change, amount -> drag(change, Offset(0f, amount)) },
                        )
                    }
                }
                PhraseRow(
                    phrase = phrase,
                    index = index,
                    total = phrases.size,
                    dragging = dragging,
                    onInsert = { onInsert(phrase.text) },
                    menuItems = listOf(
                        DropdownItem(stringResource(R.string.assist_copy), onClick = { onCopy(phrase) }),
                        DropdownItem(stringResource(R.string.assist_paste), onClick = { onPaste(phrase) }),
                        DropdownItem(stringResource(R.string.assist_view_full), onClick = { onView(phrase) }),
                        DropdownItem(stringResource(R.string.assist_edit), onClick = { onEdit(phrase) }),
                        DropdownItem(stringResource(R.string.assist_delete), onClick = { onDelete(phrase) }),
                    ),
                    gripModifier = Modifier.draggableHandle(
                        enabled = reorderEnabled,
                        dragGestureDetector = gestureDetector,
                        onDragStarted = { onGripDown(phrase.id) },
                        onDragStopped = { onGripEnd(released) },
                    ),
                    onGripKey = { delta -> onGripKey(phrase.id, delta) },
                    onGripFocuser = { requester -> onGripFocuser(phrase.id, requester) },
                )
            }
        }
    }
}

@Composable
private fun PhraseRow(
    phrase: StoredPhrase,
    index: Int,
    total: Int,
    dragging: Boolean,
    onInsert: () -> Unit,
    menuItems: List<DropdownItem>,
    gripModifier: Modifier,
    onGripKey: (Int) -> Unit,
    onGripFocuser: (FocusRequester) -> Unit,
) {
    val reorderDescription = stringResource(R.string.assist_reorder_for, phrase.text)
    val positionDescription = stringResource(R.string.assist_moved, index + 1, total)
    val moveUp = stringResource(R.string.assist_move_up)
    val moveDown = stringResource(R.string.assist_move_down)
    val gripFocus = remember(phrase.id) { FocusRequester() }
    val shape = RoundedCornerShape(16.dp)
    val elevation by animateDpAsState(
        if (dragging) 6.dp else 0.dp,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .shadow(elevation, shape)
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val reorderLabel = stringResource(R.string.assist_reorder)
        Box(
            Modifier.size(48.dp).focusRequester(gripFocus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { onGripKey(-1); true }
                        Key.DirectionDown -> { onGripKey(1); true }
                        else -> false
                    }
                }
                .then(gripModifier)
                .semantics {
                    contentDescription = reorderDescription
                    role = Role.Button
                    stateDescription = positionDescription
                    customActions = listOf(
                        CustomAccessibilityAction(moveUp) { onGripKey(-1); true },
                        CustomAccessibilityAction(moveDown) { onGripKey(1); true },
                    )
                }
                .testTag("assist-phrase-grip"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                VoiceLinkIcon.GripVertical.vector,
                reorderLabel,
                Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        AssistRowBody(
            text = phrase.text,
            multiple = false,
            onInsert = onInsert,
            modifier = Modifier.weight(1f).testTag("assist-phrase-row"),
            menuItems = menuItems,
            menuTag = "assist-phrase-menu",
        )
        LaunchedEffect(gripFocus) { onGripFocuser(gripFocus) }
    }
}

@Composable
internal fun AssistRow(
    text: String,
    multiple: Boolean,
    onInsert: () -> Unit,
    menuItems: List<DropdownItem>,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistRowBody(
            text = text,
            multiple = multiple,
            onInsert = onInsert,
            modifier = Modifier.weight(1f).testTag(tag),
            menuItems = menuItems,
            menuTag = "assist-clipboard-menu",
        )
    }
}

@Composable
private fun AssistRowBody(
    text: String,
    multiple: Boolean,
    onInsert: () -> Unit,
    modifier: Modifier,
    menuItems: List<DropdownItem>,
    menuTag: String,
) {
    val insertLabel = stringResource(R.string.assist_insert_action)
    val moreLabel = stringResource(R.string.assist_more_for, text)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClickLabel = insertLabel, role = Role.Button, onClick = onInsert)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (multiple) Text(
            stringResource(R.string.assist_multiple_note),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1,
        )
    }
    // The same Miuix icon-dropdown the page's overflow button uses, so the menu motion matches.
    WindowIconDropdownMenu(
        DropdownEntry(menuItems),
        Modifier.size(48.dp).testTag(menuTag).semantics { contentDescription = moreLabel },
        minHeight = 48.dp, minWidth = 48.dp,
    ) {
        Icon(VoiceLinkIcon.More.vector, null, Modifier.size(22.dp))
    }
}

@Composable
private fun EditorBody(
    draft: EditorDraft,
    error: Int?,
    rejection: Int?,
    saving: Boolean,
    focus: FocusRequester,
    onText: (String) -> Unit,
    onSave: () -> Unit,
) {
    val shownError = rejection ?: error
    val counter = with(draft.text) { codePointCount(0, length) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.assist_phrase_label), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = draft.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus)
                .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.background)
                .padding(12.dp).testTag("assist-editor-field"),
            textStyle = MiuixTheme.textStyles.main.copy(fontSize = 16.sp, lineHeight = 24.sp, color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    if (draft.text.isEmpty()) Text(
                        stringResource(R.string.assist_phrase_placeholder),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shownError?.let { stringResource(it) }.orEmpty(),
                Modifier.weight(1f).testTag("assist-editor-error").semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 12.sp,
                color = if (shownError != null) errorColor() else MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 3,
            )
            Text(
                stringResource(R.string.assist_counter, counter, PhraseRules.LIMIT_CODE_POINTS),
                Modifier.testTag("assist-editor-counter"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        LaunchedEffect(focus) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
}

@Composable
private fun ViewerBody(text: String) {
    Text(
        text,
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("assist-viewer-text"),
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}

@Composable
private fun AssistFooter(
    tab: AssistTab,
    issue: Boolean,
    ready: Boolean,
    clipboardRead: ClipboardRead?,
    phraseCount: Int,
    undo: PhraseUndo?,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        undo?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.assist_deleted),
                    Modifier.weight(1f).testTag("assist-undo-notice"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                TextButton(
                    stringResource(R.string.assist_undo),
                    onUndo,
                    Modifier.heightIn(min = 48.dp).testTag("assist-undo-delete"),
                    minHeight = 48.dp,
                    colors = buttonTextColors(),
                )
            }
        }
        val enabled = when (tab) {
            AssistTab.Clipboard -> clipboardRead is ClipboardRead.Text || clipboardRead == ClipboardRead.NoText
            AssistTab.Phrases -> ready && !issue && phraseCount > 0
        }
        AssistClearAction(enabled, "assist-clear", onClear)
    }
}

@Composable
private fun AssistConfirmDialog(
    confirm: PendingConfirm,
    scrimColor: Color,
    cancelFocus: FocusRequester,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelLabel = if (confirm.kind == ConfirmKind.ClearClipboard || confirm.kind == ConfirmKind.ClearPhrases || confirm.kind == ConfirmKind.ClearRoot) R.string.assist_cancel else R.string.assist_discard_keep
    val confirmLabel = if (confirm.kind == ConfirmKind.ClearClipboard || confirm.kind == ConfirmKind.ClearPhrases || confirm.kind == ConfirmKind.ClearRoot) R.string.assist_clear else R.string.assist_discard_confirm
    val title = when (confirm.kind) {
        ConfirmKind.ClearClipboard -> R.string.assist_clear_clipboard_title
        ConfirmKind.ClearRoot -> R.string.root_clipboard_clear_title
        ConfirmKind.ClearPhrases -> R.string.assist_clear_phrases_title
        else -> R.string.assist_discard_title
    }
    val message = when (confirm.kind) {
        ConfirmKind.ClearClipboard -> stringResource(R.string.assist_clear_clipboard_text)
        ConfirmKind.ClearRoot -> stringResource(R.string.root_clipboard_clear_message, confirm.count)
        ConfirmKind.ClearPhrases -> stringResource(R.string.assist_clear_phrases_text, confirm.count)
        else -> stringResource(R.string.assist_discard_text)
    }
    val dialogMaxHeight = (LocalWindowInfo.current.containerDpSize.height - 48.dp).coerceAtLeast(200.dp)
    var cancelFocused by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(scrimColor)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.padding(24.dp).widthIn(max = 320.dp).heightIn(max = dialogMaxHeight).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {},
                ),
                cornerRadius = 24.dp,
                insideMargin = PaddingValues(24.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(message, fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onCancel,
                            Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(cancelFocus)
                                .onFocusChanged { cancelFocused = it.isFocused }.focusable()
                                .testTag("assist-confirm-cancel"),
                            cornerRadius = 24.dp,
                            insideMargin = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(),
                        ) { Text(stringResource(cancelLabel)) }
                        Button(
                            onConfirm,
                            Modifier.weight(1f).heightIn(min = 48.dp).testTag("assist-confirm-action"),
                            cornerRadius = 24.dp,
                            insideMargin = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                color = if (MiuixTheme.colorScheme.background == YuePalette.Neutral100) YuePalette.ErrorContainerLight else YuePalette.ErrorContainerDark,
                                contentColor = errorColor(),
                            ),
                        ) { Text(stringResource(confirmLabel), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            // The dialog window takes input focus asynchronously; keep the safe action focused.
            LaunchedEffect(Unit) {
                var attempts = 0
                while (!cancelFocused && attempts++ < 60) {
                    withFrameNanos { }
                    runCatching { cancelFocus.requestFocus() }
                    kotlinx.coroutines.delay(16)
                }
            }
        }
    }
}

@Composable
private fun AssistEmpty(title: Int, help: Int?, action: Int? = null, tag: String, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(24.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 3)
        if (help != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(help),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 4,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onAction,
                Modifier.heightIn(min = 48.dp).testTag("assist-empty-action"),
                cornerRadius = 24.dp,
                insideMargin = PaddingValues(horizontal = 20.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun AssistLoading(label: Int) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp).testTag("assist-loading"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
        Text(stringResource(label), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun errorColor(): Color = if (MiuixTheme.colorScheme.background == YuePalette.Neutral100) YuePalette.ErrorLight else YuePalette.ErrorDark

@Composable
private fun buttonTextColors() = ButtonDefaults.textButtonColors(
    color = Color.Transparent,
    textColor = MiuixTheme.colorScheme.primary,
)

/** Unwraps the Activity that owns the visible window, if any. */
private fun Context.hostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun clipboardWindowFocused(context: Context, fallback: View): Boolean {
    val decor = context.hostActivity()?.window?.decorView ?: fallback
    return decor.hasWindowFocus()
}

private suspend fun awaitClipboardWindow(context: Context, view: View): Boolean {
    val started = SystemClock.elapsedRealtime()
    while (SystemClock.elapsedRealtime() - started < 2_000L) {
        if (clipboardWindowFocused(context, view) && !imeVisible(context, view)) return true
        withFrameNanos { }
    }
    return clipboardWindowFocused(context, view) && !imeVisible(context, view)
}

private fun imeVisible(context: Context, fallback: View): Boolean {
    val decor = context.hostActivity()?.window?.decorView ?: fallback
    return ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

@Composable
internal fun RootClearConfirmation(count: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scrim = YuePalette.Neutral1000.copy(alpha = 0.35f)
    AssistConfirmDialog(PendingConfirm(ConfirmKind.ClearRoot, count = count), scrim,
        remember { FocusRequester() }, onCancel, onConfirm)
}
