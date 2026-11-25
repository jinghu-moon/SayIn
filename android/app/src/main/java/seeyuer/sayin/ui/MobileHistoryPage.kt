package seeyuer.sayin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: Int) {
    All(R.string.history_all), Submitted(R.string.history_submitted), Review(R.string.history_attention)
}

private enum class HistoryDateFilter(val label: Int) {
    All(R.string.history_date_all), Today(R.string.history_date_today),
    Week(R.string.history_date_week), Month(R.string.history_date_month);

    fun cutoff(now: Long, zone: ZoneId): Long = when (this) {
        All -> Long.MIN_VALUE
        Today -> LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        Week -> now - 7L * 24 * 60 * 60 * 1000
        Month -> now - 30L * 24 * 60 * 60 * 1000
    }
}

private data class PendingExport(val name: String, val bytes: ByteArray)

@Composable
internal fun MobileHistoryPage(
    state: ClientState,
    action: (String, String) -> Unit,
    loadImage: suspend (String, Int) -> Bitmap? = { _, _ -> null },
    onHideHistory: (List<String>) -> Unit = {},
    onCleanupHistory: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var dateFilter by rememberSaveable { mutableStateOf(HistoryDateFilter.All) }
    var management by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var draftDate by remember { mutableStateOf(HistoryDateFilter.All) }
    var draftApp by remember { mutableStateOf<String?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var encryptedOpen by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf(false) }
    var cleanupOpen by remember { mutableStateOf(false) }
    var cleanupDays by remember { mutableIntStateOf(30) }
    var batchConfirm by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val dateCutoff = dateFilter.cutoff(now, zone)
    val appNames = remember(state.history, state.historyApplications) {
        state.history.filter { !it.historyHidden }
            .mapNotNull { state.historyApplications[it.id]?.name?.takeIf(String::isNotBlank) }
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(appNames) {
        if (appFilter != null && appFilter !in appNames) appFilter = null
    }
    fun filtered(status: HistoryFilter, app: String?, date: HistoryDateFilter, query: String): List<OutboxItem> {
        val cutoff = date.cutoff(now, zone)
        return state.history.filter { item ->
            !item.historyHidden && (status == HistoryFilter.All ||
                status == HistoryFilter.Submitted && item.state == "injected" ||
                status == HistoryFilter.Review && item.state in setOf("saved", "uploading", "accepted", "held", "executing", "review", "uncertain", "failed")) &&
                (app == null || state.historyApplications[item.id]?.name == app) &&
                item.createdAt >= cutoff &&
                (query.isBlank() || (state.historyText[item.id].orEmpty() + " " +
                    state.historyApplications[item.id]?.name.orEmpty() + " " + item.id)
                    .contains(query.trim(), ignoreCase = true))
        }
    }
    val rows = remember(filter, appFilter, dateFilter, search.text, state.history, state.historyApplications, state.historyText) {
        filtered(filter, appFilter, dateFilter, search.text)
    }
    val visibleIds = remember(rows) { rows.map { it.id }.toSet() }
    LaunchedEffect(visibleIds) { selected = selected.intersect(visibleIds) }
    val today = LocalDate.now(zone)
    val groups = rows.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
    val activeFilterCount = (if (dateFilter == HistoryDateFilter.All) 0 else 1) + if (appFilter != null) 1 else 0
    fun record(item: OutboxItem) = HistoryExportRecord(
        id = item.id, createdAt = item.createdAt, state = item.state, kind = item.kind,
        application = state.historyApplications[item.id]?.name,
        text = state.historyText[item.id].takeIf { item.kind == "text" },
        imageWidth = state.historyImages[item.id]?.width,
        imageHeight = state.historyImages[item.id]?.height,
        imageBytes = state.historyImages[item.id]?.bytes,
    )
    fun exportRecords() = rows.map(::record)
    fun stamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    fun startExport(name: String, content: ByteArray) {
        pendingExport = PendingExport(name, content)
    }
    val markdownExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        writeExport(context, scope, uri, pendingExport)
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        writeExport(context, scope, uri, pendingExport)
    }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        writeExport(context, scope, uri, pendingExport)
    }
    fun openFilterSheet() {
        draftDate = dateFilter
        draftApp = appFilter
        filterOpen = true
    }
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        SmallTopAppBar(
            title = "",
            modifier = Modifier.fillMaxWidth(),
            navigationIcon = {
                Text(stringResource(R.string.nav_history), fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            },
            actions = {
                Text(stringResource(R.string.history_count, rows.size), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Spacer(Modifier.width(4.dp))
                HistoryTopMenu(
                    enabled = rows.isNotEmpty(),
                    cleanupEnabled = state.history.any { !it.historyHidden },
                    onManage = { management = true; selected = emptySet() },
                    onExport = { exportOpen = true },
                    onCleanup = { cleanupDays = 30; cleanupOpen = true },
                )
            },
            navigationIconPadding = 20.dp,
            actionIconPadding = 12.dp,
            defaultWindowInsetsPadding = false,
            color = MiuixTheme.colorScheme.surface,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HistorySearchField(
                value = search,
                onValueChange = { search = it },
                filterCount = activeFilterCount,
                management = management,
                onFilter = ::openFilterSheet,
            )
            if (management) {
                HistoryBatchBar(
                    selected = selected.size,
                    total = visibleIds.size,
                    onSelectAll = {
                        selected = if (selected.size == visibleIds.size && visibleIds.isNotEmpty()) emptySet() else visibleIds
                    },
                    onDelete = { if (selected.isNotEmpty()) batchConfirm = true },
                    onDone = { management = false; selected = emptySet() },
                )
            } else {
                HistoryStatusTabs(filter) { filter = it }
            }
            if (!management && activeFilterCount > 0) {
                HistoryActiveFilters(
                    date = dateFilter.takeIf { it != HistoryDateFilter.All },
                    app = appFilter,
                    onRemoveDate = { dateFilter = HistoryDateFilter.All },
                    onRemoveApp = { appFilter = null },
                    onClear = { dateFilter = HistoryDateFilter.All; appFilter = null },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(VoiceLinkIcon.History.vector, null, Modifier.size(40.dp))
                    Text(stringResource(if (state.history.none { !it.historyHidden }) R.string.mobile_history_empty else R.string.history_no_results))
                }
            }
            groups.forEach { (day, entries) ->
                item("day-$day") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(when (day) {
                            today -> stringResource(R.string.history_today)
                            today.minusDays(1) -> stringResource(R.string.history_yesterday)
                            else -> dateFormat.format(Date(entries.first().createdAt))
                        }, Modifier.weight(1f), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text(stringResource(R.string.history_count, entries.size), fontSize = 11.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .8f))
                    }
                }
                items(entries, key = { it.id }) { item ->
                    HistoryCard(item, state, timeFormat.format(Date(item.createdAt)), loadImage,
                        onDetails = { detailId = item.id }, onDelete = { deleteId = item.id }, action = action,
                        management = management, selected = item.id in selected,
                        onSelected = { checked -> selected = if (checked) selected + item.id else selected - item.id })
                }
            }
            item("storage") { Caption(stringResource(R.string.history_local_storage)) }
        }
    }
    val detail = state.history.firstOrNull { it.id == detailId && !it.historyHidden }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = detail != null, onDismissRequest = { detailId = null }) {
        if (detail != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_details), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (detail.kind == "image") HistoryThumbnail(detail.id, loadImage, large = true, onClick = {})
            else Text(state.historyText[detail.id] ?: stringResource(R.string.history_text_unavailable), fontSize = 18.sp, lineHeight = 28.sp)
            Text(stringResource(historyStatus(detail)))
            if (detail.state == "injected") Caption(stringResource(if (detail.method == IMAGE_PATH_COPY_METHOD)
                R.string.image_path_paste_help else R.string.history_submitted_help))
            HistoryApplicationRow(state.historyApplications[detail.id])
            Caption(dateFormat.format(Date(detail.createdAt)) + " " + timeFormat.format(Date(detail.createdAt)))
            Caption(stringResource(R.string.operation_label, detail.id))
            HistoryRecovery(detail, state, action)
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = deleteId != null, onDismissRequest = { deleteId = null }) {
        if (deleteId != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_delete), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_delete_help))
            ConfirmCancelButtons(
                confirm = stringResource(R.string.history_confirm),
                onConfirm = { deleteId?.let { action(it, "delete") }; deleteId = null; detailId = null },
                onCancel = { deleteId = null },
                confirmTag = "history-confirm-delete",
            )
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = filterOpen, onDismissRequest = { filterOpen = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 680.dp)
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
            .testTag("history-filter-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_filter_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                IconButton({ filterOpen = false }, Modifier.size(40.dp)) {
                    Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.assist_cancel), Modifier.size(18.dp))
                }
            }
            Text(stringResource(R.string.history_filter_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Text(stringResource(R.string.history_filter_date), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            val dates = HistoryDateFilter.entries
            TabRowWithContour(
                tabs = dates.map { stringResource(it.label) },
                selectedTabIndex = dates.indexOf(draftDate).coerceAtLeast(0),
                onTabSelected = { draftDate = dates[it] },
                modifier = Modifier.fillMaxWidth().testTag("history-date-tabs"),
                colors = historyTabColors(track = true),
                height = 42.dp,
                cornerRadius = 10.dp,
            )
            Text(stringResource(R.string.history_filter_app), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Column {
                    val apps = listOf<String?>(null) + appNames
                    apps.forEachIndexed { index, app ->
                        Row(Modifier.fillMaxWidth().clickable { draftApp = app }
                            .testTag("history-app-${app ?: "all"}")
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (app != null) {
                                val icon = state.historyApplications.values.firstOrNull { it.name == app }?.icon
                                if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                                else Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                                Spacer(Modifier.width(9.dp))
                            } else {
                                Spacer(Modifier.width(29.dp))
                            }
                            Text(app ?: stringResource(R.string.history_app_all), Modifier.weight(1f), fontSize = 14.sp)
                            RadioButton(selected = draftApp == app, onClick = null, modifier = Modifier.size(26.dp))
                        }
                        if (index != apps.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
            val draftCount = filtered(filter, draftApp, draftDate, search.text).size
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.history_filter_reset), onClick = {
                    draftDate = HistoryDateFilter.All; draftApp = null
                }, modifier = Modifier.weight(1f), minHeight = 48.dp, colors = plainTextButtonColors())
                Button(onClick = {
                    dateFilter = draftDate; appFilter = draftApp; filterOpen = false
                }, modifier = Modifier.weight(2f).testTag("history-filter-apply"), minHeight = 48.dp,
                    cornerRadius = 14.dp, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(stringResource(R.string.history_filter_apply, draftCount))
                }
            }
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = exportOpen, onDismissRequest = { exportOpen = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_export_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_export_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Action(R.string.history_export_markdown, primary = true) {
                startExport("sayin-history-${stamp()}.md", HistoryExport.markdown(exportRecords()).toByteArray())
                exportOpen = false; markdownExport.launch(pendingExport!!.name)
            }
            Action(R.string.history_export_json) {
                startExport("sayin-history-${stamp()}.json", HistoryExport.json(exportRecords()).toByteArray())
                exportOpen = false; jsonExport.launch(pendingExport!!.name)
            }
            Action(R.string.history_export_encrypted) {
                exportOpen = false; passphrase = ""; passphraseError = false; encryptedOpen = true
            }
            TextButton(stringResource(R.string.assist_cancel), onClick = { exportOpen = false }, minHeight = 48.dp)
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = encryptedOpen, onDismissRequest = { encryptedOpen = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_export_encrypted_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_export_encrypted_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            TextField(passphrase, { passphrase = it; passphraseError = false }, Modifier.fillMaxWidth().testTag("history-passphrase"),
                label = stringResource(R.string.history_export_passphrase), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (passphraseError) Text(stringResource(R.string.history_export_passphrase_short), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.error)
            Action(R.string.history_export_encrypted_start, primary = true) {
                if (passphrase.length < 8) { passphraseError = true; return@Action }
                val records = exportRecords()
                scope.launch {
                    val secret = passphrase.toCharArray()
                    val content = try {
                        withContext(Dispatchers.Default) {
                            HistoryExport.encrypted(HistoryExport.json(records).toByteArray(), secret)
                        }
                    } finally {
                        secret.fill('\u0000')
                        passphrase = ""
                    }
                    startExport("sayin-history-${stamp()}.sayinbackup", content)
                    encryptedOpen = false
                    backupExport.launch(pendingExport!!.name)
                }
            }
            TextButton(stringResource(R.string.assist_cancel), onClick = { encryptedOpen = false }, minHeight = 48.dp)
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = batchConfirm, onDismissRequest = { batchConfirm = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_delete_selected_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_delete_multiple_help, selected.size))
            ConfirmCancelButtons(
                confirm = stringResource(R.string.history_confirm),
                onConfirm = {
                    onHideHistory(selected.toList())
                    batchConfirm = false; management = false; selected = emptySet()
                },
                onCancel = { batchConfirm = false },
                confirmTag = "history-confirm-delete",
            )
        }
    }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = cleanupOpen, onDismissRequest = { cleanupOpen = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_cleanup_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                IconButton({ cleanupOpen = false }, Modifier.size(40.dp)) {
                    Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.assist_cancel), Modifier.size(18.dp))
                }
            }
            Text(stringResource(R.string.history_cleanup_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Column {
                    val options = listOf(
                        7 to R.string.history_cleanup_week,
                        30 to R.string.history_cleanup_month,
                        90 to R.string.history_cleanup_quarter,
                        0 to R.string.history_cleanup_all,
                    )
                    options.forEachIndexed { index, (days, label) ->
                        Row(Modifier.fillMaxWidth().clickable { cleanupDays = days }
                            .testTag("history-cleanup-$days")
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), Modifier.weight(1f), fontSize = 14.sp)
                            RadioButton(selected = cleanupDays == days, onClick = null, modifier = Modifier.size(26.dp))
                        }
                        if (index != options.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
            ConfirmCancelButtons(
                confirm = stringResource(R.string.history_cleanup_confirm),
                onConfirm = {
                    val cutoff = if (cleanupDays == 0) now + 1 else now - cleanupDays * 24L * 60 * 60 * 1000
                    onCleanupHistory(cutoff)
                    cleanupOpen = false
                },
                onCancel = { cleanupOpen = false },
                confirmTag = "history-cleanup-confirm",
            )
        }
    }
}

@Composable
private fun HistorySearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    filterCount: Int,
    management: Boolean,
    onFilter: () -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag("history-search"),
        label = stringResource(R.string.history_search),
        useLabelAsPlaceholder = true,
        singleLine = true,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.text.isNotEmpty()) IconButton({ onValueChange(TextFieldValue()) }, Modifier.size(40.dp).testTag("history-clear-search")) {
                    Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.history_clear_search), Modifier.size(17.dp))
                }
                if (!management) Box(Modifier.size(40.dp)) {
                    IconButton({ onFilter() }, Modifier.fillMaxSize().testTag("history-filter-open")) {
                        Icon(VoiceLinkIcon.Filter.vector, stringResource(R.string.history_filter),
                            Modifier.size(18.dp), tint = if (filterCount > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    if (filterCount > 0) Box(Modifier.align(Alignment.TopEnd).size(16.dp)
                        .clip(CircleShape).background(MiuixTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text(filterCount.toString(), color = MiuixTheme.colorScheme.onPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    )
}

@Composable
private fun HistoryStatusTabs(selected: HistoryFilter, onSelected: (HistoryFilter) -> Unit) {
    val filters = HistoryFilter.entries
    TabRowWithContour(
        tabs = filters.map { stringResource(it.label) },
        selectedTabIndex = filters.indexOf(selected).coerceAtLeast(0),
        onTabSelected = { onSelected(filters[it]) },
        modifier = Modifier.fillMaxWidth().testTag("history-status-tabs"),
        colors = historyTabColors(track = true),
        height = 46.dp,
        cornerRadius = 14.dp,
    )
}

@Composable
private fun HistoryBatchBar(
    selected: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
        .background(MiuixTheme.colorScheme.primary).padding(start = 14.dp, end = 8.dp)
        .testTag("history-batch-bar"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.history_selected_count, selected), Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        TextButton(stringResource(if (selected == total && total > 0) R.string.history_select_none else R.string.history_select_all),
            onClick = onSelectAll, minHeight = 36.dp, colors = historyBatchTextColors(),
            modifier = Modifier.testTag("history-select-all"))
        TextButton(stringResource(R.string.history_delete_selected, selected), onClick = onDelete,
            enabled = selected > 0, minHeight = 36.dp, colors = historyBatchTextColors(),
            modifier = Modifier.testTag("history-delete-selected"))
        TextButton(stringResource(R.string.history_manage_cancel), onClick = onDone,
            minHeight = 36.dp, colors = historyBatchTextColors(),
            modifier = Modifier.testTag("history-batch-done"))
    }
}

@Composable
private fun HistoryActiveFilters(
    date: HistoryDateFilter?,
    app: String?,
    onRemoveDate: () -> Unit,
    onRemoveApp: () -> Unit,
    onClear: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        date?.let {
            ActiveFilterChip(stringResource(it.label), "history-active-date", onRemoveDate)
        }
        app?.let {
            ActiveFilterChip(it, "history-active-app", onRemoveApp)
        }
        TextButton(stringResource(R.string.history_filter_clear), onClick = onClear, minHeight = 32.dp,
            colors = plainTextButtonColors(), modifier = Modifier.testTag("history-clear-filters"))
    }
}

@Composable
private fun ActiveFilterChip(label: String, tag: String, onRemove: () -> Unit) {
    Row(Modifier.height(32.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
        .clickable(onClick = onRemove).padding(start = 12.dp, end = 8.dp)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.history_filter_remove, label), Modifier.size(13.dp),
            tint = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun HistoryTopMenu(
    enabled: Boolean,
    cleanupEnabled: Boolean,
    onManage: () -> Unit,
    onExport: () -> Unit,
    onCleanup: () -> Unit,
) {
    val colors = DropdownDefaults.dropdownColors(
        selectedContentColor = MiuixTheme.colorScheme.error,
        selectedSummaryColor = MiuixTheme.colorScheme.error,
        selectedContainerColor = MiuixTheme.colorScheme.error.copy(alpha = .08f),
        selectedIndicatorColor = Color.Transparent,
    )
    val entry = DropdownEntry(listOf(
        DropdownItem(
            text = stringResource(R.string.history_manage),
            enabled = enabled,
            onClick = onManage,
            icon = { modifier -> Icon(VoiceLinkIcon.Check.vector, null, modifier,
                tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
        ),
        DropdownItem(
            text = stringResource(R.string.history_export),
            enabled = enabled,
            onClick = onExport,
            icon = { modifier -> Icon(VoiceLinkIcon.Download.vector, null, modifier,
                tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
        ),
        DropdownItem(
            text = stringResource(R.string.history_cleanup),
            enabled = cleanupEnabled,
            selected = cleanupEnabled,
            onClick = onCleanup,
            icon = { modifier -> Icon(VoiceLinkIcon.Clear.vector, null, modifier,
                tint = if (cleanupEnabled) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary) },
        ),
    ))
    WindowIconDropdownMenu(
        entry = entry,
        modifier = Modifier.size(48.dp).testTag("history-top-more"),
        dropdownColors = colors,
        minWidth = 48.dp,
        minHeight = 48.dp,
        backgroundColor = Color.Transparent,
        cornerRadius = 12.dp,
    ) {
        Icon(VoiceLinkIcon.More.vector, stringResource(R.string.history_more), Modifier.size(22.dp))
    }
}

@Composable
private fun ConfirmCancelButtons(
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmTag: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            minHeight = 48.dp,
            cornerRadius = 14.dp,
            colors = ButtonDefaults.buttonColors(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            Text(stringResource(R.string.assist_cancel))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f).testTag(confirmTag),
            minHeight = 48.dp,
            cornerRadius = 14.dp,
            colors = ButtonDefaults.buttonColors(
                color = MiuixTheme.colorScheme.error,
                contentColor = MiuixTheme.colorScheme.onError,
            ),
        ) {
            Text(confirm)
        }
    }
}

@Composable
private fun historyTabColors(track: Boolean) = TabRowDefaults.tabRowColors(
    backgroundColor = if (track) MiuixTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surface,
    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
    selectedBackgroundColor = MiuixTheme.colorScheme.primary,
    selectedContentColor = MiuixTheme.colorScheme.onPrimary,
)

@Composable
private fun historyBatchTextColors() = ButtonDefaults.textButtonColors(
    color = Color.Transparent,
    disabledColor = Color.Transparent,
    textColor = MiuixTheme.colorScheme.onPrimary,
    disabledTextColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = .45f),
)

@Composable
private fun historyCheckboxColors() = CheckboxDefaults.checkboxColors(
    checkedBackgroundColor = MiuixTheme.colorScheme.primary,
    checkedForegroundColor = MiuixTheme.colorScheme.onPrimary,
    uncheckedBackgroundColor = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .18f),
    uncheckedForegroundColor = Color.Transparent,
)

private fun writeExport(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, uri: Uri?,
    pending: PendingExport?) {
    if (uri == null || pending == null) return
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                    ?: error("No output stream")
            }
        }
        Toast.makeText(context, if (result.isSuccess) R.string.history_export_done else R.string.history_export_failed,
            Toast.LENGTH_SHORT).show()
    }
}

private fun historyStatus(item: OutboxItem): Int = when {
    item.state == "uploading" -> R.string.image_uploading
    item.method == IMAGE_PATH_COPY_METHOD && item.state == "injected" -> R.string.image_path_copied
    item.kind == "image" && item.state == "injected" -> R.string.history_image_submitted
    else -> seeyuer.sayin.data.receiptPresentation(item).titleRes
}

@Composable
private fun HistoryCard(item: OutboxItem, state: ClientState, time: String, loadImage: suspend (String, Int) -> Bitmap?,
    onDetails: () -> Unit, onDelete: () -> Unit, action: (String, String) -> Unit,
    management: Boolean = false, selected: Boolean = false, onSelected: (Boolean) -> Unit = {}) {
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
    Box(Modifier.fillMaxWidth().then(
        if (management) Modifier.clickable { onSelected(!selected) } else Modifier
    )) {
    Panel(insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (management) Checkbox(ToggleableState(selected), onClick = null,
                colors = historyCheckboxColors(),
                modifier = Modifier.size(26.dp).testTag("history-select-${item.id}"))
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(toneBackground).padding(8.dp, 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon((if (item.state == "injected") VoiceLinkIcon.Check else if (item.state in setOf("uncertain", "review", "failed")) VoiceLinkIcon.Alert else VoiceLinkIcon.History).vector,
                        null, Modifier.size(14.dp), tint = tone)
                    Text(stringResource(historyStatus(item)), fontSize = 12.sp, color = tone, fontWeight = FontWeight.Medium)
                }
            }
            Text(time, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            if (!management) WindowIconDropdownMenu(DropdownEntry(listOf(
                DropdownItem(stringResource(R.string.history_details), onClick = onDetails),
                DropdownItem(stringResource(R.string.history_delete), onClick = onDelete))),
                Modifier.size(48.dp).offset(x = 14.dp).testTag("history-more-${item.id}"), minHeight = 48.dp, minWidth = 48.dp) {
                Icon(VoiceLinkIcon.More.vector, stringResource(R.string.history_details), Modifier.size(20.dp))
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
        if (item.state != "injected" && !management) HistoryRecovery(item, state, action)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { HistoryApplicationRow(state.historyApplications[item.id]) }
            if (!management) {
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
                }, modifier = Modifier.size(48.dp).offset(x = 14.dp).testTag("history-copy-${item.id}")) {
                    Icon((if (copied) VoiceLinkIcon.Check else VoiceLinkIcon.Copy).vector,
                        stringResource(if (copied) R.string.history_copied else R.string.history_copy), Modifier.size(20.dp))
                }
            }
        }
    }
    }
}

@Composable
private fun HistoryApplicationRow(application: ForegroundApplication?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (application?.icon != null) Image(application.icon.asImageBitmap(), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
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
    if (item.kind == "image" && item.state == "failed") {
        imageDeliveryFailure(item.reason)?.let { Caption(stringResource(it)) }
    }
    val presentation = seeyuer.sayin.data.receiptPresentation(item)
    presentation.helpRes?.let { help ->
        val cause = receiptCause(item.reason)
        if (cause != null && item.state in setOf("held", "expired")) Caption(stringResource(
            if (item.state == "expired") R.string.receipt_expired_cause_help else R.string.receipt_held_cause_help, stringResource(cause)))
        else Caption(stringResource(help))
    }
    presentation.scopeRes?.let { Caption(stringResource(it)) }
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
