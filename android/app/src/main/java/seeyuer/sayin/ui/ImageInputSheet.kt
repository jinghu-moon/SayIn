package seeyuer.sayin.ui

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.data.ImageIssue
import seeyuer.sayin.data.IMAGE_PATH_COPY_METHOD
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The photo is an explicit, single-use input intention, independent of speech.
 * Dismissal/backgrounding revokes it; reopening never resumes an old upload. */
@Composable
internal fun ImageInputSheet(
    show: Boolean, state: ClientState, inputAvailable: Boolean,
    onDismiss: () -> Unit, onSelect: (Uri) -> Unit, onSend: (String) -> Unit,
    onCancel: () -> Unit, onClear: () -> Unit, onPreview: (String) -> Unit = {},
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
    OverlayBottomSheet(
        insideMargin = AppSheetInsideMargin,
        show = show, onDismissRequest = onDismiss,
    ) {
        if (show) {
            val draft = state.imageDraft
            val preview by produceState<ImageBitmap?>(null, draft) {
                value = if (draft == null) null else withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(draft.preview, 0, draft.preview.size)?.asImageBitmap()
                }
            }
            var showInfo by remember { mutableStateOf(false) }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var canReadScreenshots by remember { mutableStateOf(hasScreenshotPermission(context)) }
            var latestShot by remember { mutableStateOf<Uri?>(null) }
            fun loadScreenshot(select: Boolean = false) {
                scope.launch {
                    val shot = withContext(Dispatchers.IO) { latestScreenshot(context) }
                    latestShot = shot
                    if (select && shot != null) onSelect(shot)
                }
            }
            val screenshotPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                canReadScreenshots = granted
                if (granted) loadScreenshot(select = true)
            }
            LaunchedEffect(show, canReadScreenshots) {
                if (show && canReadScreenshots) loadScreenshot()
                if (!show) latestShot = null
            }

            val metadata = draft?.image?.metadata
            val kb = metadata?.let { (it.bytes + 1023) / 1024 } ?: 0
            val mimeLabel = metadata?.mime?.wire?.substringAfter('/')?.uppercase().orEmpty()
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .testTag("image-input"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.image_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("image-close")) {
                        Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.image_close), Modifier.size(22.dp))
                    }
                }

                if (draft != null) {
                    val ratio = metadata!!.width.toFloat() / metadata.height
                    val maxWidth = 156f
                    val maxHeight = 104f
                    val frameWidth = if (ratio >= maxWidth / maxHeight) maxWidth else maxHeight * ratio
                    val frameHeight = if (ratio >= maxWidth / maxHeight) maxWidth / ratio else maxHeight
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (preview != null) {
                            Box(Modifier.width(frameWidth.dp).height(frameHeight.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { onPreview(draft.image.resourceId) }.testTag("image-preview-open")) {
                                Image(preview!!, stringResource(R.string.image_preview), Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop)
                            }
                        } else {
                            Box(Modifier.width(frameWidth.dp).height(frameHeight.dp).clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.image_history), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.image_dimensions, metadata!!.width, metadata.height), fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            Text(stringResource(R.string.image_size_mime, kb, mimeLabel), fontSize = 11.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(16.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(VoiceLinkIcon.Photo.vector, null, Modifier.size(30.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.image_empty_hint), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }

                when {
                    state.imagePreparing -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                        Text(stringResource(R.string.image_preparing), fontSize = 12.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    state.imageBusy -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                            Text(
                                stringResource(if (state.imageSubmitted) R.string.image_awaiting_receipt else R.string.image_progress,
                                    if (draft == null) 0 else (100L * state.imageProgress / draft.image.metadata.bytes).toInt()),
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                        if (!state.imageSubmitted) TextButton(stringResource(R.string.image_cancel_upload),
                            onClick = onCancel, minHeight = 44.dp, colors = plainTextButtonColors())
                    }
                    else -> {
                        val receipt = state.imageRequest?.let { id -> state.history.firstOrNull { it.id == id } }
                        val issue = state.imageIssue
                        when {
                            receipt != null && receipt.state == "injected" -> ImageStatus(
                                VoiceLinkIcon.Check, stringResource(if (receipt.method == IMAGE_PATH_COPY_METHOD)
                                    R.string.image_path_paste_help else R.string.image_paste_submitted),
                                successColor(), Modifier.testTag("image-receipt"),
                            )
                            receipt != null && receipt.state == "uncertain" -> ImageStatus(
                                VoiceLinkIcon.Alert, stringResource(receiptText(receipt.state)), errorColor(),
                            )
                            receipt != null && receipt.state == "failed" -> ImageStatus(
                                VoiceLinkIcon.Alert, stringResource(imageDeliveryFailure(receipt.reason)
                                    ?: receiptText(receipt.state)), errorColor(),
                            )
                            issue != null -> ImageStatus(VoiceLinkIcon.Alert, stringResource(when (issue) {
                                ImageIssue.INVALID -> R.string.image_invalid
                                ImageIssue.NOT_READY -> R.string.image_not_ready
                                ImageIssue.NOT_SUPPORTED -> R.string.image_unsupported
                                ImageIssue.CONNECTION -> R.string.image_connection_error
                                ImageIssue.STORAGE -> R.string.error_storage
                                ImageIssue.QUOTA -> R.string.image_quota
                                ImageIssue.CANCELLED -> R.string.image_cancelled
                            }), errorColor())
                            !state.storageHealthy -> ImageStatus(VoiceLinkIcon.Alert, stringResource(R.string.error_storage), errorColor())
                            !state.ready -> ImageStatus(VoiceLinkIcon.Alert, stringResource(R.string.image_not_ready), warningColor())
                            !state.imageSupported -> ImageStatus(VoiceLinkIcon.Alert, stringResource(R.string.image_unsupported), warningColor())
                            draft != null -> ImageStatus(VoiceLinkIcon.Hourglass, stringResource(R.string.image_ready_status),
                                MiuixTheme.colorScheme.primary, sub = stringResource(R.string.image_ready_sub))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { showInfo = !showInfo }
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.image_format_toggle), Modifier.weight(1f), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Icon(VoiceLinkIcon.ChevronDown.vector, null, Modifier.size(18.dp)
                        .rotate(if (showInfo) 180f else 0f), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                if (showInfo) Text(stringResource(R.string.image_normalization), fontSize = 11.5.sp,
                    lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)

                if (draft == null) {
                    Button(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.imageBusy && !state.imagePreparing && state.storageHealthy,
                        minHeight = 52.dp, cornerRadius = 16.dp, colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Icon(VoiceLinkIcon.Photo.vector, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.image_choose), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val shot = latestShot
                    if (shot != null) {
                        TextButton(
                            stringResource(R.string.image_use_screenshot), onClick = { onSelect(shot) },
                            enabled = !state.imageBusy && !state.imagePreparing,
                            minHeight = 44.dp, colors = plainTextButtonColors(), modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (!canReadScreenshots) {
                        TextButton(
                            stringResource(R.string.image_use_screenshot),
                            onClick = {
                                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    android.Manifest.permission.READ_MEDIA_IMAGES
                                } else android.Manifest.permission.READ_EXTERNAL_STORAGE
                                screenshotPermission.launch(permission)
                            },
                            enabled = !state.imageBusy && !state.imagePreparing,
                            minHeight = 44.dp, colors = plainTextButtonColors(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Button(
                        onClick = { onSend(draft.image.resourceId) },
                        modifier = Modifier.fillMaxWidth().testTag("image-send"),
                        enabled = state.ready && state.imageSupported && !state.imageBusy && !state.imagePreparing && inputAvailable,
                        minHeight = 52.dp, cornerRadius = 16.dp, colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Icon(VoiceLinkIcon.Send.vector, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.image_send), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val shape = RoundedCornerShape(14.dp)
                        Button(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).border(1.dp,
                                MiuixTheme.colorScheme.outline.copy(alpha = .55f), shape),
                            enabled = !state.imageBusy && !state.imagePreparing, minHeight = 48.dp,
                            cornerRadius = 14.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color.Transparent, disabledColor = Color.Transparent,
                                contentColor = MiuixTheme.colorScheme.onSurface,
                                disabledContentColor = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .45f),
                            ),
                        ) { Text(stringResource(R.string.image_change), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        Button(
                            onClick = onClear,
                            modifier = Modifier.weight(1f).border(1.dp,
                                MiuixTheme.colorScheme.error.copy(alpha = .32f), shape),
                            enabled = !state.imageBusy && !state.imagePreparing, minHeight = 48.dp,
                            cornerRadius = 14.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color.Transparent, disabledColor = Color.Transparent,
                                contentColor = MiuixTheme.colorScheme.error,
                                disabledContentColor = MiuixTheme.colorScheme.error.copy(alpha = .45f),
                            ),
                        ) { Text(stringResource(R.string.image_remove), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

        }
    }
}

@Composable
internal fun ImagePreviewDialog(
    imageId: String,
    loadImage: suspend (String, Int) -> android.graphics.Bitmap?,
    onDismiss: () -> Unit,
) {
    var image by remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(imageId) { mutableStateOf(false) }
    LaunchedEffect(imageId) {
        val loaded = withContext(Dispatchers.Default) { loadImage(imageId, 1600) }
        image = loaded?.asImageBitmap()
        failed = loaded == null
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val screen = LocalConfiguration.current
        Box(Modifier.requiredSize(screen.screenWidthDp.dp, screen.screenHeightDp.dp)
            .background(Color.Black).testTag("image-preview-dialog")) {
            val shown = image
            if (shown == null) {
                if (failed) Text(stringResource(R.string.image_preview_failed), Modifier.align(Alignment.Center),
                    color = Color.White, fontSize = 13.sp)
                else CircularProgressIndicator(Modifier.align(Alignment.Center), size = 28.dp, strokeWidth = 2.dp)
            } else Image(shown, stringResource(R.string.image_preview),
                Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next == 1f) Offset.Zero else offset + pan
                        }
                    },
                contentScale = ContentScale.Fit)
            IconButton(onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(48.dp)) {
                Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.image_close), Modifier.size(24.dp),
                    tint = Color.White)
            }
        }
    }
}

private fun hasScreenshotPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else android.Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun latestScreenshot(context: Context): Uri? = runCatching {
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DATE_ADDED,
    )
    context.contentResolver.query(
        collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        var scanned = 0
        while (cursor.moveToNext() && scanned < 80) {
            scanned++
            val name = cursor.getString(1).orEmpty()
            val path = cursor.getString(2).orEmpty()
            val haystack = "$name $path".lowercase()
            if ("screenshot" in haystack || "screencap" in haystack || "截屏" in haystack || "截图" in haystack) {
                return@use ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        null
    }
}.getOrNull()

@Composable
private fun ImageStatus(icon: VoiceLinkIcon, text: String, color: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .12f))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon.vector, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = color)
            sub?.let { Text(it, fontSize = 11.5.sp, color = color.copy(alpha = .82f)) }
        }
    }
}

@Composable
private fun successColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.SuccessDark else YuePalette.SuccessLight

@Composable
private fun warningColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.WarningDark else YuePalette.WarningLight

@Composable
private fun errorColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.ErrorDark else YuePalette.ErrorLight
