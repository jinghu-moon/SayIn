package seeyuer.sayin.ui.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.R
import seeyuer.sayin.asr.CloudIssue
import seeyuer.sayin.asr.CloudSettings
import seeyuer.sayin.asr.CloudSettingsState
import seeyuer.sayin.asr.DoubaoAuth
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ForegroundApplication
import seeyuer.sayin.data.RootClipboardClient
import seeyuer.sayin.designsystem.Appearance
import seeyuer.sayin.designsystem.AppearanceMode
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.quick.QuickActivity
import seeyuer.sayin.quick.QuickEntryService
import seeyuer.sayin.quick.SpeakTileService
import seeyuer.sayin.polish.PolishConfigState
import seeyuer.sayin.translation.ModelPreparationProgress
import seeyuer.sayin.translation.ModelPreparationStage
import seeyuer.sayin.ui.SpeakInputSource
import seeyuer.sayin.ui.SpeakInteraction
import seeyuer.sayin.ui.SpeakSendMode
import seeyuer.sayin.ui.AppSendPolicy
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.CloudEditorState
import seeyuer.sayin.ui.CloudSettingsCard
import seeyuer.sayin.ui.plainTextButtonColors
import seeyuer.sayin.ui.localization.AppLanguage
import seeyuer.sayin.ui.localization.AppLanguages
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries

internal class HotwordsDraft(config: seeyuer.sayin.asr.CloudConfiguration?) {
    var text by mutableStateOf(config?.hotwords?.joinToString("\n").orEmpty())
    private var base = config?.hotwords?.joinToString("\n").orEmpty()
    val dirty: Boolean get() = text != base
    fun reset(config: seeyuer.sayin.asr.CloudConfiguration?) {
        base = config?.hotwords?.joinToString("\n").orEmpty()
        text = base
    }
}

internal enum class SettingsRoute(val parent: SettingsRoute?) {
    Root(null),
    SendPolicy(Root),
    Recognition(Root),
    Cloud(Recognition),
    Hotwords(Recognition),
    OfflineModel(Recognition),
    Translation(Root),
    Polish(Root),
    PolishServices(Polish),
    PolishUsage(Polish),
    PolishApps(Polish),
    PolishTerms(Polish),
    PolishPrompt(Polish),
    Ball(Root),
    Background(Root),
    Clipboard(Root),
    Privacy(Root),
    Storage(Privacy),
    About(Root),
    Help(About),
    Licenses(About),
    Diagnostics(About),
}

