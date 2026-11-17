package seeyuer.sayin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.sayin.R
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private enum class HistoryFilter(val label: Int) {
    All(R.string.history_all), Submitted(R.string.history_submitted), Review(R.string.history_attention)
}

@Composable
internal fun MobileHistoryPage(state: ClientState, action: (String, String) -> Unit,
    loadImage: suspend (String, Int) -> Bitmap? = { _, _ -> null }) {
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val rows = state.history.filter { item ->
        !item.historyHidden && (filter == HistoryFilter.All || filter == HistoryFilter.Submitted && item.state == "injected" ||
            filter == HistoryFilter.Review && item.state in setOf("saved", "uploading", "accepted", "held", "executing", "review", "uncertain", "failed")) &&
            (search.text.isBlank() || (state.historyText[item.id].orEmpty() + " " + state.historyApplications[item.id]?.name.orEmpty() + " " + item.id)
                .contains(search.text.trim(), ignoreCase = true))
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val groups = rows.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
    LazyColumn(Modifier.fillMaxSize().testTag("history-list"), contentPadding = PaddingValues(20.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("search") {
            TextField(search, { search = it }, Modifier.fillMaxWidth().testTag("history-search"),
                label = stringResource(R.string.history_search), singleLine = true)
        }
        item("filters") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { item ->
                    HistoryFilterChip(
                        label = stringResource(item.label),
                        selected = filter == item,
                        onClick = { filter = item },
                        modifier = Modifier.testTag("history-filter-${item.name}"),
                    )
                }
            }
        }
        if (rows.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(VoiceLinkIcon.History.vector, null, Modifier.size(40.dp))
                Text(stringResource(if (state.history.none { !it.historyHidden }) R.string.mobile_history_empty else R.string.history_no_results))
            }
        }
        groups.forEach { (day, entries) ->
            item("day-$day") {
                Text(when (day) { today -> stringResource(R.string.history_today); today.minusDays(1) -> stringResource(R.string.history_yesterday)
                    else -> dateFormat.format(Date(entries.first().createdAt)) },
                    Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            items(entries, key = { it.id }) { item ->
                HistoryCard(item, state, timeFormat.format(Date(item.createdAt)), loadImage,
                    onDetails = { detailId = item.id }, onDelete = { deleteId = item.id }, action = action)
            }
        }
        item("storage") { Caption(stringResource(R.string.history_local_storage)) }
    }
    val detail = state.history.firstOrNull { it.id == detailId && !it.historyHidden }
    OverlayBottomSheet(show = detail != null, onDismissRequest = { detailId = null }) {
        if (detail != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_details), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (detail.kind == "image") HistoryThumbnail(detail.id, loadImage, large = true, onClick = {})
            else Text(state.historyText[detail.id] ?: stringResource(R.string.history_text_unavailable), fontSize = 18.sp, lineHeight = 28.sp)
            Text(stringResource(historyStatus(detail)))
            if (detail.state == "injected") Caption(stringResource(R.string.history_submitted_help))
            HistoryApplicationRow(state.historyApplications[detail.id])
            Caption(dateFormat.format(Date(detail.createdAt)) + " " + timeFormat.format(Date(detail.createdAt)))
            Caption(stringResource(R.string.operation_label, detail.id))
            HistoryRecovery(detail, state, action)
        }
    }
    OverlayBottomSheet(show = deleteId != null, onDismissRequest = { deleteId = null }) {
        if (deleteId != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_delete), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_delete_help))
            Action(R.string.history_delete, onClick = { deleteId?.let { action(it, "delete") }; deleteId = null; detailId = null })
            Action(R.string.assist_cancel, onClick = { deleteId = null })
        }
    }
}

private fun historyStatus(item: OutboxItem): Int = when {
    item.state == "uploading" -> R.string.image_uploading
    item.kind == "image" && item.state == "injected" -> R.string.history_image_submitted
    else -> receiptText(item.state)
}

