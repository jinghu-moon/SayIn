package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.normalizedText
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.ui.localization.AppLanguagePicker
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

private enum class MobilePage(val label: Int, val title: Int, val subtitle: Int, val icon: VoiceLinkIcon) {
    Speak(R.string.nav_speak, R.string.app_name, R.string.app_tagline, VoiceLinkIcon.Microphone),
    History(R.string.nav_history, R.string.mobile_history_title, R.string.mobile_history_subtitle, VoiceLinkIcon.History),
    Connection(R.string.nav_connection, R.string.mobile_connection_title, R.string.mobile_connection_subtitle, VoiceLinkIcon.Computer),
    Settings(R.string.nav_settings, R.string.mobile_settings_title, R.string.mobile_settings_subtitle, VoiceLinkIcon.Settings),
}

/** Presentation has no repository ownership: changing tabs never sends or reconnects. */
@Composable
internal fun VoiceLinkContent(
    state: ClientState,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onHistoryAction: (String, String) -> Unit,
    connectionActions: @Composable () -> Unit,
) {
    var pageName by rememberSaveable { mutableStateOf(MobilePage.Speak.name) }
    val page = MobilePage.valueOf(pageName)
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("presentation", 0) }
    var keepAwake by remember { mutableStateOf(preferences.getBoolean("keep_awake", false)) }
    val view = LocalView.current
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface)
                .statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == MobilePage.Speak) {
                    Image(painterResource(R.drawable.sayin_brand), null, Modifier.size(42.dp))
                } else Box(Modifier.size(42.dp).background(MiuixTheme.colorScheme.primary, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(page.icon.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Caption(stringResource(page.subtitle))
                }
                IconButton(onClick = { pageName = MobilePage.Connection.name }, modifier = Modifier.size(48.dp)) {
                    Icon(VoiceLinkIcon.Wifi.vector, stringResource(connectionText(state.connection)),
                        tint = if (state.connection == Connection.ONLINE) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                MobilePage.entries.forEach { item ->
                    NavigationBarItem(selected = page == item, onClick = { pageName = item.name },
                        icon = item.icon.vector, label = stringResource(item.label),
                        modifier = Modifier.testTag("nav-${item.name}"))
                }
            }
        },
    ) { insets ->
        // Each page retains its own scroll position and the draft stays above page content.
        val speakScroll = rememberScrollState()
        val historyScroll = rememberScrollState()
        val connectionScroll = rememberScrollState()
        val settingsScroll = rememberScrollState()
        val scroll = when (page) {
            MobilePage.Speak -> speakScroll
            MobilePage.History -> historyScroll
            MobilePage.Connection -> connectionScroll
            MobilePage.Settings -> settingsScroll
        }
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxHeight().verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp).testTag("page-${page.name}"),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (page) {
                    MobilePage.Speak -> SpeakPage(state, draft, onDraftChange, onSend) { pageName = MobilePage.Connection.name }
                    MobilePage.History -> HistoryPage(state, onHistoryAction)
                    MobilePage.Connection -> {
                        ComputerCard(state)
                        Panel {
                            SectionTitle(R.string.mobile_pairing, VoiceLinkIcon.Scan)
                            Caption(stringResource(R.string.pair_help))
                            connectionActions()
                        }
                        Notice(stringResource(R.string.mobile_privacy), VoiceLinkIcon.Shield)
                    }
                    MobilePage.Settings -> SettingsPage(keepAwake) {
                        keepAwake = it
                        preferences.edit().putBoolean("keep_awake", it).apply()
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SpeakPage(state: ClientState, draft: TextFieldValue, onDraftChange: (TextFieldValue) -> Unit,
                      onSend: () -> Unit, onConnect: () -> Unit) {
    ComputerCard(state, onConnect)
    SectionTitle(R.string.mobile_recognition, VoiceLinkIcon.Microphone)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeTile(R.string.mobile_local_asr, R.string.mobile_not_installed, false, VoiceLinkIcon.Microphone, Modifier.weight(1f))
        ModeTile(R.string.mobile_keyboard, R.string.mobile_keyboard_help, true, VoiceLinkIcon.Input, Modifier.weight(1f))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(88.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center) {
            IconButton(onClick = { focus.requestFocus(); keyboard?.show() }, modifier = Modifier.size(68.dp),
                backgroundColor = MiuixTheme.colorScheme.primary, cornerRadius = 34.dp) {
                Icon(VoiceLinkIcon.Microphone.vector, stringResource(R.string.mobile_open_keyboard), Modifier.size(32.dp),
                    tint = MiuixTheme.colorScheme.onPrimary)
            }
        }
        Text(stringResource(R.string.mobile_open_keyboard), fontWeight = FontWeight.Medium)
        Caption(stringResource(R.string.mobile_voice_hint))
    }
    Panel {
        SectionTitle(R.string.mobile_draft, VoiceLinkIcon.Input)
        TextField(draft, onDraftChange, Modifier.fillMaxWidth().focusRequester(focus).testTag("draft"),
            label = stringResource(R.string.input_placeholder), minLines = 3)
        val normalized = remember(draft.text) { runCatching { normalizedText(draft.text) }.getOrNull() }
        if (normalized != null && normalized != draft.text) {
            Caption(stringResource(R.string.normalization_preview))
            Text(normalized)
        }
        Action(R.string.send_to_computer, primary = true, enabled = state.ready && normalized != null, onClick = onSend)
        Caption(stringResource(if (state.ready) R.string.mobile_manual_send else readinessText(state)))
        state.issue?.let { Notice(stringResource(issueText(it)), VoiceLinkIcon.Alert) }
    }
    Notice(stringResource(R.string.mobile_send_explanation), VoiceLinkIcon.Send)
}

@Composable
private fun ComputerCard(state: ClientState, onConnect: (() -> Unit)? = null) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(VoiceLinkIcon.Computer.vector, null, tint = MiuixTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.address.ifEmpty { stringResource(R.string.connection_offline) }, fontWeight = FontWeight.Medium)
                Caption(stringResource(if (state.connection == Connection.ONLINE) readinessText(state) else connectionText(state.connection)))
            }
            if (state.connection == Connection.ONLINE) {
                Icon(VoiceLinkIcon.Check.vector, stringResource(R.string.connected), tint = MiuixTheme.colorScheme.primary)
            }
        }
        if (onConnect != null && !state.ready) {
            Action(if (state.paired) R.string.mobile_manage_connection else R.string.mobile_connect_action, onClick = onConnect)
        }
        if (state.ready) Notice(stringResource(R.string.mobile_cursor), VoiceLinkIcon.Input)
    }
}