@Composable
internal fun SettingsHost(
    session: SpeakSession,
    cloudSettings: CloudSettings,
    cloudState: CloudSettingsState,
    clientState: ClientState,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onSubpageChange: (Boolean) -> Unit,
    initialRoute: SettingsRoute = SettingsRoute.Root,
) {
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    LaunchedEffect(initialRoute) { route = initialRoute }
    val cloudEditor = remember(cloudState.config) { CloudEditorState(cloudState.config) }
    val hotwordsDraft = remember(cloudState.config) { HotwordsDraft(cloudState.config) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() { route = route.parent ?: SettingsRoute.Root }
    fun hasUnsavedChanges() = when (route) {
        SettingsRoute.Cloud -> cloudEditor.dirty
        SettingsRoute.Hotwords -> hotwordsDraft.dirty
        else -> false
    }
    fun requestBack() { if (hasUnsavedChanges()) confirmLeave = true else leave() }
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
    BackHandler(enabled = route != SettingsRoute.Root) { requestBack() }
    val baseTextStyles = MiuixTheme.textStyles
    val settingsTextStyles = remember(baseTextStyles) {
        baseTextStyles.copy(
            body2 = baseTextStyles.body2.copy(fontSize = 13.sp),
        )
    }
    MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = settingsTextStyles) {
    AnimatedContent(
        targetState = route,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val backwards = targetState == initialState.parent || targetState.depth() < initialState.depth()
            val direction = if (backwards) -1 else 1
            ((slideInHorizontally(animationSpec = tween(260)) { width -> direction * width } + fadeIn(tween(180))) togetherWith
                (slideOutHorizontally(animationSpec = tween(220)) { width -> -direction * width / 4 } + fadeOut(tween(150))))
                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(0) }))
        },
    ) { currentRoute ->
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
            if (currentRoute == SettingsRoute.Root) RootTopBar()
            else SettingsTopBar(stringResource(currentRoute.titleRes()), onBack = ::requestBack, actions = {
                when (currentRoute) {
                    SettingsRoute.Cloud -> TextButton(
                        stringResource(R.string.settings_hotwords_save),
                        onClick = { cloudEditor.save(cloudSettings) },
                        enabled = !cloudState.busy && cloudEditor.dirty && cloudEditor.filled,
                        minHeight = 40.dp,
                        colors = plainTextButtonColors(),
                    )
                    SettingsRoute.Hotwords -> cloudState.config?.let { config ->
                        TextButton(
                            stringResource(R.string.settings_hotwords_save),
                            onClick = { cloudSettings.save(config.auth, config.appKey, config.appId, config.accessToken, hotwordsDraft.text) },
                            enabled = !cloudState.busy && hotwordsDraft.dirty,
                            minHeight = 40.dp,
                            colors = plainTextButtonColors(),
                        )
                    }
                    else -> Unit
                }
            })
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("settings-page-${currentRoute.name}"), contentAlignment = Alignment.TopCenter) {
                val pageModifier = Modifier.widthIn(max = 600.dp).fillMaxHeight()
                when (currentRoute) {
                    SettingsRoute.Root -> RootPage(
                        session, cloudState, keepAwake, onKeepAwake, language,
                        onOpen = { route = it }, modifier = pageModifier, listState = rootList,
                    )
                    SettingsRoute.SendPolicy -> SendPolicyPage(
                        session, clientState, pageModifier, scrollFor(SettingsRoute.SendPolicy),
                    )
                    SettingsRoute.Recognition -> RecognitionPage(
                        session, cloudState, onOpenCloud = { route = SettingsRoute.Cloud },
                        onOpenHotwords = { route = SettingsRoute.Hotwords },
                        onOpenModel = { route = SettingsRoute.OfflineModel }, modifier = pageModifier,
                        scroll = scrollFor(SettingsRoute.Recognition),
                    )
                    SettingsRoute.OfflineModel -> OfflineModelPage(session, pageModifier, scrollFor(SettingsRoute.OfflineModel))
                    SettingsRoute.Cloud -> CloudPage(cloudEditor, cloudSettings, cloudState,
                        onOpenHotwords = { route = SettingsRoute.Hotwords },
                        modifier = pageModifier, scroll = scrollFor(SettingsRoute.Cloud))
                    SettingsRoute.Hotwords -> HotwordsPage(cloudSettings, cloudState, hotwordsDraft, onOpenCloud = { route = SettingsRoute.Cloud }, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Hotwords))
                    SettingsRoute.Translation -> TranslationPage(session, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Translation))
                    SettingsRoute.Polish -> PolishSettingsPage(session, pageModifier, scrollFor(SettingsRoute.Polish),
                        openServices = { route = SettingsRoute.PolishServices },
                        openUsage = { route = SettingsRoute.PolishUsage },
                        openApps = { route = SettingsRoute.PolishApps },
                        openTerms = { route = SettingsRoute.PolishTerms },
                        openPrompt = { route = SettingsRoute.PolishPrompt })
                    SettingsRoute.PolishServices -> PolishServicesPage(session, pageModifier, scrollFor(SettingsRoute.PolishServices))
                    SettingsRoute.PolishUsage -> PolishUsagePage(session, pageModifier, scrollFor(SettingsRoute.PolishUsage))
                    SettingsRoute.PolishApps -> PolishAppRulesPage(session, pageModifier, scrollFor(SettingsRoute.PolishApps))
                    SettingsRoute.PolishTerms -> PolishTermsPage(session, pageModifier, scrollFor(SettingsRoute.PolishTerms))
                    SettingsRoute.PolishPrompt -> PolishPromptPage(session, pageModifier, scrollFor(SettingsRoute.PolishPrompt))
                    SettingsRoute.Ball -> BallPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Ball))
                    SettingsRoute.Background -> BackgroundPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Background))
                    SettingsRoute.Clipboard -> ClipboardPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Clipboard))
                    SettingsRoute.Privacy -> PrivacyPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Privacy))
                    SettingsRoute.Storage -> StoragePage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Storage))
                    SettingsRoute.About -> AboutPage(
                        onOpenHelp = { route = SettingsRoute.Help },
                        onOpenLicenses = { route = SettingsRoute.Licenses },
                        onOpenDiagnostics = { route = SettingsRoute.Diagnostics },
                        modifier = pageModifier,
                        scroll = scrollFor(SettingsRoute.About),
                    )
                    SettingsRoute.Help -> HelpPage(modifier = pageModifier, scroll = scrollFor(SettingsRoute.Help))
                    SettingsRoute.Licenses -> LicensesPage(pageModifier)
                    SettingsRoute.Diagnostics -> DiagnosticsPage(clientState, pageModifier, scrollFor(SettingsRoute.Diagnostics))
                }
            }
        }
    }
    WindowDialog(
        title = stringResource(R.string.settings_unsaved_title),
        summary = stringResource(R.string.settings_unsaved_message),
        show = confirmLeave,
        onDismissRequest = { confirmLeave = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.settings_keep_editing), onClick = { confirmLeave = false },
                modifier = Modifier.weight(1f).testTag("settings-keep-editing"))
            TextButton(stringResource(R.string.settings_discard_changes), onClick = {
                confirmLeave = false
                if (route == SettingsRoute.Cloud) cloudEditor.reset(cloudState.config) else hotwordsDraft.reset(cloudState.config)
                leave()
            }, modifier = Modifier.weight(1f).testTag("settings-discard-changes"))
        }
    }
    }
}

private fun SettingsRoute.depth(): Int = parent?.let { it.depth() + 1 } ?: 0