@Composable
private fun HistoryCard(item: OutboxItem, state: ClientState, time: String, loadImage: suspend (String, Int) -> Bitmap?,
    onDetails: () -> Unit, onDelete: () -> Unit, action: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(item.id) { mutableStateOf(false) }
    var copying by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val tone = when (item.state) {
        "injected" -> if (dark) YuePalette.SuccessDark else YuePalette.SuccessLight
        "uncertain", "review", "failed" -> if (dark) YuePalette.ErrorDark else YuePalette.ErrorLight
        "saved", "uploading", "accepted", "held", "executing" -> if (dark) YuePalette.WarningDark else YuePalette.WarningLight
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    val toneBackground = when (item.state) {
        "injected" -> if (dark) YuePalette.SuccessContainerDark else YuePalette.SuccessContainerLight
        "uncertain", "review", "failed" -> if (dark) YuePalette.ErrorContainerDark else YuePalette.ErrorContainerLight
        "saved", "uploading", "accepted", "held", "executing" -> if (dark) YuePalette.WarningContainerDark else YuePalette.WarningContainerLight
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val hasFooterAction = item.kind == "image" || state.historyText[item.id] != null
    Panel(insideMargin = PaddingValues(
        start = 16.dp, end = 16.dp, top = 12.dp,
        bottom = if (hasFooterAction) 0.dp else 12.dp,
    )) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(toneBackground).padding(8.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon((if (item.state == "injected") VoiceLinkIcon.Check else if (item.state in setOf("uncertain", "review", "failed")) VoiceLinkIcon.Alert else VoiceLinkIcon.History).vector,
                    null, Modifier.size(14.dp), tint = tone)
                Text(stringResource(historyStatus(item)), fontSize = 12.sp, color = tone, fontWeight = FontWeight.Medium)
            }
            }
            Text(time, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            WindowIconDropdownMenu(DropdownEntry(listOf(
                DropdownItem(stringResource(R.string.history_details), onClick = onDetails),
                DropdownItem(stringResource(R.string.history_delete), onClick = onDelete))),
                Modifier.size(48.dp).testTag("history-more-${item.id}"), minHeight = 48.dp, minWidth = 48.dp) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Icon(VoiceLinkIcon.More.vector, stringResource(R.string.history_details), Modifier.size(20.dp))
                }
            }
        }
        if (item.kind == "image") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HistoryThumbnail(item.id, loadImage, onClick = onDetails)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.image_history), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                state.historyImages[item.id]?.let { metadata -> Caption(stringResource(R.string.image_metadata, metadata.width, metadata.height, (metadata.bytes + 1023) / 1024)) }
            }
        } else Text(state.historyText[item.id] ?: stringResource(R.string.history_text_unavailable), fontSize = 17.sp, lineHeight = 26.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("history-text-${item.id}"))
        if (item.state != "injected") HistoryRecovery(item, state, action)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { HistoryApplicationRow(state.historyApplications[item.id]) }
            val text = state.historyText[item.id]
            if (item.kind == "image" || text != null) IconButton(onClick = {
                if (!copying) scope.launch {
                    copying = true
                    val clip = withContext(Dispatchers.IO) { runCatching {
                        if (item.kind == "image") ClipData.newUri(context.contentResolver, "SayIn image", HistoryImages.uri(context, item.id))
                        else ClipData.newPlainText("SayIn", text)
                    }.getOrNull() }
                    if (clip != null) {
                        copied = runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }.isSuccess
                        if (!copied) Toast.makeText(context, R.string.history_copy_failed, Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, R.string.history_image_unavailable, Toast.LENGTH_SHORT).show()
                    copying = false
                }
            }, modifier = Modifier.size(48.dp).testTag("history-copy-${item.id}")) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Icon((if (copied) VoiceLinkIcon.Check else VoiceLinkIcon.Copy).vector,
                        stringResource(if (copied) R.string.history_copied else R.string.history_copy), Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryApplicationRow(application: ForegroundApplication?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (application?.icon != null) Image(application.icon.asImageBitmap(), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
        else Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(application?.name ?: stringResource(R.string.history_target_unknown), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryThumbnail(id: String, load: suspend (String, Int) -> Bitmap?, large: Boolean = false, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, id, large) { value = load(id, if (large) 1600 else 256) }
    val size = if (large) Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp) else Modifier.size(64.dp)
    Box(size.clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
        .testTag("history-image-$id"), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), stringResource(R.string.image_preview),
            if (large) Modifier.fillMaxWidth().heightIn(max = 360.dp) else Modifier.size(64.dp), contentScale = ContentScale.Fit)
        else Icon(VoiceLinkIcon.Photo.vector, stringResource(R.string.history_image_unavailable), Modifier.size(24.dp))
    }
}

@Composable
private fun HistoryRecovery(item: OutboxItem, state: ClientState, action: (String, String) -> Unit) {
    receiptHelp(item.state)?.let { help ->
        val cause = receiptCause(item.reason)
        if (cause != null && item.state in setOf("held", "expired")) Caption(stringResource(
            if (item.state == "expired") R.string.receipt_expired_cause_help else R.string.receipt_held_cause_help, stringResource(cause)))
        else Caption(stringResource(help))
    }
    if (item.state == "review") {
        Notice(stringResource(R.string.review_warning), VoiceLinkIcon.Alert)
        Action(R.string.speak_query, state.connection == Connection.ONLINE) { action(item.id, "query") }
        Action(R.string.discard_request) { action(item.id, "discard") }
    }
    if (item.state in setOf("saved", "accepted", "executing")) Action(R.string.speak_query, state.connection == Connection.ONLINE) { action(item.id, "query") }
    if (item.state == "held" && item.kind == "image") Caption(stringResource(R.string.image_held))
    if (item.state == "held" && item.kind != "image") Action(R.string.release_request, state.ready) { action(item.id, "release") }
    if (item.state in setOf("held", "accepted")) Action(R.string.cancel_request, state.connection == Connection.ONLINE) { action(item.id, "cancel") }
    if (item.state == "uncertain") Notice(stringResource(R.string.uncertain_warning), VoiceLinkIcon.Alert)
}

/** Pill-sized filter with tab selection semantics, not just color feedback. */
@Composable
private fun HistoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(24.dp)
    val borderColor = if (dark) YuePalette.Neutral600 else YuePalette.Neutral400
    Box(
        modifier.height(40.dp).clip(shape)
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier else Modifier.border(1.dp, borderColor, shape))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}
