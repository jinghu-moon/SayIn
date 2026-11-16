package seeyuer.sayin.ui.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.R
import seeyuer.sayin.asr.CloudIssue
import seeyuer.sayin.asr.CloudSettings
import seeyuer.sayin.asr.CloudSettingsState
import seeyuer.sayin.asr.DoubaoAuth
import seeyuer.sayin.data.RootClipboardClient
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.quick.QuickActivity
import seeyuer.sayin.quick.QuickEntryService
import seeyuer.sayin.quick.SpeakTileService
import seeyuer.sayin.translation.ModelPreparationStage
import seeyuer.sayin.ui.SpeakInputSource
import seeyuer.sayin.ui.SpeakSendMode
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.CloudSettingsCard
import seeyuer.sayin.ui.localization.AppLanguage
import seeyuer.sayin.ui.localization.AppLanguages
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class SettingsRoute(val parent: SettingsRoute?) {
    Root(null),
    Recognition(Root),
    Cloud(Recognition),
    Hotwords(Recognition),
    Translation(Root),
    Background(Root),
    Clipboard(Root),
    Privacy(Root),
    About(Root),
    Help(About),
}

@Composable
internal fun SettingsHost(
    session: SpeakSession,
    cloudSettings: CloudSettings,
    cloudState: CloudSettingsState,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onSubpageChange: (Boolean) -> Unit,
    initialRoute: SettingsRoute = SettingsRoute.Root,
) {
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    LaunchedEffect(initialRoute) { if (initialRoute != SettingsRoute.Root) route = initialRoute }
    val rootList = rememberLazyListState()
    val scrollStates = remember { mutableMapOf<SettingsRoute, androidx.compose.foundation.ScrollState>() }
    fun scrollFor(value: SettingsRoute) = scrollStates.getOrPut(value) { androidx.compose.foundation.ScrollState(0) }
    val configuration = LocalConfiguration.current
    val language = remember(configuration) { AppLanguages.current() }
    val context = LocalContext.current
    DisposableEffect(route) {
        onSubpageChange(route != SettingsRoute.Root)
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { onSubpageChange(false) } }
    BackHandler(enabled = route != SettingsRoute.Root) { route = route.parent ?: SettingsRoute.Root }
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        if (route == SettingsRoute.Root) RootTopBar()
        else SettingsTopBar(stringResource(route.titleRes()), onBack = { route = route.parent ?: SettingsRoute.Root })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val pageModifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()
            when (route) {
                SettingsRoute.Root -> RootPage(
                    session, cloudState, keepAwake, onKeepAwake, language,
                    onOpen = { route = it }, modifier = pageModifier, listState = rootList,
                )
                SettingsRoute.Recognition -> RecognitionPage(
                    session, cloudState, onOpenCloud = { route = SettingsRoute.Cloud },
                    onOpenHotwords = { route = SettingsRoute.Hotwords }, modifier = pageModifier,
                    scroll = scrollFor(SettingsRoute.Recognition),
                )
                SettingsRoute.Cloud -> CloudPage(cloudSettings, cloudState, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Cloud))
                SettingsRoute.Hotwords -> HotwordsPage(cloudSettings, cloudState, onOpenCloud = { route = SettingsRoute.Cloud }, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Hotwords))
                SettingsRoute.Translation -> TranslationPage(session, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Translation))
                SettingsRoute.Background -> BackgroundPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Background))
                SettingsRoute.Clipboard -> ClipboardPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Clipboard))
                SettingsRoute.Privacy -> PrivacyPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Privacy))
                SettingsRoute.About -> AboutPage(onOpenHelp = { route = SettingsRoute.Help }, modifier = pageModifier, scroll = scrollFor(SettingsRoute.About))
                SettingsRoute.Help -> HelpPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Help))
            }
        }
    }
}

private fun SettingsRoute.titleRes(): Int = when (this) {
    SettingsRoute.Root -> R.string.settings_title
    SettingsRoute.Recognition -> R.string.settings_voice_recognition
    SettingsRoute.Cloud -> R.string.settings_cloud_title
    SettingsRoute.Hotwords -> R.string.settings_hotwords_title
    SettingsRoute.Translation -> R.string.settings_translation_title
    SettingsRoute.Background -> R.string.settings_background_title
    SettingsRoute.Clipboard -> R.string.settings_clipboard_title
    SettingsRoute.Privacy -> R.string.settings_privacy_title
    SettingsRoute.About -> R.string.settings_about_title
    SettingsRoute.Help -> R.string.settings_help_title
}

