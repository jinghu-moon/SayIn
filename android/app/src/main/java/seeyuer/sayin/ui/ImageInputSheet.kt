package seeyuer.sayin.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.ImageIssue
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** The photo is an explicit, single-use input intention, independent of speech.
 * Dismissal/backgrounding revokes it; reopening never resumes an old upload. */
@Composable
internal fun ImageInputSheet(
    show: Boolean, state: ClientState, inputAvailable: Boolean,
    onDismiss: () -> Unit, onSelect: (Uri) -> Unit, onSend: (String) -> Unit,
    onCancel: () -> Unit, onClear: () -> Unit,
) {
    val latestCancel by rememberUpdatedState(onCancel)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestShow by rememberUpdatedState(show)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && latestShow) latestSelect(uri)
    }
    DisposableEffect(show, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (show && event == Lifecycle.Event.ON_STOP) latestCancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (show) latestCancel() }
    }
    OverlayBottomSheet(show = show, onDismissRequest = onDismiss) {
        if (show) Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("image-input"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.image_title), fontSize = 20.sp)
            val draft = state.imageDraft
            val preview by produceState<ImageBitmap?>(null, draft) {
                value = if (draft == null) null else withContext(Dispatchers.Default) {
                    // Repository supplies a bounded 256 px thumbnail of the
                    // frozen normalized bytes, never the full decoded image.
                    BitmapFactory.decodeByteArray(draft.preview, 0, draft.preview.size)?.asImageBitmap()
                }
            }
            preview?.let { Image(it, stringResource(R.string.image_preview), Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
            if (draft != null) Caption(stringResource(R.string.image_metadata, draft.image.metadata.width,
                draft.image.metadata.height, (draft.image.metadata.bytes + 1023) / 1024))
            Caption(stringResource(R.string.image_normalization))
            Caption(stringResource(R.string.image_clipboard_notice))
            if (state.imagePreparing) {
                CircularProgressIndicator()
                Caption(stringResource(R.string.image_preparing))
            }
            if (state.imageBusy) {
                CircularProgressIndicator()
                Text(stringResource(if (state.imageSubmitted) R.string.image_awaiting_receipt else R.string.image_progress,
                    if (draft == null) 0 else (100L * state.imageProgress / draft.image.metadata.bytes).toInt()),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!state.imageSubmitted) Action(R.string.image_cancel_upload, onClick = onCancel)
            }
            state.imageRequest?.let { id -> state.history.firstOrNull { it.id == id } }?.let { item ->
                if (!state.imageBusy) {
                    Text(stringResource(if (item.state == "injected") R.string.image_paste_submitted else receiptText(item.state)), Modifier.testTag("image-receipt"))
                    if (item.state == "uncertain") Caption(stringResource(R.string.uncertain_warning))
                }
            }
            state.imageIssue?.let { issue -> Caption(stringResource(when (issue) {
                ImageIssue.INVALID -> R.string.image_invalid
                ImageIssue.NOT_READY -> R.string.image_not_ready
                ImageIssue.NOT_SUPPORTED -> R.string.image_unsupported
                ImageIssue.CONNECTION -> R.string.image_connection_error
                ImageIssue.STORAGE -> R.string.error_storage
                ImageIssue.QUOTA -> R.string.image_quota
                ImageIssue.CANCELLED -> R.string.image_cancelled
            })) }
            if (!state.storageHealthy) Caption(stringResource(R.string.error_storage))
            else if (!state.ready) Caption(stringResource(R.string.image_not_ready))
            else if (!state.imageSupported) Caption(stringResource(R.string.image_unsupported))
            Action(R.string.image_choose, !state.imageBusy && !state.imagePreparing && state.storageHealthy) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (draft != null) {
                Action(R.string.image_send, state.ready && state.imageSupported && !state.imageBusy && !state.imagePreparing && inputAvailable,
                    primary = true) { onSend(draft.image.resourceId) }
                Action(R.string.image_remove, !state.imageBusy && !state.imagePreparing, onClick = onClear)
            }
            Action(R.string.image_close, onClick = onDismiss)
        }
    }
}
