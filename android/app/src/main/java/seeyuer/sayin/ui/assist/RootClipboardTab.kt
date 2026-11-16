package seeyuer.sayin.ui.assist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import seeyuer.sayin.R
import seeyuer.sayin.data.RootClip
import seeyuer.sayin.data.RootClips
import seeyuer.sayin.data.RootClipboardClient
import seeyuer.sayin.data.RootClipboardAccess
import seeyuer.sayin.data.RootClipboardFailure
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalRootClipboardClient = staticCompositionLocalOf<RootClipboardAccess> { RootClipboardClient() }

/** Owned by the open panel, so viewer/editor navigation does not discard the loaded list. */
@Stable
internal class RootClipboardState {
    var snapshot by mutableStateOf<RootClips?>(null)
    var resolved by mutableStateOf(false)
    val listState = LazyListState()

    fun clear() {
        snapshot = null
        resolved = false
    }
}

/** A visible-window data source only. Full page and quick card use the same draft callbacks. */
@Composable
internal fun RootClipboardTab(
    onAvailable: (Boolean) -> Unit,
    onModal: (Boolean) -> Unit,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
    onSaveAsPhrase: (String) -> Unit,
    onView: (String) -> Unit,
    state: RootClipboardState = remember { RootClipboardState() },
    ordinary: @Composable () -> Unit,
) {
    val client = LocalRootClipboardClient.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var pendingClear by remember { mutableStateOf<RootClips?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    val availableCallback by rememberUpdatedState(onAvailable)
    val modalCallback by rememberUpdatedState(onModal)
    SideEffect { availableCallback(state.snapshot != null || !state.resolved); modalCallback(pendingClear != null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                resumed = false; epoch++; operation?.cancel(); operation = null
                state.snapshot = null; state.resolved = false; pendingClear = null; busy = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            epoch++; operation?.cancel(); pendingClear = null
            modalCallback(false); lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(resumed, refresh) {
        if (!resumed || state.resolved && refresh == 0) return@LaunchedEffect
        busy = true
        val generation = epoch
        try {
            val latest = client.list()
            if (resumed && generation == epoch) state.snapshot = latest
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (generation == epoch) {
                val wasAvailable = state.snapshot != null
                state.snapshot = null
                if (wasAvailable || refresh > 0 || failure is RootClipboardFailure) notice = rootClipboardMessage(failure)
            }
        } finally { if (generation == epoch) { state.resolved = true; busy = false } }
    }

    fun textAction(entry: RootClip, action: (String) -> Unit) {
        val current = state.snapshot ?: return
        if (busy || !resumed) return
        busy = true
        val generation = epoch
        operation = scope.launch {
            try {
                val text = client.get(current, entry.id)
                if (resumed && generation == epoch && state.snapshot == current) action(text)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == epoch) { state.snapshot = null; notice = rootClipboardMessage(failure) }
            } finally { if (generation == epoch) busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val current = state.snapshot
        if (!state.resolved && current == null) Box(Modifier.weight(1f).fillMaxWidth().testTag("root-clipboard-loading"), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        else if (current == null) Box(Modifier.weight(1f)) { ordinary() }
        else {
            if (!current.captureReady) Text(stringResource(R.string.root_clipboard_paused),
                Modifier.padding(horizontal = 16.dp), fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("root-clipboard-list"), state = state.listState, contentPadding = PaddingValues(8.dp)) {
                if (current.entries.isEmpty()) item {
                    Text(stringResource(R.string.root_clipboard_empty), Modifier.padding(16.dp).testTag("root-clipboard-empty"))
                }
                items(current.entries, key = { it.id }) { entry ->
                    AssistRow(
                        text = entry.preview, multiple = false,
                        onInsert = { textAction(entry, onInsert) },
                        menuItems = listOf(
                            DropdownItem(stringResource(R.string.assist_copy), onClick = { textAction(entry) { text -> onCopy(text); scope.launch { delay(500); if (resumed) refresh++ } } }),
                            DropdownItem(stringResource(R.string.assist_paste), onClick = { textAction(entry, onPaste) }),
                            DropdownItem(stringResource(R.string.assist_view_full), onClick = { textAction(entry, onView) }),
                            DropdownItem(stringResource(R.string.assist_save_as_phrase), onClick = { textAction(entry, onSaveAsPhrase) }),
                        ),
                        tag = "root-clipboard-${entry.id}",
                    )
                }
            }
        }
        notice?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(message), Modifier.weight(1f).testTag("root-clipboard-notice"),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                if (message != R.string.root_clipboard_cleared && !busy) {
                    TextButton(stringResource(R.string.assist_retry),
                        { notice = null; refresh++ },
                        Modifier.heightIn(min = 48.dp).testTag("root-clipboard-retry"), minHeight = 48.dp)
                }
            }
        }
        if (current != null) AssistClearAction(!busy && current.entries.isNotEmpty(), "root-clipboard-clear") {
            if (!busy) pendingClear = current
        }
    }
    pendingClear?.let { confirmed ->
        RootClearConfirmation(confirmed.entries.size, onCancel = { pendingClear = null }) {
            pendingClear = null
            if (!busy && resumed) {
                busy = true
                val generation = epoch
                operation = scope.launch {
                    try {
                        client.clear(confirmed)
                        if (generation == epoch && resumed) { state.snapshot = confirmed.copy(entries = emptyList()); notice = R.string.root_clipboard_cleared; refresh++ }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (generation == epoch) { state.snapshot = null; notice = rootClipboardMessage(failure) }
                    } finally { if (generation == epoch) busy = false }
                }
            }
        }
    }
}

private fun rootClipboardMessage(failure: Exception): Int = when ((failure as? RootClipboardFailure)?.code) {
    "client_not_authorized", "untrusted_server" -> R.string.root_clipboard_denied
    "locked_or_non_owner_user" -> R.string.root_clipboard_locked
    "stale_revision", "entry_missing" -> R.string.root_clipboard_changed
    else -> R.string.root_clipboard_unavailable
}