@Composable
private fun RootTopBar() {
    SmallTopAppBar(
        title = "",
        modifier = Modifier.fillMaxWidth(),
        navigationIcon = {
            Text(stringResource(R.string.settings_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        },
        navigationIconPadding = 20.dp,
        actionIconPadding = 20.dp,
        defaultWindowInsetsPadding = false,
        color = MiuixTheme.colorScheme.background,
    )
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    SmallTopAppBar(
        title = "",
        modifier = Modifier.fillMaxWidth(),
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("settings-back")) {
                    Icon(VoiceLinkIcon.ArrowLeft.vector, stringResource(R.string.settings_back), Modifier.size(22.dp))
                }
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        },
        navigationIconPadding = 4.dp,
        actionIconPadding = 20.dp,
        defaultWindowInsetsPadding = false,
        color = MiuixTheme.colorScheme.background,
    )
}

@Composable
private fun RootPage(
    session: SpeakSession,
    cloudState: CloudSettingsState,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    language: AppLanguage?,
    onOpen: (SettingsRoute) -> Unit,
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("presentation", 0) }
    var ballEnabled by remember { mutableStateOf(prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context)) }
    var ballDenied by remember { mutableStateOf(false) }
    var tileHelp by remember { mutableStateOf(false) }
    fun enableBall() {
        if (!Settings.canDrawOverlays(context)) { ballDenied = true; return }
        try {
            ContextCompat.startForegroundService(context, Intent(context, QuickEntryService::class.java))
            ballEnabled = true; ballDenied = false
        } catch (_: Exception) { ballEnabled = false; ballDenied = true }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enableBall() }
    fun requestOverlay() {
        if (Settings.canDrawOverlays(context)) enableBall()
        else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestOverlay() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ballEnabled = prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context)
                if (ballEnabled) ballDenied = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val engine = session.lastVoiceSource
    val cloudText = when {
        cloudState.issue == CloudIssue.Storage -> stringResource(R.string.settings_engine_cloud_read_error)
        cloudState.config != null -> stringResource(R.string.settings_engine_cloud_configured)
        else -> stringResource(R.string.settings_engine_cloud_missing)
    }
    val engineText = stringResource(
        R.string.settings_engine_summary,
        stringResource(if (engine == SpeakInputSource.Cloud) R.string.settings_engine_cloud else R.string.settings_engine_local),
        cloudText,
    )
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("page-Settings"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { GroupHeader(R.string.settings_group_voice_input) }
        item {
            SettingsCard {
                ArrowPreference(title = stringResource(R.string.settings_voice_recognition), summary = engineText,
                    startAction = startIcon(VoiceLinkIcon.Microphone), onClick = { onOpen(SettingsRoute.Recognition) })
                SwitchPreference(title = stringResource(R.string.settings_auto_send),
                    summary = stringResource(R.string.settings_auto_send_summary),
                    checked = session.sendMode == SpeakSendMode.Automatic,
                    enabled = !session.busy && !session.requestLocked && !session.translation.running && !session.translationStopping,
                    onCheckedChange = { session.changeSendMode(if (it) SpeakSendMode.Automatic else SpeakSendMode.Manual) })
                ArrowPreference(title = stringResource(R.string.settings_translation),
                    summary = stringResource(R.string.settings_translation_summary),
                    startAction = startIcon(VoiceLinkIcon.Translate), onClick = { onOpen(SettingsRoute.Translation) })
            }
        }
        item { GroupHeader(R.string.settings_group_quick_entry) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.settings_floating_ball),
                    summary = stringResource(if (ballDenied) R.string.quick_overlay_denied else R.string.quick_overlay_help),
                    checked = ballEnabled,
                    startAction = startIcon(VoiceLinkIcon.Phone),
                    modifier = Modifier.testTag("quick-ball"),
                    onCheckedChange = { checked ->
                        if (!checked) {
                            context.stopService(Intent(context, QuickEntryService::class.java))
                            QuickEntryService.enabled(context, false); ballEnabled = false; ballDenied = false
                        } else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else requestOverlay()
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_control_center),
                    summary = stringResource(if (tileHelp) R.string.quick_tile_help else R.string.settings_control_center_summary),
                    startAction = startIcon(VoiceLinkIcon.Send),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                ComponentName(context, SpeakTileService::class.java), appName,
                                AndroidIcon.createWithResource(context, R.drawable.ic_stat_microphone), ContextCompat.getMainExecutor(context)) { }
                        } else tileHelp = true
                    },
                )
                ArrowPreference(title = stringResource(R.string.settings_background_help),
                    summary = stringResource(R.string.settings_background_help_summary),
                    startAction = startIcon(VoiceLinkIcon.Settings), onClick = { onOpen(SettingsRoute.Background) })
            }
        }
        item { GroupHeader(R.string.settings_group_experience) }
        item {
            SettingsCard {
                ArrowPreference(title = stringResource(R.string.settings_appearance),
                    summary = stringResource(R.string.settings_appearance_summary),
                    startAction = startIcon(VoiceLinkIcon.Settings), enabled = false, onClick = {})
                val languages = AppLanguage.entries
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_app_language),
                    items = languages.map { stringResource(it.labelRes) },
                    selectedIndex = languages.indexOfFirst { it == language }.coerceAtLeast(0),
                    startAction = startIcon(VoiceLinkIcon.Translate),
                    onSelectedIndexChange = { AppLanguages.select(languages[it]) },
                )
                SwitchPreference(title = stringResource(R.string.settings_keep_awake),
                    summary = stringResource(R.string.settings_keep_awake_summary),
                    checked = keepAwake, startAction = startIcon(VoiceLinkIcon.Computer),
                    modifier = Modifier.testTag("keep-awake"),
                    onCheckedChange = onKeepAwake)
            }
        }
        item { GroupHeader(R.string.settings_group_privacy) }
        item {
            SettingsCard {
                ArrowPreference(title = stringResource(R.string.settings_storage),
                    summary = stringResource(R.string.settings_storage_summary),
                    startAction = startIcon(VoiceLinkIcon.History), enabled = false, onClick = {})
                ArrowPreference(title = stringResource(R.string.settings_clipboard),
                    summary = stringResource(R.string.settings_clipboard_summary),
                    startAction = startIcon(VoiceLinkIcon.Clipboard), onClick = { onOpen(SettingsRoute.Clipboard) })
                ArrowPreference(title = stringResource(R.string.settings_privacy),
                    summary = stringResource(R.string.settings_privacy_summary),
                    startAction = startIcon(VoiceLinkIcon.Shield), onClick = { onOpen(SettingsRoute.Privacy) })
            }
        }
        item { GroupHeader(R.string.settings_group_about) }
        item {
            SettingsCard {
                ArrowPreference(title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                    startAction = startIcon(VoiceLinkIcon.Settings), onClick = { onOpen(SettingsRoute.About) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GroupHeader(title: Int) {
    Text(stringResource(title), modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun startIcon(icon: VoiceLinkIcon): @Composable () -> Unit = {
    Icon(icon.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun RecognitionPage(
    session: SpeakSession,
    cloudState: CloudSettingsState,
    onOpenCloud: () -> Unit,
    onOpenHotwords: () -> Unit,
    modifier: Modifier,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val engine = session.lastVoiceSource
    val engineOptions = listOf(
        stringResource(R.string.settings_engine_local),
        stringResource(R.string.settings_engine_cloud),
    )
    val canChangeEngine = !session.busy && !session.requestLocked && !session.translation.running &&
        !session.translationStopping && !session.translationPreparing
    val cloudSummary = when {
        cloudState.issue == CloudIssue.Storage -> stringResource(R.string.settings_engine_cloud_read_error)
        cloudState.config != null -> stringResource(R.string.settings_engine_cloud_configured)
        else -> stringResource(R.string.settings_engine_cloud_missing)
    }
    val hotwordCount = cloudState.config?.hotwords?.size ?: 0
    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard {
            WindowDropdownPreference(
                title = stringResource(R.string.settings_recognition_method),
                items = engineOptions,
                selectedIndex = if (engine == SpeakInputSource.Cloud) 1 else 0,
                enabled = canChangeEngine,
                onSelectedIndexChange = { session.selectSource(if (it == 1) SpeakInputSource.Cloud else SpeakInputSource.Local) },
            )
            ArrowPreference(title = stringResource(R.string.settings_offline_model),
                summary = modelStatus(session), startAction = startIcon(VoiceLinkIcon.Computer), enabled = false, onClick = {})
        }
        SettingsCard {
            ArrowPreference(title = stringResource(R.string.settings_cloud_service),
                summary = stringResource(R.string.settings_cloud_service_summary, cloudSummary),
                startAction = startIcon(VoiceLinkIcon.Cloud), onClick = onOpenCloud)
            ArrowPreference(title = stringResource(R.string.settings_hotwords),
                summary = if (cloudState.config == null) stringResource(R.string.settings_hotwords_configure_first)
                else stringResource(R.string.settings_hotwords_summary, hotwordCount),
                startAction = startIcon(VoiceLinkIcon.Input),
                enabled = cloudState.config != null && !cloudState.busy,
                onClick = onOpenHotwords)
        }
        NoticeText(stringResource(R.string.settings_recognition_note))
    }
}

@Composable
private fun modelStatus(session: SpeakSession): String = when {
    session.modelPreparation != null -> stringResource(R.string.settings_translation_progress,
        (session.modelPreparation!!.bytes * 100 / session.modelPreparation!!.total.coerceAtLeast(1)).toInt())
    session.modelPreparationFailed -> stringResource(R.string.settings_offline_model_failed)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Ready -> stringResource(R.string.settings_offline_model_ready)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Loading -> stringResource(R.string.settings_offline_model_loading)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Warming -> stringResource(R.string.settings_offline_model_warming)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Failed -> stringResource(R.string.settings_offline_model_failed)
    else -> stringResource(R.string.settings_offline_model_idle)
}

@Composable
private fun CloudPage(settings: CloudSettings, state: CloudSettingsState, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
        CloudSettingsCard(settings, state, showHotwords = false)
    }
}

@Composable
private fun HotwordsPage(
    settings: CloudSettings,
    state: CloudSettingsState,
    onOpenCloud: () -> Unit,
    modifier: Modifier,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val config = state.config
    if (config == null) {
        Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeText(stringResource(R.string.settings_hotwords_configure_first))
            TextButton(stringResource(R.string.settings_cloud_service), onClick = onOpenCloud, minHeight = 48.dp)
        }
        return
    }
    var text by remember(config) { mutableStateOf(config.hotwords.joinToString("\n")) }
    val canSave = !state.busy && text != config.hotwords.joinToString("\n")
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(
            value = text,
            onValueChange = { if (it.length <= 4096) text = it },
            modifier = Modifier.fillMaxWidth().testTag("settings-hotwords"),
            label = stringResource(R.string.settings_hotwords),
            enabled = !state.busy,
            maxLines = 12,
        )
        NoticeText(stringResource(R.string.settings_hotwords_help))
        Text(
            stringResource(
                R.string.settings_hotwords_count,
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().count(),
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.sumOf { it.length },
            ),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        TextButton(
            stringResource(R.string.settings_hotwords_save),
            onClick = { settings.save(config.auth, config.appKey, config.appId, config.accessToken, text) },
            enabled = canSave,
            minHeight = 48.dp,
            modifier = Modifier.fillMaxWidth().testTag("settings-hotwords-save"),
        )
        state.issue?.let { NoticeText(cloudIssueText(it)) }
    }
}

@Composable
private fun cloudIssueText(issue: CloudIssue): String = stringResource(when (issue) {
    CloudIssue.Configuration -> R.string.cloud_error_configuration
    CloudIssue.Storage -> R.string.cloud_error_storage
    CloudIssue.Network -> R.string.cloud_test_error_network
    CloudIssue.Authentication -> R.string.cloud_error_authentication
    CloudIssue.AccessDenied -> R.string.cloud_error_access_denied
    CloudIssue.Quota -> R.string.cloud_error_quota
    CloudIssue.Timeout -> R.string.cloud_test_error_timeout
    CloudIssue.Protocol -> R.string.cloud_test_error_protocol
    CloudIssue.Service -> R.string.cloud_error_service
})

@Composable
private fun TranslationPage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val progress = session.modelPreparation
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            ArrowPreference(title = stringResource(R.string.settings_translation),
                summary = when {
                    progress != null -> stringResource(R.string.settings_translation_progress,
                        (progress.bytes * 100 / progress.total.coerceAtLeast(1)).toInt())
                    session.modelPreparationFailed -> stringResource(R.string.settings_translation_failed)
                    session.translationModelReady -> stringResource(R.string.settings_translation_ready)
                    else -> stringResource(R.string.settings_translation_missing)
                },
                startAction = startIcon(VoiceLinkIcon.Translate), enabled = false, onClick = {})
        }
        NoticeText(stringResource(R.string.settings_translation_prepare_desc))
        TextButton(
            stringResource(if (session.translationModelReady) R.string.settings_translation_ready else R.string.settings_translation_prepare),
            onClick = { session.prepareTranslationModel() },
            enabled = progress == null && !session.translationModelReady,
            minHeight = 48.dp,
            modifier = Modifier.fillMaxWidth().testTag("settings-translation-prepare"),
        )
        NoticeText(stringResource(R.string.settings_translation_note))
    }
}

@Composable
private fun BackgroundPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeText(stringResource(R.string.settings_background_body))
        TextButton(stringResource(R.string.settings_background_open_quick), onClick = {
            runCatching { context.startActivity(QuickActivity.intent(context)) }
        }, minHeight = 48.dp, modifier = Modifier.fillMaxWidth())
        NoticeText(stringResource(R.string.settings_background_keepalive))
    }
}