@Composable
private fun ModeTile(title: Int, subtitle: Int, selected: Boolean, icon: VoiceLinkIcon, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else MiuixTheme.colorScheme.surface, shape)
        .border(1.dp, if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon.vector, null, tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(title), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
        Caption(stringResource(subtitle))
    }
}

@Composable
private fun HistoryPage(state: ClientState, action: (String, String) -> Unit) {
    Caption(stringResource(R.string.history_help))
    if (state.history.isEmpty()) {
        EmptyState(VoiceLinkIcon.History, R.string.mobile_history_empty, R.string.mobile_history_empty_help)
    } else {
        val configuration = LocalConfiguration.current
        val formatter = remember(configuration) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, configuration.locales[0]) }
        state.history.forEach { item ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon((when (item.state) {
                        "injected" -> VoiceLinkIcon.Check
                        "uncertain", "review", "failed" -> VoiceLinkIcon.Alert
                        else -> VoiceLinkIcon.History
                    }).vector, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text(stringResource(receiptText(item.state)), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                }
                Caption(formatter.format(Date(item.createdAt)))
                Text(stringResource(R.string.operation_label, item.id.take(8)), fontSize = 14.sp)
                receiptHelp(item.state)?.let { help ->
                    val cause = receiptCause(item.reason)
                    if (cause != null && item.state in setOf("held", "expired")) {
                        Caption(stringResource(if (item.state == "expired") R.string.receipt_expired_cause_help
                            else R.string.receipt_held_cause_help, stringResource(cause)))
                    } else Caption(stringResource(help))
                }
                if (item.state == "review") {
                    Notice(stringResource(R.string.review_warning), VoiceLinkIcon.Alert)
                    Action(R.string.retry_same_request, enabled = state.ready && item.target == state.target) { action(item.id, "retry") }
                    Action(R.string.discard_request) { action(item.id, "discard") }
                }
                if (item.state == "held") Action(R.string.release_request, enabled = state.ready) { action(item.id, "release") }
                if (item.state in setOf("held", "accepted")) Action(R.string.cancel_request, enabled = state.connection == Connection.ONLINE) { action(item.id, "cancel") }
                if (item.state == "uncertain") Notice(stringResource(R.string.uncertain_warning), VoiceLinkIcon.Alert)
            }
        }
    }
}

@Composable
private fun SettingsPage(keepAwake: Boolean, onKeepAwake: (Boolean) -> Unit) {
    Panel {
        SectionTitle(R.string.mobile_input_settings, VoiceLinkIcon.Input)
        SettingLine(R.string.mobile_default_mode, R.string.mobile_keyboard_help)
        HorizontalDivider()
        SettingLine(R.string.mobile_send_mode, R.string.mobile_manual_send)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.mobile_keep_awake))
                Caption(stringResource(R.string.mobile_keep_awake_help))
            }
            Switch(checked = keepAwake, onCheckedChange = onKeepAwake,
                modifier = Modifier.testTag("keep-awake").sizeIn(minWidth = 48.dp, minHeight = 48.dp))
        }
    }
    AppLanguagePicker()
    Panel {
        SectionTitle(R.string.mobile_planned, VoiceLinkIcon.Microphone)
        SettingLine(R.string.mobile_local_asr, R.string.mobile_asr_plan)
        HorizontalDivider()
        SettingLine(R.string.mobile_quick_entry, R.string.mobile_quick_entry_plan)
    }
    Notice(stringResource(R.string.mobile_privacy), VoiceLinkIcon.Shield)
}

@Composable
private fun SettingLine(title: Int, summary: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.Medium)
        Caption(stringResource(summary))
    }
}

@Composable
internal fun Notice(text: String, icon: VoiceLinkIcon) {
    Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon.vector, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(20.dp)), cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: Int, icon: VoiceLinkIcon) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon.vector, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun EmptyState(icon: VoiceLinkIcon, title: Int, help: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon.vector, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(title), fontWeight = FontWeight.Medium)
        Caption(stringResource(help))
    }
}

private fun readinessText(state: ClientState): Int = when {
    state.connection != Connection.ONLINE -> R.string.connection_required
    !state.active -> R.string.not_active_device
    state.target == null -> R.string.enable_receiving_on_computer
    state.paused -> R.string.receiver_paused
    else -> R.string.receiver_ready
}