private fun SettingsRoute.titleRes(): Int = when (this) {
    SettingsRoute.Root -> R.string.settings_title
    SettingsRoute.SendPolicy -> R.string.settings_send_policy
    SettingsRoute.Recognition -> R.string.settings_voice_recognition
    SettingsRoute.Cloud -> R.string.settings_cloud_title
    SettingsRoute.Hotwords -> R.string.settings_hotwords_title
    SettingsRoute.OfflineModel -> R.string.settings_offline_model_title
    SettingsRoute.Translation -> R.string.settings_translation_title
    SettingsRoute.Polish -> R.string.settings_polish
    SettingsRoute.PolishServices -> R.string.polish_services
    SettingsRoute.PolishUsage -> R.string.polish_stats
    SettingsRoute.PolishApps -> R.string.polish_app_rules_title
    SettingsRoute.PolishTerms -> R.string.polish_terms_title
    SettingsRoute.PolishPrompt -> R.string.polish_prompt_title
    SettingsRoute.Ball -> R.string.settings_floating_ball_settings
    SettingsRoute.Background -> R.string.settings_background_title
    SettingsRoute.Clipboard -> R.string.settings_clipboard_title
    SettingsRoute.Privacy -> R.string.settings_privacy_title
    SettingsRoute.Storage -> R.string.settings_storage_title
    SettingsRoute.About -> R.string.settings_about_title
    SettingsRoute.Help -> R.string.settings_help_title
    SettingsRoute.Licenses -> R.string.settings_about_licenses
    SettingsRoute.Diagnostics -> R.string.settings_diagnostics_title
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
private fun SettingsTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
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
        actions = actions,
        defaultWindowInsetsPadding = false,
        color = MiuixTheme.colorScheme.background,
    )
}