@Composable
private fun ClipboardPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val unavailableText = stringResource(R.string.settings_clipboard_unavailable)
    val resultFormat = stringResource(R.string.settings_clipboard_result)
    val readyText = stringResource(R.string.settings_clipboard_ready)
    val notReadyText = stringResource(R.string.settings_clipboard_not_ready)
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeText(stringResource(R.string.settings_clipboard_normal))
        NoticeText(stringResource(R.string.settings_clipboard_module))
        TextButton(
            stringResource(if (checking) R.string.settings_clipboard_checking else R.string.settings_clipboard_check),
            onClick = {
                if (checking) return@TextButton
                checking = true; result = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { RootClipboardClient().list() }.getOrNull()
                    }
                    val text = if (outcome == null) unavailableText
                    else resultFormat.format(
                        if (outcome.captureReady) readyText else notReadyText,
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                    )
                    result = text; checking = false
                }
            },
            enabled = !checking, minHeight = 48.dp, modifier = Modifier.fillMaxWidth().testTag("settings-clipboard-check"),
        )
        result?.let { NoticeText(it) }
        SettingsCard {
            ArrowPreference(title = stringResource(R.string.settings_clipboard_control),
                summary = stringResource(R.string.settings_clipboard_control_summary),
                startAction = startIcon(VoiceLinkIcon.Shield), enabled = false, onClick = {})
        }
    }
}

