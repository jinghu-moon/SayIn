package seeyuer.sayin.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.OutboxItem
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
internal fun MobileHistoryPage(state: ClientState, action: (String, String) -> Unit) {
    var search by remember { mutableStateOf(TextFieldValue()) }
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var detailId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val formatter = remember(configuration) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, configuration.locales[0]) }
    val rows = state.history.filter { item ->
        (filter == HistoryFilter.All || filter == HistoryFilter.Submitted && item.state == "injected" ||
            filter == HistoryFilter.Review && item.state in setOf("saved", "accepted", "held", "executing", "review", "uncertain", "failed")) &&
            (search.text.isBlank() || (state.historyText[item.id].orEmpty() + " " + item.id).contains(search.text, ignoreCase = true))
    }
    TextField(search, { search = it }, Modifier.fillMaxWidth().testTag("history-search"),
        label = stringResource(R.string.history_search), singleLine = true)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryFilter.entries.forEach { item ->
            TextButton(stringResource(item.label), { filter = item }, Modifier.testTag("history-filter-${item.name}"), minHeight = 48.dp,
                colors = ButtonDefaults.textButtonColors(color = if (filter == item) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
                    textColor = if (filter == item) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface))
        }
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(VoiceLinkIcon.History.vector, null, Modifier.size(40.dp))
            Text(stringResource(if (state.history.isEmpty()) R.string.mobile_history_empty else R.string.history_no_results))
        }
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var previousDay: LocalDate? = null
    rows.forEach { item ->
        val day = Instant.ofEpochMilli(item.createdAt).atZone(zone).toLocalDate()
        if (day != previousDay) {
            Text(when (day) { today -> stringResource(R.string.history_today); today.minusDays(1) -> stringResource(R.string.history_yesterday)
                else -> DateFormat.getDateInstance(DateFormat.MEDIUM, configuration.locales[0]).format(Date(item.createdAt)) },
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            previousDay = day
        }
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon((if (item.state == "injected") VoiceLinkIcon.Check else if (item.state in setOf("uncertain", "review", "failed")) VoiceLinkIcon.Alert else VoiceLinkIcon.Computer).vector,
                    null, Modifier.size(18.dp))
                Text(stringResource(if (item.state == "uploading") R.string.image_uploading else receiptText(item.state)), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                WindowIconDropdownMenu(DropdownEntry(listOf(DropdownItem(stringResource(R.string.history_details), onClick = { detailId = item.id }))),
                    Modifier.size(48.dp).testTag("history-more-${item.id}"), minHeight = 48.dp, minWidth = 48.dp) {
                    Icon(VoiceLinkIcon.More.vector, stringResource(R.string.history_details), Modifier.size(22.dp))
                }
            }
            val text = state.historyText[item.id]
            Text(if (item.kind == "image") stringResource(R.string.image_history) else text ?: stringResource(R.string.history_text_unavailable), fontSize = 17.sp, lineHeight = 27.sp,
                maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.testTag("history-text-${item.id}"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Caption(stringResource(R.string.connection_computer))
                    Caption(formatter.format(Date(item.createdAt)))
                }
                if (text != null) TextButton(stringResource(R.string.history_copy), {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SayIn", text))
                }, minHeight = 48.dp)
            }
            HistoryRecovery(item, state, action)
        }
    }
    Caption(stringResource(R.string.history_local_storage))
    val detail = state.history.firstOrNull { it.id == detailId }
    OverlayBottomSheet(show = detail != null, onDismissRequest = { detailId = null }) {
        if (detail != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_details), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (detail.kind == "image") stringResource(R.string.image_history) else state.historyText[detail.id] ?: stringResource(R.string.history_text_unavailable), fontSize = 18.sp, lineHeight = 28.sp)
            Caption(stringResource(if (detail.kind == "image" && detail.state == "injected") R.string.image_paste_submitted else receiptText(detail.state)))
            Caption(formatter.format(Date(detail.createdAt)))
            Caption(stringResource(R.string.operation_label, detail.id))
            HistoryRecovery(detail, state, action)
        }
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