@Composable
private fun SendPolicyPage(
    session: SpeakSession,
    clientState: ClientState,
    modifier: Modifier,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val current = clientState.foregroundApplication
    val apps = remember(current, clientState.historyApplications, session.appSendPolicies) {
        buildList {
            current?.let { add(it) }
            addAll(clientState.historyApplications.values)
            session.appSendPolicies.keys.forEach { name ->
                if (name.isNotBlank() && none { it.name == name }) add(ForegroundApplication(name))
            }
        }.filter { it.name.isNotBlank() }.distinctBy { it.name }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
    val automatic = session.sendMode == SpeakSendMode.Automatic
    val canChange = !session.busy && !session.requestLocked && !session.translation.running && !session.translationStopping
    /** Dragged value previews the wait time; only releasing commits it. */
    var delayDraft by remember { mutableIntStateOf(session.autoSendSeconds) }
    LaunchedEffect(session.autoSendSeconds) {
        delayDraft = session.autoSendSeconds
    }
    val ruleOptions = remember { listOf(AppSendPolicy.Inherit, AppSendPolicy.Automatic, AppSendPolicy.Manual) }
    val ruleLabels = listOf(
        stringResource(R.string.settings_policy_inherit),
        stringResource(R.string.settings_policy_auto),
        stringResource(R.string.settings_policy_manual),
    )
    fun effective(appName: String?): AppSendPolicy {
        val rule = appName?.let { session.appSendPolicies[it] } ?: AppSendPolicy.Inherit
        return if (rule == AppSendPolicy.Inherit) {
            if (automatic) AppSendPolicy.Automatic else AppSendPolicy.Manual
        } else rule
    }

    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    PolicyAppIcon(current, 36.dp)
                } else {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center) {
                        Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(19.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: stringResource(R.string.settings_send_policy_no_current),
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (current != null) {
                        Text(stringResource(R.string.settings_send_policy_current), fontSize = 11.5.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (current != null) SendPolicyStateChip(effective(current.name))
            }
        }

        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.settings_auto_send),
                    summary = stringResource(R.string.settings_auto_send_hint),
                    checked = automatic,
                    enabled = canChange,
                    switchColors = settingsSwitchColors(),
                    modifier = Modifier.testTag("settings-send-policy-default"),
                    onCheckedChange = {
                        if (canChange) session.changeSendMode(if (it) SpeakSendMode.Automatic else SpeakSendMode.Manual)
                    },
                )
                SliderPreference(
                    value = delayDraft.toFloat(),
                    onValueChange = { delayDraft = it.roundToInt() },
                    onValueChangeFinished = { session.changeAutoSendSeconds(delayDraft) },
                    modifier = Modifier.testTag("settings-send-policy-wait"),
                    title = stringResource(R.string.settings_send_policy_wait),
                    summary = stringResource(R.string.settings_auto_send_summary),
                    valueText = stringResource(R.string.settings_send_policy_seconds, delayDraft),
                    enabled = canChange && automatic,
                    valueRange = SpeakInteraction.MIN_DELAY_SECONDS.toFloat()..SpeakInteraction.MAX_DELAY_SECONDS.toFloat(),
                    steps = SpeakInteraction.MAX_DELAY_SECONDS - SpeakInteraction.MIN_DELAY_SECONDS - 1,
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_send_policy_apps), Modifier.weight(1f),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            if (session.appSendPolicies.isNotEmpty()) {
                TextButton(stringResource(R.string.settings_send_policy_clear), onClick = { session.clearAppSendPolicies() },
                    minHeight = 36.dp, colors = plainTextButtonColors(),
                    modifier = Modifier.testTag("send-policy-clear"))
            }
        }

        if (apps.isEmpty()) {
            SettingsCard {
                Text(stringResource(R.string.settings_send_policy_empty), modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Column {
                    apps.forEachIndexed { index, app ->
                        WindowDropdownPreference(
                            items = ruleLabels,
                            selectedIndex = ruleOptions.indexOf(session.appSendPolicies[app.name] ?: AppSendPolicy.Inherit),
                            title = app.name,
                            startAction = { PolicyAppIcon(app, 24.dp) },
                            modifier = Modifier.testTag("send-policy-app-${app.name}"),
                            onSelectedIndexChange = { selected ->
                                session.setAppSendPolicy(app.name, ruleOptions[selected])
                            },
                        )
                        if (index != apps.lastIndex) HorizontalDivider(Modifier.padding(start = 58.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SendPolicyStateChip(policy: AppSendPolicy) {
    val automatic = policy == AppSendPolicy.Automatic
    Box(
        Modifier.clip(CircleShape)
            .background(if (automatic) MiuixTheme.colorScheme.primary.copy(alpha = .11f) else MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            if (automatic) stringResource(R.string.settings_send_policy_chip_auto)
            else stringResource(R.string.settings_send_policy_chip_manual),
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (automatic) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
        )
    }
}

@Composable
private fun PolicyAppIcon(application: ForegroundApplication, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val icon = application.icon
    if (icon != null) {
        Image(
            icon.asImageBitmap(), null,
            Modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(if (size >= 36.dp) 10.dp else 9.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(size * .55f),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
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
                ArrowPreference(
                    title = stringResource(R.string.settings_send_policy),
                    summary = pluralStringResource(
                        R.plurals.settings_send_policy_summary,
                        session.appSendPolicies.size,
                        stringResource(if (session.sendMode == SpeakSendMode.Automatic) R.string.settings_policy_auto else R.string.settings_policy_manual),
                        session.appSendPolicies.size,
                    ),
                    startAction = startIcon(VoiceLinkIcon.Send),
                    onClick = { onOpen(SettingsRoute.SendPolicy) },
                )
                ArrowPreference(title = stringResource(R.string.settings_translation),
                    summary = stringResource(R.string.settings_translation_summary),
                    startAction = startIcon(VoiceLinkIcon.Translate), onClick = { onOpen(SettingsRoute.Translation) })
                val polishState = session.polish.state
                val polishSummary = when (polishState) {
                    PolishConfigState.Missing -> stringResource(R.string.settings_polish_summary_missing)
                    PolishConfigState.Corrupt, PolishConfigState.Unauthorized ->
                        stringResource(R.string.settings_polish_summary_corrupt)
                    is PolishConfigState.Ready -> stringResource(
                        R.string.settings_polish_summary_ready,
                        stringResource(providerLabel(polishState.config.provider)),
                        polishState.config.model,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.settings_polish),
                    summary = polishSummary,
                    startAction = {
                        val configured = (polishState as? PolishConfigState.Ready)?.config?.provider
                        if (configured != null) ProviderLogo(configured, Modifier.size(26.dp))
                        else Icon(VoiceLinkIcon.Ai.vector, null, Modifier.size(22.dp))
                    },
                    onClick = { onOpen(SettingsRoute.Polish) },
                )
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
                    switchColors = settingsSwitchColors(),
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
                    title = stringResource(R.string.settings_floating_ball_settings),
                    summary = stringResource(R.string.settings_floating_ball_settings_summary),
                    startAction = startIcon(VoiceLinkIcon.Phone),
                    onClick = { onOpen(SettingsRoute.Ball) },
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
                val appearance by Appearance.mode.collectAsStateWithLifecycle()
                val appearanceModes = AppearanceMode.entries
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_appearance),
                    items = appearanceModes.map { stringResource(it.labelRes) },
                    selectedIndex = appearanceModes.indexOf(appearance).coerceAtLeast(0),
                    startAction = startIcon(VoiceLinkIcon.Automatic),
                    onSelectedIndexChange = { Appearance.set(context, appearanceModes[it]) },
                )
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
                    switchColors = settingsSwitchColors(),
                    modifier = Modifier.testTag("keep-awake"),
                    onCheckedChange = onKeepAwake)
            }
        }
        item { GroupHeader(R.string.settings_group_privacy) }
        item {
            SettingsCard {
                ArrowPreference(title = stringResource(R.string.settings_storage),
                    summary = stringResource(R.string.settings_storage_summary),
                    startAction = startIcon(VoiceLinkIcon.History), onClick = { onOpen(SettingsRoute.Storage) })
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
private fun settingsSwitchColors() = SwitchDefaults.switchColors(
    checkedTrackColor = MiuixTheme.colorScheme.primary,
    checkedThumbColor = MiuixTheme.colorScheme.onPrimary,
    uncheckedTrackColor = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.Neutral700 else YuePalette.Neutral300,
    uncheckedThumbColor = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.Neutral100 else YuePalette.Neutral0,
)

@Composable
private fun GroupHeader(title: Int) {
    Text(stringResource(title), modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
    onOpenModel: () -> Unit,
    modifier: Modifier,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val engine = session.lastVoiceSource
    val cloudConfigured = cloudState.config != null
    val cloudError = cloudState.issue == CloudIssue.Storage
    val canChangeEngine = !session.busy && !session.requestLocked && !session.translation.running &&
        !session.translationStopping && !session.translationPreparing
    val cloud = engine == SpeakInputSource.Cloud
    val note = when {
        cloud && cloudError -> stringResource(R.string.settings_engine_cloud_read_error)
        cloud && !cloudConfigured -> stringResource(R.string.settings_cloud_need_config)
        cloud -> stringResource(R.string.settings_cloud_upload_note)
        else -> stringResource(R.string.settings_local_private_note)
    }
    val warning = cloud && (cloudError || !cloudConfigured)
    val noteColor = if (warning) warningColor() else MiuixTheme.colorScheme.onSurfaceSecondary
    val hotwordCount = cloudState.config?.hotwords?.size ?: 0
    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.padding(16.dp)) {
                TabRowWithContour(
                    tabs = listOf(
                        stringResource(R.string.settings_engine_local),
                        stringResource(R.string.settings_engine_cloud),
                    ),
                    selectedTabIndex = if (cloud) 1 else 0,
                    onTabSelected = {
                        if (canChangeEngine) session.selectSource(if (it == 1) SpeakInputSource.Cloud else SpeakInputSource.Local)
                    },
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                        selectedContentColor = MiuixTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().alpha(if (canChangeEngine) 1f else .5f).testTag("settings-recognition-engine"),
                )
                Row(Modifier.fillMaxWidth().padding(top = 11.dp, start = 3.dp, end = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon((if (cloud) VoiceLinkIcon.Cloud else VoiceLinkIcon.Shield).vector, null, Modifier.size(14.dp), tint = noteColor)
                    Spacer(Modifier.width(7.dp))
                    Text(note, fontSize = 11.5.sp, lineHeight = 17.sp, color = noteColor)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.settings_voice_commands),
                    summary = stringResource(R.string.settings_voice_commands_summary),
                    checked = session.voiceCommandsEnabled,
                    enabled = !session.busy && !session.requestLocked,
                    switchColors = settingsSwitchColors(),
                    modifier = Modifier.testTag("voice-commands"),
                    onCheckedChange = session::changeVoiceCommands,
                )
            }
        }
        Text(stringResource(R.string.settings_voice_commands_help),
            Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            fontSize = 11.5.sp, lineHeight = 16.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)

        SectionLabel(stringResource(R.string.settings_section_local), Modifier.padding(top = 10.dp, start = 4.dp, bottom = 6.dp))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(
                title = stringResource(R.string.settings_offline_model),
                summary = stringResource(R.string.settings_offline_model_sub),
                startAction = startIcon(VoiceLinkIcon.Computer),
                onClick = onOpenModel,
                endActions = {
                    Text(modelStatus(session), fontSize = 13.sp, color = modelStatusColor(session))
                    Spacer(Modifier.width(4.dp))
                    Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(17.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                },
            )
        }

        SectionLabel(stringResource(R.string.settings_section_cloud), Modifier.padding(top = 14.dp, start = 4.dp, bottom = 6.dp))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column {
                BasicComponent(
                    title = stringResource(R.string.settings_cloud_service_name),
                    summary = stringResource(R.string.settings_cloud_service_sub),
                    startAction = startIcon(VoiceLinkIcon.Cloud),
                    endActions = {
                        Text(cloudStatusText(cloudState), fontSize = 13.sp, color = cloudStatusColor(cloudState))
                        Spacer(Modifier.width(4.dp))
                        Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    },
                    onClick = onOpenCloud,
                )
                HorizontalDivider()
                BasicComponent(
                    title = stringResource(R.string.settings_hotwords),
                    startAction = startIcon(VoiceLinkIcon.Input),
                    endActions = {
                        Text(
                            if (cloudConfigured) stringResource(R.string.settings_hotwords_count_only, hotwordCount)
                            else stringResource(R.string.settings_engine_cloud_missing_short),
                            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    },
                    enabled = cloudConfigured && !cloudState.busy,
                    onClick = onOpenHotwords,
                )
            }
        }

        Text(stringResource(R.string.settings_recognition_short_note), Modifier.padding(top = 12.dp, start = 4.dp),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun warningColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.WarningDark else YuePalette.WarningLight

@Composable
private fun successColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.SuccessDark else YuePalette.SuccessLight

@Composable
private fun modelStatus(session: SpeakSession): String = when {
    session.modelPreparation != null -> stringResource(R.string.settings_offline_model_loading)
    session.modelPreparationFailed || session.model == seeyuer.sayin.asr.LocalModelPhase.Failed -> stringResource(R.string.settings_offline_model_failed)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Ready -> stringResource(R.string.settings_offline_model_ready)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Loading -> stringResource(R.string.settings_offline_model_loading)
    session.model == seeyuer.sayin.asr.LocalModelPhase.Warming -> stringResource(R.string.settings_offline_model_warming)
    else -> stringResource(R.string.settings_offline_model_idle)
}

@Composable
private fun modelStatusColor(session: SpeakSession): Color = when {
    session.modelPreparation != null || session.model == seeyuer.sayin.asr.LocalModelPhase.Loading ||
        session.model == seeyuer.sayin.asr.LocalModelPhase.Warming -> MiuixTheme.colorScheme.primary
    session.modelPreparationFailed || session.model == seeyuer.sayin.asr.LocalModelPhase.Failed -> MiuixTheme.colorScheme.error
    session.model == seeyuer.sayin.asr.LocalModelPhase.Ready -> successColor()
    else -> MiuixTheme.colorScheme.onSurfaceSecondary
}

@Composable
private fun cloudStatusText(state: CloudSettingsState): String = when {
    state.issue == CloudIssue.Storage -> stringResource(R.string.settings_engine_cloud_read_error)
    state.busy -> stringResource(R.string.cloud_saving)
    state.config != null -> stringResource(R.string.settings_engine_cloud_configured_short)
    else -> stringResource(R.string.settings_engine_cloud_missing_short)
}

@Composable
private fun cloudStatusColor(state: CloudSettingsState): Color = when {
    state.issue == CloudIssue.Storage -> MiuixTheme.colorScheme.error
    state.busy -> MiuixTheme.colorScheme.primary
    state.config != null -> successColor()
    else -> warningColor()
}

@Composable
private fun CloudPage(editor: CloudEditorState, settings: CloudSettings, state: CloudSettingsState,
    onOpenHotwords: () -> Unit, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
        CloudSettingsCard(editor, settings, state, onOpenHotwords = onOpenHotwords,
            hotwordCount = state.config?.hotwords?.size ?: 0)
    }
}

@Composable
private fun HotwordsPage(
    settings: CloudSettings,
    state: CloudSettingsState,
    draft: HotwordsDraft,
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
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(
            value = draft.text,
            onValueChange = { if (it.length <= 4096) draft.text = it },
            modifier = Modifier.fillMaxWidth().testTag("settings-hotwords"),
            label = stringResource(R.string.settings_hotwords),
            enabled = !state.busy,
            maxLines = 12,
        )
        NoticeText(stringResource(R.string.settings_hotwords_help))
        Text(
            stringResource(
                R.string.settings_hotwords_count,
                draft.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().count(),
                draft.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.sumOf { it.length },
            ),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
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
    val context = LocalContext.current
    val progress = session.modelPreparation
    val model = session.currentTranslationModel
    val models = session.translationModels
    val canManage = !session.busy && !session.requestLocked && !session.translation.running &&
        !session.translationStopping && !session.translationPreparing && !session.modelDeleting
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            WindowDropdownPreference(
                title = stringResource(R.string.settings_translation_model),
                items = models.map(::translationModelLabel),
                selectedIndex = models.indexOfFirst { it.id == model.id }.coerceAtLeast(0),
                startAction = startIcon(VoiceLinkIcon.Translate),
                onSelectedIndexChange = { index -> models.getOrNull(index)?.let { session.selectTranslationModel(it.id) } },
            )
            DiagnosticRow(stringResource(R.string.settings_translation_file), model.fileName)
            DiagnosticRow(stringResource(R.string.settings_translation_size), Formatter.formatFileSize(context, model.bytes))
            DiagnosticRow(stringResource(R.string.settings_translation_version), model.revision.take(12))
            DiagnosticRow(stringResource(R.string.settings_translation_status), when {
                progress != null -> stringResource(R.string.settings_translation_progress, progressPercent(progress))
                session.modelPreparationFailed || session.modelDeleteFailed -> stringResource(R.string.settings_translation_failed)
                session.translationModelBundled -> stringResource(R.string.settings_translation_bundled)
                session.translationModelReady -> stringResource(R.string.settings_translation_ready)
                session.translationModelPresent -> stringResource(R.string.settings_translation_incomplete)
                else -> stringResource(R.string.settings_translation_missing)
            })
        }
        NoticeText(stringResource(if (session.translationModelBundled) R.string.settings_translation_bundled_note else R.string.settings_translation_prepare_desc))
        if (!session.translationModelBundled) {
            Button(
                onClick = { session.prepareTranslationModel() },
                enabled = canManage && progress == null,
                minHeight = 48.dp,
                cornerRadius = 16.dp,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().testTag("settings-translation-prepare"),
            ) {
                Text(stringResource(if (session.translationModelReady) R.string.settings_translation_redownload else R.string.settings_translation_prepare))
            }
        }
        if (session.modelDeleteFailed) NoticeText(stringResource(R.string.settings_translation_delete_failed))
        if (session.translationModelPresent && progress == null) {
            TextButton(
                stringResource(if (session.modelDeleting) R.string.settings_translation_deleting else R.string.settings_translation_delete),
                onClick = { confirmDelete = true },
                enabled = canManage,
                minHeight = 48.dp,
                colors = plainTextButtonColors(),
                modifier = Modifier.fillMaxWidth().testTag("settings-translation-delete"),
            )
        }
        NoticeText(stringResource(R.string.settings_translation_note))
        NoticeText(stringResource(R.string.settings_translation_switch_note))
    }
    WindowDialog(
        title = stringResource(R.string.settings_translation_delete_title),
        summary = stringResource(R.string.settings_translation_delete_message),
        show = confirmDelete,
        onDismissRequest = { confirmDelete = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.assist_cancel), onClick = { confirmDelete = false },
                modifier = Modifier.weight(1f))
            TextButton(stringResource(R.string.settings_translation_delete_confirm), onClick = {
                confirmDelete = false
                session.deleteTranslationModel()
            }, modifier = Modifier.weight(1f).testTag("settings-translation-delete-confirm"))
        }
    }
}

private fun progressPercent(progress: ModelPreparationProgress): Int =
    (progress.bytes * 100 / progress.total.coerceAtLeast(1)).toInt()

private fun translationModelLabel(model: seeyuer.sayin.translation.TranslationModel): String =
    model.fileName.removeSuffix(".gguf")

@Composable
private fun OfflineModelPage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        bytes = withContext(Dispatchers.IO) { runCatching { seeyuer.sayin.asr.LocalAsrModel.size(context.assets) }.getOrNull() }
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            BasicComponent(
                title = stringResource(R.string.settings_asr_model_spec),
                summary = stringResource(R.string.settings_asr_model_spec_summary),
                startAction = startIcon(VoiceLinkIcon.Computer),
                endActions = {
                    Text(stringResource(R.string.settings_asr_model_spec_value), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                },
            )
            DiagnosticRow(stringResource(R.string.settings_asr_model_files), "4")
            DiagnosticRow(stringResource(R.string.settings_asr_model_size),
                bytes?.let { Formatter.formatFileSize(context, it) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_asr_model_version), seeyuer.sayin.asr.LocalAsrModel.VERSION)
            DiagnosticRow(stringResource(R.string.settings_asr_model_state), modelStatus(session))
        }
        NoticeText(stringResource(R.string.settings_asr_model_only_one))
        NoticeText(stringResource(R.string.settings_asr_model_bundled))
        NoticeText(stringResource(R.string.settings_asr_model_spec_note))
    }
}


@Composable
private fun BallPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    var size by remember { mutableFloatStateOf(QuickEntryService.size(context).toFloat()) }
    var opacity by remember { mutableFloatStateOf(QuickEntryService.peekAlpha(context) * 100f) }
    var doubleAction by remember { mutableStateOf(QuickEntryService.doubleAction(context)) }
    val doubleOptions = listOf(
        QuickEntryService.DOUBLE_CARD to stringResource(R.string.settings_ball_double_card),
        QuickEntryService.DOUBLE_RECORD to stringResource(R.string.settings_ball_double_record),
    )
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_ball_size), Modifier.weight(1f), fontSize = 14.sp)
                    Text(stringResource(R.string.settings_ball_size_value, size.roundToInt()), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = 44f..72f,
                    steps = 6,
                    onValueChangeFinished = { QuickEntryService.setSize(context, size.roundToInt()) },
                    modifier = Modifier.fillMaxWidth().testTag("ball-size"),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_ball_opacity), Modifier.weight(1f), fontSize = 14.sp)
                    Text(stringResource(R.string.settings_ball_opacity_value, opacity.roundToInt()), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 35f..100f,
                    steps = 12,
                    onValueChangeFinished = { QuickEntryService.setPeekAlpha(context, opacity / 100f) },
                    modifier = Modifier.fillMaxWidth().testTag("ball-opacity"),
                )
            }
        }
        SettingsCard {
            WindowDropdownPreference(
                title = stringResource(R.string.settings_ball_double),
                items = doubleOptions.map { it.second },
                selectedIndex = doubleOptions.indexOfFirst { it.first == doubleAction }.coerceAtLeast(0),
                startAction = startIcon(VoiceLinkIcon.Phone),
                onSelectedIndexChange = { index ->
                    doubleOptions.getOrNull(index)?.let {
                        doubleAction = it.first
                        QuickEntryService.setDoubleAction(context, it.first)
                    }
                },
            )
        }
        NoticeText(stringResource(R.string.settings_ball_note))
        NoticeText(stringResource(R.string.settings_ball_fullscreen_note))
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
private fun AboutPage(onOpenHelp: () -> Unit, onOpenLicenses: () -> Unit, onOpenDiagnostics: () -> Unit, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
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
                startAction = startIcon(VoiceLinkIcon.Shield), onClick = onOpenLicenses)
            ArrowPreference(title = stringResource(R.string.settings_about_diagnostics),
                summary = stringResource(R.string.settings_about_diagnostics_summary),
                startAction = startIcon(VoiceLinkIcon.Settings), onClick = onOpenDiagnostics)
        }
    }
}

