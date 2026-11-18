package seeyuer.sayin.ui

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
import seeyuer.sayin.ui.settings.SettingsHost
import seeyuer.sayin.ui.settings.SettingsRoute
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class MobilePage(val label: Int, val icon: VoiceLinkIcon) {
    Speak(R.string.nav_speak, VoiceLinkIcon.Microphone),
    History(R.string.nav_history, VoiceLinkIcon.History),
    Connection(R.string.nav_connection, VoiceLinkIcon.Computer),
    Settings(R.string.nav_settings, VoiceLinkIcon.Settings),
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
    sharedImage: android.net.Uri? = null,
    shareVersion: Int = 0,
    onSelectImage: (android.net.Uri) -> Unit = {},
    onSendImage: (String) -> Unit = {},
    onCancelImage: () -> Unit = {},
    onClearImage: () -> Unit = {},
    loadDraftImage: suspend (String, Int) -> android.graphics.Bitmap? = { _, _ -> null },
    loadHistoryImage: suspend (String, Int) -> android.graphics.Bitmap? = { _, _ -> null },
) {
    var pageName by rememberSaveable { mutableStateOf(MobilePage.Speak.name) }
    val pagerState = rememberPagerState(initialPage = MobilePage.valueOf(pageName).ordinal) { MobilePage.entries.size }
    val page = MobilePage.entries[pagerState.currentPage]
    val scope = rememberCoroutineScope()
    val jumpProgress = remember { Animatable(1f) }
    var jumpDirection by remember { mutableIntStateOf(0) }
    suspend fun moveTo(target: MobilePage) {
        val distance = kotlin.math.abs(target.ordinal - pagerState.currentPage)
        if (distance <= 1) {
            // Neighboring tabs keep the normal pager spring.
            jumpProgress.snapTo(1f)
            pagerState.animateScrollToPage(target.ordinal, animationSpec = PagerNavigationSpringSpec)
        } else {
            // A non-adjacent pager animation would show every intermediate page.
            // Switch directly, then play a short directional entrance for the new page.
            jumpDirection = if (target.ordinal > pagerState.currentPage) 1 else -1
            jumpProgress.snapTo(0f)
            pagerState.scrollToPage(target.ordinal)
            jumpProgress.animateTo(1f, animationSpec = tween(240, easing = FastOutSlowInEasing))
        }
    }
    fun goTo(target: MobilePage) {
        scope.launch {
            session.cancelAutoSend()
            moveTo(target)
        }
    }
    var showConnection by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    var previewImageId by remember { mutableStateOf<String?>(null) }
    var settingsSubpage by remember { mutableStateOf(false) }
    var settingsEntry by remember { mutableStateOf(SettingsRoute.Root) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("presentation", 0) }
    val cloudState by cloudSettings.state.collectAsStateWithLifecycle()
    val source = session.source
    val sendMode = session.sendMode
    val jumpValue = jumpProgress.value
    val jumpOffset = with(LocalDensity.current) {
        (if (jumpDirection >= 0) 32.dp else (-32).dp).toPx()
    } * (1f - jumpValue)
    LaunchedEffect(pagerState.currentPage) {
        pageName = page.name
        session.cancelAutoSend()
    }
    LaunchedEffect(entryVersion) {
        moveTo(if (openSettings) MobilePage.Settings else MobilePage.Speak)
    }
    LaunchedEffect(quick) { session.cancelAutoSend() }
    LaunchedEffect(sharedImage, shareVersion) {
        sharedImage?.let {
            session.cancelAutoSend()
            showConnection = false
            onSelectImage(it)
            showImage = true
        }
    }
    SideEffect { session.updateReceiver(state) }
    var keepAwake by remember { mutableStateOf(preferences.getBoolean("keep_awake", false)) }
    RefreshImeInsetsAfterLayout()
    val view = LocalView.current
    // Read the raw IME inset before the Scaffold's imePadding consumes it: the input-assist sheet
    // renders in an overlay window that receives no IME insets of its own.
    val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // A constant background keeps the canvas stable while pages slide; each page paints itself.
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            // Same status-bar spacer on every page and while the IME is open: the bar itself lives
            // inside each pager page, so its height never changes and typing cannot push it away.
            if (!quick) Spacer(Modifier.statusBarsPadding())
        },
        bottomBar = {
            // Keep the bar composed at the window bottom. The IME window slides over it instead of
            // the layout removing it, so the page never has to jump to make room.
            if (!quick && !settingsSubpage) {
                val navigationLine = if (MiuixTheme.colorScheme.background == YuePalette.Neutral100) YuePalette.Neutral200 else YuePalette.Neutral850
                Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)
                    .drawBehind { drawLine(navigationLine, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                    .navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp)) {
                    MobilePage.entries.forEach { item ->
                        val selected = page == item
                        Column(Modifier.weight(1f).heightIn(min = 52.dp).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { if (item == MobilePage.Settings) settingsEntry = SettingsRoute.Root; goTo(item) }).testTag("nav-${item.name}"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
                            Icon(item.icon.vector, null, Modifier.size(23.dp), tint = tint)
                            Text(stringResource(item.label), fontSize = 11.sp, color = tint, maxLines = 1)
                        }
                    }
                }
            }
        },
    ) { insets ->
        val connectionScroll = rememberScrollState()
        val extraImeBottom = (imeBottom - insets.calculateBottomPadding()).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
            .then(if (extraImeBottom > 0.dp) Modifier.padding(bottom = extraImeBottom) else Modifier)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = jumpOffset
                    alpha = 0.72f + 0.28f * jumpValue
                },
                userScrollEnabled = !quick && !settingsSubpage,
            ) { index ->
                when (MobilePage.entries[index]) {
                    MobilePage.Speak -> SpeakPage(state, session, phrases, onSend,
                        cloudConfig = cloudState.config.takeIf { !cloudState.busy && cloudState.issue != seeyuer.sayin.asr.CloudIssue.Storage },
                        onConfigureCloud = { if (quick) onExpand(true) else { settingsEntry = SettingsRoute.Recognition; goTo(MobilePage.Settings) } },
                        onConnect = { showConnection = true },
                        connectionOpen = showConnection || showImage,
                        onImage = { session.cancelAutoSend(); showImage = true },
                        quick = quick, onExpand = { onExpand(false) }, onDismiss = onDismiss,
                        onRequestAction = onHistoryAction, pageActive = pagerState.settledPage == index)
                    MobilePage.History -> Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        PageTopBar(MobilePage.History)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("page-History")) {
                                MobileHistoryPage(state, onHistoryAction, loadHistoryImage)
                            }
                        }
                    }
                    MobilePage.Connection -> Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        PageTopBar(MobilePage.Connection)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Column(Modifier.widthIn(max = 600.dp).fillMaxHeight().verticalScroll(connectionScroll)
                                .padding(horizontal = 20.dp, vertical = 16.dp).testTag("page-Connection"),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (!state.paired && state.connectionPresentation == ConnectionPresentation.First) {
                                    ConnectionDetails(state, onReconnect, connectionActions)
                                } else Panel { ConnectionDetails(state, onReconnect, connectionActions) }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    MobilePage.Settings -> SettingsHost(
                        session = session,
                        cloudSettings = cloudSettings,
                        cloudState = cloudState,
                        clientState = state,
                        keepAwake = keepAwake,
                        onKeepAwake = {
                            keepAwake = it
                            preferences.edit().putBoolean("keep_awake", it).apply()
                        },
                        onSubpageChange = { settingsSubpage = it },
                        initialRoute = settingsEntry,
                    )
                }
            }
        }
        ImageInputSheet(showImage, state, !session.busy && !session.requestLocked,
            onDismiss = { onCancelImage(); showImage = false }, onSelectImage, onSendImage, onCancelImage, onClearImage,
            onPreview = { previewImageId = it })
        previewImageId?.let { id -> ImagePreviewDialog(id, loadDraftImage) { previewImageId = null } }
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
private fun PageTopBar(page: MobilePage) {
    SmallTopAppBar(
        title = "",
        modifier = Modifier.fillMaxWidth(),
        navigationIcon = {
            Text(stringResource(page.label), style = MiuixTheme.textStyles.title3,
                fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIconPadding = 20.dp,
        actionIconPadding = 20.dp,
        defaultWindowInsetsPadding = false,
        color = MiuixTheme.colorScheme.surface,
    )
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
internal fun Panel(
    insideMargin: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth().border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(20.dp)), cornerRadius = 20.dp, insideMargin = insideMargin) {
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
