package seeyuer.sayin.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import seeyuer.sayin.designsystem.YuePalette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.asr.CloudSettings
import seeyuer.sayin.asr.CloudSettingsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.PhraseLibrary
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.ui.localization.AppLanguagePicker
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import java.text.DateFormat
import java.util.Date

private enum class MobilePage(val label: Int, val title: Int, val subtitle: Int, val icon: VoiceLinkIcon) {
    Speak(R.string.nav_speak, R.string.app_name, R.string.app_tagline, VoiceLinkIcon.Microphone),
    History(R.string.nav_history, R.string.mobile_history_title, R.string.mobile_history_subtitle, VoiceLinkIcon.History),
    Connection(R.string.nav_connection, R.string.nav_connection, R.string.mobile_connection_subtitle, VoiceLinkIcon.Computer),
    Settings(R.string.nav_settings, R.string.mobile_settings_title, R.string.mobile_settings_subtitle, VoiceLinkIcon.Settings),
}

/** Presentation has no repository ownership: changing tabs never sends or reconnects. */
@Composable
internal fun VoiceLinkContent(
    state: ClientState,
    session: SpeakSession,
    phrases: PhraseLibrary,
    onSend: () -> Unit,
    onHistoryAction: (String, String) -> Unit,
    connectionActions: @Composable () -> Unit,
    onReconnect: () -> Unit = {},
    cloudSettings: CloudSettings = viewModel(),
    quick: Boolean = false,
    onExpand: (Boolean) -> Unit = {},
    onDismiss: () -> Unit = {},
    openSettings: Boolean = false,
    entryVersion: Int = 0,
    onSelectImage: (android.net.Uri) -> Unit = {},
    onSendImage: (String) -> Unit = {},
    onCancelImage: () -> Unit = {},
    onClearImage: () -> Unit = {},
) {
    var pageName by rememberSaveable { mutableStateOf(MobilePage.Speak.name) }
    val page = MobilePage.valueOf(pageName)
    var showConnection by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("presentation", 0) }
    val cloudState by cloudSettings.state.collectAsStateWithLifecycle()
    val source = session.source
    val sendMode = session.sendMode
    LaunchedEffect(entryVersion) { pageName = if (openSettings) MobilePage.Settings.name else MobilePage.Speak.name }
    LaunchedEffect(quick) { session.cancelAutoSend() }
    SideEffect { session.updateReceiver(state) }
    var keepAwake by remember { mutableStateOf(preferences.getBoolean("keep_awake", false)) }
    RefreshImeInsetsAfterLayout()
    val view = LocalView.current
    // Read the raw IME inset before the Scaffold's imePadding consumes it: the input-assist sheet
    // renders in an overlay window that receives no IME insets of its own.
    val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val editing = page == MobilePage.Speak && imeBottom > 0.dp
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = if (page == MobilePage.Speak) MiuixTheme.colorScheme.background else MiuixTheme.colorScheme.surface,
        topBar = {
            if (!quick && !editing && page == MobilePage.Speak) Spacer(Modifier.statusBarsPadding())
            if (!quick && !editing && page != MobilePage.Speak) Row(Modifier.fillMaxWidth().background(if (page == MobilePage.Speak) MiuixTheme.colorScheme.background else MiuixTheme.colorScheme.surface)
                .statusBarsPadding().padding(horizontal = 20.dp, vertical = if (page == MobilePage.Speak) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == MobilePage.Speak) {
                    Image(painterResource(R.drawable.sayin_brand), null, Modifier.size(30.dp))
                } else Box(Modifier.size(42.dp).background(MiuixTheme.colorScheme.primary, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(page.icon.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(page.title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    if (page != MobilePage.Speak) Caption(stringResource(page.subtitle))
                }
                if (page == MobilePage.Connection) Box(Modifier.widthIn(max = 110.dp)) { ConnectionBadge(state) }
                else ConnectionChip(state, { showConnection = true }, Modifier.widthIn(max = 180.dp))
            }
        },
        bottomBar = {
            val navigationLine = if (MiuixTheme.colorScheme.background == YuePalette.Neutral100) YuePalette.Neutral200 else YuePalette.Neutral850
            if (!quick && !editing) Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)
                .drawBehind { drawLine(navigationLine, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp)) {
                MobilePage.entries.forEach { item ->
                    val selected = page == item
                    Column(Modifier.weight(1f).heightIn(min = 52.dp).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { session.cancelAutoSend(); pageName = item.name }).testTag("nav-${item.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
                        Icon(item.icon.vector, null, Modifier.size(23.dp), tint = tint)
                        Text(stringResource(item.label), fontSize = 11.sp, color = tint, maxLines = 1)
                    }
                }
            }
        },
    ) { insets ->
        // Each page retains its own scroll position and the draft stays above page content.
        val historyScroll = rememberScrollState()
        val connectionScroll = rememberScrollState()
        val settingsScroll = rememberScrollState()
        val scroll = when (page) {
            MobilePage.Speak -> null
            MobilePage.History -> historyScroll
            MobilePage.Connection -> connectionScroll
            MobilePage.Settings -> settingsScroll
        }
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            if (page == MobilePage.Speak) {
                SpeakPage(state, session, phrases, onSend,
                    cloudConfig = cloudState.config.takeIf { !cloudState.busy && cloudState.issue != seeyuer.sayin.asr.CloudIssue.Storage },
                    onConfigureCloud = { if (quick) onExpand(true) else pageName = MobilePage.Settings.name },
                    onConnect = { showConnection = true },
                    connectionOpen = showConnection || showImage,
                    onImage = { session.cancelAutoSend(); showImage = true },
                    quick = quick, onExpand = { onExpand(false) }, onDismiss = onDismiss,
                    onRequestAction = onHistoryAction)
            } else Column(Modifier.widthIn(max = 600.dp).fillMaxHeight().verticalScroll(requireNotNull(scroll))
                .padding(horizontal = 20.dp, vertical = 16.dp).testTag("page-${page.name}"),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (page) {
                    MobilePage.Speak -> Unit
                    MobilePage.History -> MobileHistoryPage(state, onHistoryAction)
                    MobilePage.Connection -> {
                        if (!state.paired && state.connectionPresentation == ConnectionPresentation.First) {
                            ConnectionDetails(state, onReconnect, connectionActions)
                        } else Panel { ConnectionDetails(state, onReconnect, connectionActions) }
                    }
                    MobilePage.Settings -> SettingsPage(keepAwake, source, sendMode, cloudSettings, cloudState) {
                        keepAwake = it
                        preferences.edit().putBoolean("keep_awake", it).apply()
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        ImageInputSheet(showImage, state, !session.busy && !session.requestLocked,
            onDismiss = { onCancelImage(); showImage = false }, onSelectImage, onSendImage, onCancelImage, onClearImage)
        OverlayBottomSheet(show = showConnection, onDismissRequest = { showConnection = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("connection-sheet")) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.mobile_connection_title), Modifier.weight(1f),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { showConnection = false }, modifier = Modifier.size(48.dp).testTag("close-connection")) {
                        Icon(VoiceLinkIcon.Close.vector, stringResource(R.string.connection_close))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ConnectionDetails(state, onReconnect, connectionActions)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(keepAwake: Boolean, source: SpeakInputSource, sendMode: SpeakSendMode, cloudSettings: CloudSettings, cloudState: CloudSettingsState, onKeepAwake: (Boolean) -> Unit) {
    Panel {
        SectionTitle(R.string.mobile_input_settings, VoiceLinkIcon.Input)
        SettingLine(R.string.mobile_default_mode, sourceLabel(source))
        HorizontalDivider()
        SettingLine(R.string.mobile_send_mode, if (sendMode == SpeakSendMode.Manual) R.string.speak_send_manual else R.string.speak_auto_send)
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
    CloudSettingsCard(cloudSettings, cloudState)
    AppLanguagePicker()
    seeyuer.sayin.quick.QuickEntrySettings()
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
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
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
internal fun Caption(text: String) {
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