@Composable
private fun LicensesPage(modifier: Modifier) {
    val context = LocalContext.current
    val libs = produceLibraries(R.raw.aboutlibraries).value
    if (libs == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.settings_about_licenses_summary), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        return
    }
    val libraries = remember(libs) {
        libs.libraries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.artifactId ?: "" })
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings-licenses"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_licenses_count, libraries.size),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Text(stringResource(R.string.settings_licenses_note), fontSize = 11.5.sp, lineHeight = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        items(libraries, key = { it.uniqueId ?: it.name ?: it.hashCode().toString() }) { library ->
            val licenses = library.licenses
            val primaryLicense = licenses.firstOrNull()
            val developer = library.developers.firstOrNull()?.name
                ?: library.organization?.name
            var expanded by remember(library.uniqueId) { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable {
                                val website = library.website?.takeIf { it.isNotBlank() }
                                if (website == null) expanded = !expanded
                                else runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website)))
                                }
                            },
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(library.name ?: library.artifactId ?: "Unknown", fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2)
                            val meta = listOfNotNull(
                                developer?.takeIf { it.isNotBlank() },
                                library.artifactVersion?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(meta, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    maxLines = 1)
                            }
                            primaryLicense?.let { license ->
                                val label = listOfNotNull(license.name, license.spdxId)
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (label.isNotBlank()) {
                                    Box(
                                        Modifier.background(
                                            MiuixTheme.colorScheme.primary.copy(alpha = .12f),
                                            RoundedCornerShape(50),
                                        ).padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier.size(44.dp).clickable { expanded = !expanded },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                VoiceLinkIcon.ChevronDown.vector,
                                null,
                                Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
                    if (expanded) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            library.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 12.sp, lineHeight = 17.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                            if (licenses.isEmpty()) {
                                Text(stringResource(R.string.settings_licenses_no_content), fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            } else {
                                licenses.forEach { license ->
                                    Text(
                                        listOfNotNull(license.name, license.spdxId).filter { it.isNotBlank() }
                                            .joinToString(" · ").ifBlank { stringResource(R.string.settings_licenses_no_content) },
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    )
                                    val text = license.licenseContent ?: license.url
                                    if (!text.isNullOrBlank()) {
                                        SelectionContainer {
                                            Text(text, fontSize = 11.sp, lineHeight = 16.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                        }
                                    }
                                }
                            }
                            library.website?.takeIf { it.isNotBlank() }?.let { website ->
                                TextButton(stringResource(R.string.settings_licenses_website), onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website))) }
                                }, minHeight = 40.dp, colors = plainTextButtonColors())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoragePage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var modelSizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        sizes = withContext(Dispatchers.IO) {
            val data = fileSize(context.filesDir) + fileSize(context.noBackupFilesDir) +
                fileSize(File(context.dataDir, "databases")) +
                fileSize(File(context.dataDir, "shared_prefs"))
            val cache = fileSize(context.cacheDir) + (context.externalCacheDir?.let(::fileSize) ?: 0L)
            data to cache
        }
        modelSizes = withContext(Dispatchers.IO) {
            val translation = fileSize(File(context.noBackupFilesDir, "translation"))
            val asr = runCatching { seeyuer.sayin.asr.LocalAsrModel.size(context.assets) }.getOrDefault(0L)
            translation to asr
        }
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            DiagnosticRow(stringResource(R.string.settings_storage_data), sizes?.let { Formatter.formatFileSize(context, it.first) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_cache), sizes?.let { Formatter.formatFileSize(context, it.second) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_used), sizes?.let { Formatter.formatFileSize(context, it.first + it.second) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_translation_models), modelSizes?.let { Formatter.formatFileSize(context, it.first) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_asr_model), modelSizes?.let { Formatter.formatFileSize(context, it.second) } ?: stringResource(R.string.settings_storage_calculating))
        }
        NoticeText(stringResource(R.string.settings_storage_note))
        if ((sizes?.second ?: 0L) > 0L) {
            Button(
                onClick = {
                    if (clearing) return@Button
                    clearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        clearing = false
                        refresh++
                    }
                },
                enabled = !clearing,
                minHeight = 48.dp,
                cornerRadius = 16.dp,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(VoiceLinkIcon.Clear.vector, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear_cache))
            }
        } else if (sizes != null) {
            NoticeText(stringResource(R.string.settings_storage_empty_cache))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(stringResource(R.string.settings_storage_refresh), onClick = { refresh++ },
                minHeight = 44.dp, colors = plainTextButtonColors(),
                modifier = Modifier.weight(1f))
            TextButton(stringResource(R.string.settings_storage_open_system), onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }, minHeight = 44.dp, colors = plainTextButtonColors(), modifier = Modifier.weight(1f))
        }
    }
}