@Composable
private fun PrivacyPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrivacyCard(R.string.settings_privacy_device_title, R.string.settings_privacy_device_body)
        PrivacyCard(R.string.settings_privacy_cross_title, R.string.settings_privacy_cross_body)
        PrivacyCard(R.string.settings_privacy_cloud_title, R.string.settings_privacy_cloud_body)
    }
}

@Composable
private fun PrivacyCard(title: Int, body: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}

@Composable
private fun AboutPage(onOpenHelp: () -> Unit, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.sayin_brand), null, Modifier.size(40.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                if (BuildConfig.DEBUG) Text(stringResource(R.string.settings_debug_build), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }
        SettingsCard {
            ArrowPreference(title = stringResource(R.string.settings_about_help),
                startAction = startIcon(VoiceLinkIcon.Clipboard), onClick = onOpenHelp)
            ArrowPreference(title = stringResource(R.string.settings_about_homepage),
                startAction = startIcon(VoiceLinkIcon.Cloud), onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jinghu-moon/SayIn"))) }
                })
            ArrowPreference(title = stringResource(R.string.settings_about_licenses),
                summary = stringResource(R.string.settings_about_licenses_summary),
                startAction = startIcon(VoiceLinkIcon.Shield), enabled = false, onClick = {})
            ArrowPreference(title = stringResource(R.string.settings_about_diagnostics),
                summary = stringResource(R.string.settings_about_diagnostics_summary),
                startAction = startIcon(VoiceLinkIcon.Settings), enabled = false, onClick = {})
        }
    }
}

@Composable
private fun HelpPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeText(stringResource(R.string.settings_help_body))
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}