private fun fileSize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf(::fileSize) ?: 0L
}

@Composable
private fun DiagnosticsPage(state: ClientState, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    val copied = remember { mutableStateOf(false) }
    val overlay = Settings.canDrawOverlays(context)
    val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val localNetwork = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val target = state.foregroundApplication?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.settings_diagnostics_none)
    val computer = state.address.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_diagnostics_none)
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val rows = listOf(
        stringResource(R.string.settings_diagnostics_version) to BuildConfig.VERSION_NAME,
        stringResource(R.string.settings_diagnostics_build) to BuildConfig.BUILD_TYPE,
        stringResource(R.string.settings_diagnostics_android) to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        stringResource(R.string.settings_diagnostics_device) to "${Build.MANUFACTURER} ${Build.MODEL}",
        stringResource(R.string.settings_diagnostics_locale) to localeTag,
    )
    val connectionRows = listOf(
        stringResource(R.string.settings_diagnostics_state) to state.connection.name,
        stringResource(R.string.settings_diagnostics_target) to target,
        stringResource(R.string.settings_diagnostics_computer) to computer,
        stringResource(R.string.settings_diagnostics_active) to trueFalse(state.active),
        stringResource(R.string.settings_diagnostics_paused) to trueFalse(state.paused),
        stringResource(R.string.settings_diagnostics_storage) to
            stringResource(if (state.storageHealthy) R.string.settings_diagnostics_healthy else R.string.settings_diagnostics_unhealthy),
    )
    val permissionRows = listOf(
        stringResource(R.string.settings_diagnostics_overlay) to yesNo(overlay),
        stringResource(R.string.settings_diagnostics_notification) to yesNo(notifications),
        stringResource(R.string.settings_diagnostics_local_network) to yesNo(localNetwork),
    )
    val diagnostics = buildString {
        rows.forEach { appendLine("${it.first}: ${it.second}") }
        appendLine()
        connectionRows.forEach { appendLine("${it.first}: ${it.second}") }
        appendLine()
        permissionRows.forEach { appendLine("${it.first}: ${it.second}") }
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.settings_diagnostics_app), Modifier.padding(start = 4.dp))
        SettingsCard { rows.forEach { DiagnosticRow(it.first, it.second) } }
        SectionLabel(stringResource(R.string.settings_diagnostics_connection), Modifier.padding(start = 4.dp))
        SettingsCard { connectionRows.forEach { DiagnosticRow(it.first, it.second) } }
        SectionLabel(stringResource(R.string.settings_diagnostics_permissions), Modifier.padding(start = 4.dp))
        SettingsCard { permissionRows.forEach { DiagnosticRow(it.first, it.second) } }
        Button(
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("SayIn diagnostics", diagnostics))
                copied.value = true
            },
            minHeight = 48.dp,
            cornerRadius = 16.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(VoiceLinkIcon.Copy.vector, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (copied.value) R.string.settings_diagnostics_copied else R.string.settings_diagnostics_copy))
        }
        TextButton(stringResource(R.string.settings_diagnostics_open_settings), onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }
        }, minHeight = 44.dp, colors = plainTextButtonColors(), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.settings_diagnostics_yes else R.string.settings_diagnostics_no)

@Composable
private fun trueFalse(value: Boolean): String =
    stringResource(if (value) R.string.settings_diagnostics_true else R.string.settings_diagnostics_false)

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
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
