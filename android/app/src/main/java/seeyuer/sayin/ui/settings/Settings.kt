package seeyuer.sayin.ui.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import seeyuer.sayin.ui.AppSheetInsideMargin
import seeyuer.sayin.quick.ForegroundApp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.state.ToggleableState
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
import seeyuer.sayin.models.ModelCategory
import seeyuer.sayin.asr.CloudIssue
import seeyuer.sayin.asr.CloudSettingsStatus
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
import seeyuer.sayin.quick.BallGeometry
import seeyuer.sayin.quick.QuickEntryService
import seeyuer.sayin.quick.SpeakTileService
import seeyuer.sayin.polish.PolishConfigState
import seeyuer.sayin.ui.shortTitleRes
import seeyuer.sayin.ui.titleRes
import seeyuer.sayin.ui.SpeakInputSource
import seeyuer.sayin.ui.SpeakInteraction
import seeyuer.sayin.ui.SpeakSendMode
import seeyuer.sayin.ui.AppSendPolicy
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.CloudEditorState
import seeyuer.sayin.ui.CloudSettingsCard
import seeyuer.sayin.ui.DraftTool
import seeyuer.sayin.ui.DRAFT_TOOL_SIDE_CHOICE_MAX
import seeyuer.sayin.ui.DraftToolEntry
import seeyuer.sayin.ui.DraftToolSide
import seeyuer.sayin.ui.DraftToolSurface
import seeyuer.sayin.ui.DraftTools
import seeyuer.sayin.ui.defaultDraftTools
import seeyuer.sayin.ui.defaultQuickDraftTools
import seeyuer.sayin.ui.moveDraftTool
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
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries

internal class HotwordsDraft(profiles: seeyuer.sayin.asr.CloudProfiles) {
    var text by mutableStateOf(profiles.hotwords.joinToString("\n"))
    private var base = profiles.hotwords.joinToString("\n")
    val dirty: Boolean get() = text != base
    fun reset(profiles: seeyuer.sayin.asr.CloudProfiles) {
        base = profiles.hotwords.joinToString("\n")
        text = base
    }
}

internal enum class SettingsRoute(val parent: SettingsRoute?) {
    Root(null),
    SendPolicy(Root),
    DraftToolbar(Root),
    Recognition(Root),
    Cloud(Recognition),
    Hotwords(Recognition),
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
    Storage(Root),
    LocalModels(Storage),
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
    var modelOrigin by rememberSaveable(initialRoute) { mutableStateOf<SettingsRoute?>(null) }
    LaunchedEffect(initialRoute) { route = initialRoute }
    val cloudEditor = remember(cloudState.profiles) { CloudEditorState(cloudState.profiles) }
    val hotwordsDraft = remember(cloudState.profiles.hotwords) { HotwordsDraft(cloudState.profiles) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClearUsage by remember { mutableStateOf(false) }
    fun openModels(origin: SettingsRoute?) {
        modelOrigin = origin
        route = SettingsRoute.LocalModels
    }
    val modelBack = modelOrigin
    fun leave() {
        route = if (route == SettingsRoute.LocalModels && modelBack != null) modelBack
            else route.parent ?: SettingsRoute.Root
    }
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
            val backwards = (initialState == SettingsRoute.LocalModels && targetState == modelBack) ||
                targetState == initialState.parent || targetState.depth() < initialState.depth()
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
                    SettingsRoute.PolishUsage -> TextButton(
                        stringResource(R.string.polish_stats_clear),
                        onClick = { confirmClearUsage = true },
                        minHeight = 40.dp,
                        colors = plainTextButtonColors(),
                    )
                    SettingsRoute.Hotwords -> {
                        TextButton(
                            stringResource(R.string.settings_hotwords_save),
                            onClick = { cloudSettings.saveHotwords(hotwordsDraft.text) },
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
                    SettingsRoute.DraftToolbar -> DraftToolbarPage(pageModifier)
                    SettingsRoute.Recognition -> RecognitionPage(
                        session, cloudState, onOpenCloud = { route = SettingsRoute.Cloud },
                        onOpenHotwords = { route = SettingsRoute.Hotwords },
                        onOpenModels = { openModels(SettingsRoute.Recognition) },
                        modifier = pageModifier,
                        scroll = scrollFor(SettingsRoute.Recognition),
                    )
                    SettingsRoute.Cloud -> CloudPage(cloudEditor, cloudSettings, cloudState,
                        onOpenHotwords = { route = SettingsRoute.Hotwords },
                        modifier = pageModifier, scroll = scrollFor(SettingsRoute.Cloud))
                    SettingsRoute.Hotwords -> HotwordsPage(cloudSettings, cloudState, hotwordsDraft, onOpenCloud = { route = SettingsRoute.Cloud }, modifier = pageModifier, scroll = scrollFor(SettingsRoute.Hotwords))
                    SettingsRoute.Translation -> TranslationPage(session, { openModels(SettingsRoute.Translation) },
                        modifier = pageModifier, scroll = scrollFor(SettingsRoute.Translation))
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
                    SettingsRoute.Storage -> StoragePage(session, { openModels(null) },
                        modifier = pageModifier, scroll = scrollFor(SettingsRoute.Storage))
                    SettingsRoute.LocalModels -> LocalModelStoragePage(session, pageModifier, scrollFor(SettingsRoute.LocalModels),
                        focusCategory = when (modelBack) {
                            SettingsRoute.Recognition -> ModelCategory.Recognition
                            SettingsRoute.Translation -> ModelCategory.Translation
                            else -> null
                        })
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
                if (route == SettingsRoute.Cloud) cloudEditor.reset(cloudState.profiles) else hotwordsDraft.reset(cloudState.profiles)
                leave()
            }, modifier = Modifier.weight(1f).testTag("settings-discard-changes"))
        }
    }
    WindowDialog(
        title = stringResource(R.string.polish_stats_clear),
        summary = stringResource(R.string.polish_stats_clear_summary),
        show = confirmClearUsage,
        onDismissRequest = { confirmClearUsage = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.polish_cancel_action), { confirmClearUsage = false },
                Modifier.weight(1f), minHeight = 48.dp)
            TextButton(stringResource(R.string.polish_stats_clear), {
                confirmClearUsage = false
                session.polish.clearStats()
            }, Modifier.weight(1f), minHeight = 48.dp, colors = plainTextButtonColors())
        }
    }
    }
}

private fun SettingsRoute.depth(): Int = parent?.let { it.depth() + 1 } ?: 0

private fun SettingsRoute.titleRes(): Int = when (this) {
    SettingsRoute.Root -> R.string.settings_title
    SettingsRoute.SendPolicy -> R.string.settings_send_policy
    SettingsRoute.DraftToolbar -> R.string.settings_draft_toolbar
    SettingsRoute.Recognition -> R.string.settings_voice_recognition
    SettingsRoute.Cloud -> R.string.settings_cloud_title
    SettingsRoute.Hotwords -> R.string.settings_hotwords_title
    SettingsRoute.Translation -> R.string.settings_translation_title
    SettingsRoute.Polish -> R.string.settings_polish
    SettingsRoute.PolishServices -> R.string.polish_services
    SettingsRoute.PolishUsage -> R.string.polish_stats
    SettingsRoute.PolishApps -> R.string.polish_app_rules_title
    SettingsRoute.PolishTerms -> R.string.polish_terms_title
    SettingsRoute.PolishPrompt -> R.string.polish_prompt_title
    SettingsRoute.Ball -> R.string.settings_floating_ball
    SettingsRoute.Background -> R.string.settings_background_title
    SettingsRoute.Clipboard -> R.string.settings_clipboard_title
    SettingsRoute.Privacy -> R.string.settings_privacy_title
    SettingsRoute.Storage -> R.string.settings_storage_title
    SettingsRoute.LocalModels -> R.string.local_model_title
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
                    summary = if (automatic) stringResource(R.string.settings_auto_send_summary)
                        else stringResource(R.string.settings_send_policy_wait_disabled),
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

/**
 * Order and visibility of the draft card's tool row. Reordering previews locally and persists
 * only on a real pointer release, matching the phrase reorder rules; visibility commits at once.
 */
@Composable
internal fun DraftToolbarPage(modifier: Modifier) {
    val context = LocalContext.current
    var surface by rememberSaveable { mutableStateOf(DraftToolSurface.Page) }
    val pageEntries by DraftTools.entriesFor(DraftToolSurface.Page).collectAsStateWithLifecycle()
    val quickEntries by DraftTools.entriesFor(DraftToolSurface.Quick).collectAsStateWithLifecycle()
    val pageSide by DraftTools.sideFor(DraftToolSurface.Page).collectAsStateWithLifecycle()
    val quickSide by DraftTools.sideFor(DraftToolSurface.Quick).collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf(pageEntries) }
    var side by remember { mutableStateOf(pageSide) }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(surface, pageEntries, quickEntries, pageSide, quickSide) {
        if (surface == DraftToolSurface.Page) {
            entries = pageEntries; side = pageSide
        } else {
            entries = quickEntries; side = quickSide
        }
    }
    fun persist() { DraftTools.replace(context, surface, entries) }
    fun toggle(tool: DraftTool, visible: Boolean) {
        entries = entries.map { if (it.tool == tool) it.copy(visible = visible) else it }
        persist()
    }
    fun move(tool: DraftTool, delta: Int) {
        val index = entries.indexOfFirst { it.tool == tool }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, entries.lastIndex)
        if (target == index) return
        val moved = entries.toMutableList()
        moved.add(target, moved.removeAt(index))
        entries = moved
        persist()
        focusTarget = tool.id
    }
    val reorderState = rememberReorderableLazyListState(listState, scrollThreshold = 48.dp) { from, to ->
        val fromTool = DraftTool.entries.firstOrNull { it.id == from.key }
        val toTool = DraftTool.entries.firstOrNull { it.id == to.key }
        if (fromTool != null && toTool != null) entries = moveDraftTool(entries, fromTool, toTool)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("settings-draft-toolbar"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.settings_draft_toolbar_surface_page),
                    stringResource(R.string.settings_draft_toolbar_surface_quick),
                ),
                selectedTabIndex = if (surface == DraftToolSurface.Quick) 1 else 0,
                onTabSelected = { index ->
                    surface = if (index == 1) DraftToolSurface.Quick else DraftToolSurface.Page
                    val targetEntries = if (surface == DraftToolSurface.Page) pageEntries else quickEntries
                    entries = targetEntries
                    side = if (surface == DraftToolSurface.Page) pageSide else quickSide
                },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                    selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                    selectedContentColor = MiuixTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().testTag("settings-draft-toolbar-surface"),
            )
        }
        item {
            Text(
                stringResource(R.string.settings_draft_toolbar_note),
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        // The row only stops spanning the card below four buttons, so the choice is offered then.
        if (entries.count { it.visible } <= DRAFT_TOOL_SIDE_CHOICE_MAX) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_draft_toolbar_side),
                        Modifier.padding(horizontal = 4.dp),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    TabRowWithContour(
                        tabs = listOf(
                            stringResource(R.string.settings_draft_toolbar_side_left),
                            stringResource(R.string.settings_draft_toolbar_side_right),
                        ),
                        selectedTabIndex = if (side == DraftToolSide.End) 1 else 0,
                        onTabSelected = { index ->
                            val chosen = if (index == 1) DraftToolSide.End else DraftToolSide.Start
                            side = chosen
                            DraftTools.setSide(context, surface, chosen)
                        },
                        colors = TabRowDefaults.tabRowColors(
                            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                            selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                            selectedContentColor = MiuixTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("settings-draft-toolbar-side"),
                    )
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.tool.id }) { index, entry ->
            ReorderableItem(reorderState, key = entry.tool.id) { dragging ->
                var released by remember { mutableStateOf(false) }
                val gestureDetector = remember {
                    DragGestureDetector { start, end, cancel, drag ->
                        detectVerticalDragGestures(
                            onDragStart = { released = false; start(it) },
                            onDragEnd = { released = true; end() },
                            onDragCancel = cancel,
                            onVerticalDrag = { change, amount -> drag(change, Offset(0f, amount)) },
                        )
                    }
                }
                DraftToolRow(
                    entry = entry,
                    index = index,
                    total = entries.size,
                    dragging = dragging,
                    focusTarget = focusTarget,
                    onFocusHandled = { focusTarget = null },
                    onMove = { delta -> move(entry.tool, delta) },
                    onVisibleChange = { toggle(entry.tool, it) },
                    gripModifier = Modifier.draggableHandle(
                        dragGestureDetector = gestureDetector,
                        onDragStopped = { if (released) persist() },
                    ),
                )
            }
        }
        item {
            TextButton(
                stringResource(R.string.settings_draft_toolbar_reset),
                onClick = {
                    entries = if (surface == DraftToolSurface.Page) defaultDraftTools() else defaultQuickDraftTools()
                    side = DraftToolSide.Start
                    DraftTools.reset(context, surface)
                },
                minHeight = 48.dp,
                colors = plainTextButtonColors(),
                modifier = Modifier.fillMaxWidth().testTag("settings-draft-toolbar-reset"),
            )
        }
    }
}

@Composable
private fun DraftToolRow(
    entry: DraftToolEntry,
    index: Int,
    total: Int,
    dragging: Boolean,
    focusTarget: String?,
    onFocusHandled: () -> Unit,
    onMove: (Int) -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    gripModifier: Modifier,
) {
    val label = stringResource(entry.tool.labelRes)
    val reorderDescription = stringResource(R.string.assist_reorder_for, label)
    val positionDescription = stringResource(R.string.assist_moved, index + 1, total)
    val moveUp = stringResource(R.string.assist_move_up)
    val moveDown = stringResource(R.string.assist_move_down)
    val reorderLabel = stringResource(R.string.assist_reorder)
    val gripFocus = remember(entry.tool.id) { FocusRequester() }
    val shape = RoundedCornerShape(16.dp)
    val elevation by animateDpAsState(
        if (dragging) 6.dp else 0.dp,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    LaunchedEffect(focusTarget) {
        if (focusTarget == entry.tool.id) {
            runCatching { gripFocus.requestFocus() }
            onFocusHandled()
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .shadow(elevation, shape)
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).focusRequester(gripFocus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { onMove(-1); true }
                        Key.DirectionDown -> { onMove(1); true }
                        else -> false
                    }
                }
                .then(gripModifier)
                .semantics {
                    contentDescription = reorderDescription
                    role = Role.Button
                    stateDescription = positionDescription
                    customActions = listOf(
                        CustomAccessibilityAction(moveUp) { onMove(-1); true },
                        CustomAccessibilityAction(moveDown) { onMove(1); true },
                    )
                }
                .testTag("draft-tool-grip-${entry.tool.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                VoiceLinkIcon.GripVertical.vector,
                reorderLabel,
                Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        Icon(entry.tool.icon.vector, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1)
        Switch(
            checked = entry.visible,
            onCheckedChange = onVisibleChange,
            colors = settingsSwitchColors(),
            modifier = Modifier.padding(end = 12.dp).testTag("draft-tool-visible-${entry.tool.id}"),
        )
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
    val pageToolEntries by DraftTools.entriesFor(DraftToolSurface.Page).collectAsStateWithLifecycle()
    val quickToolEntries by DraftTools.entriesFor(DraftToolSurface.Quick).collectAsStateWithLifecycle()
    val shownToolCount = pageToolEntries.count { it.visible }
    val shownQuickToolCount = quickToolEntries.count { it.visible }
    val appName = stringResource(R.string.app_name)
    var tileHelp by remember { mutableStateOf(false) }
    val engine = session.lastVoiceSource
    val providerName = stringResource(cloudState.profiles.selected.shortTitleRes())
    val engineText = when {
        engine == SpeakInputSource.Cloud -> when {
            cloudState.status == CloudSettingsStatus.Loading ->
                stringResource(R.string.settings_engine_cloud_loading)
            cloudState.issue == CloudIssue.Storage ->
                stringResource(R.string.settings_engine_cloud_unreadable)
            cloudState.config == null ->
                stringResource(R.string.settings_engine_cloud_provider_missing, providerName)
            else -> stringResource(R.string.settings_engine_cloud_provider, providerName)
        }
        !session.localModels.initialized -> stringResource(R.string.settings_engine_local_loading)
        session.recognitionInstalled -> stringResource(
            R.string.settings_engine_local_model,
            session.currentRecognitionModel.name,
        )
        session.localModels.catalog.recognition.none { it.id in session.localModels.installed } ->
            stringResource(R.string.settings_engine_local_no_model)
        else -> stringResource(R.string.settings_engine_local_no_selection)
    }
    val translationSummary = when {
        !session.localModels.initialized -> stringResource(R.string.settings_translation_summary_loading)
        session.translationModelReady -> stringResource(
            R.string.settings_translation_summary_model,
            session.currentTranslationModel.displayName,
        )
        else -> stringResource(R.string.settings_translation_summary_missing)
    }
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
                val defaultMode = stringResource(
                    if (session.sendMode == SpeakSendMode.Automatic) R.string.settings_policy_auto
                    else R.string.settings_policy_manual,
                )
                val ruleCount = session.appSendPolicies.size
                ArrowPreference(
                    title = stringResource(R.string.settings_send_policy),
                    summary = if (ruleCount == 0) defaultMode else pluralStringResource(
                        R.plurals.settings_send_policy_summary_rules,
                        ruleCount,
                        defaultMode,
                        ruleCount,
                    ),
                    startAction = startIcon(VoiceLinkIcon.Send),
                    onClick = { onOpen(SettingsRoute.SendPolicy) },
                )
                ArrowPreference(title = stringResource(R.string.settings_translation),
                    summary = translationSummary,
                    startAction = startIcon(VoiceLinkIcon.Translate), onClick = { onOpen(SettingsRoute.Translation) })
                val polishState = session.polish.state
                val polishSummary = when (polishState) {
                    PolishConfigState.Missing -> stringResource(R.string.settings_polish_summary_missing)
                    PolishConfigState.Corrupt, PolishConfigState.Unauthorized ->
                        stringResource(R.string.settings_polish_summary_corrupt)
                    is PolishConfigState.Ready -> stringResource(
                        R.string.settings_polish_summary_ready,
                        stringResource(providerLabel(polishState.config.provider)),
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.settings_polish),
                    summary = polishSummary,
                    startAction = {
                        Icon(VoiceLinkIcon.Ai.vector, null, Modifier.size(24.dp))
                    },
                    onClick = { onOpen(SettingsRoute.Polish) },
                )
            }
        }
        item { GroupHeader(R.string.settings_group_quick_entry) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_floating_ball),
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
                ArrowPreference(
                    title = stringResource(R.string.settings_draft_toolbar),
                    summary = stringResource(R.string.settings_draft_toolbar_summary, shownToolCount, shownQuickToolCount),
                    startAction = startIcon(VoiceLinkIcon.Clipboard),
                    modifier = Modifier.testTag("settings-draft-toolbar"),
                    onClick = { onOpen(SettingsRoute.DraftToolbar) },
                )
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
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun startIcon(icon: VoiceLinkIcon): @Composable () -> Unit = {
    Icon(icon.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
}

@Composable
private fun RecognitionPage(
    session: SpeakSession,
    cloudState: CloudSettingsState,
    onOpenCloud: () -> Unit,
    onOpenHotwords: () -> Unit,
    onOpenModels: () -> Unit,
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
    val hotwordCount = cloudState.profiles.hotwords.size
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

        Spacer(Modifier.height(12.dp))

        if (cloud) {
            // Only the selected engine's settings stay on screen; the tab is the engine choice.
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Column {
                    BasicComponent(
                        title = stringResource(cloudState.profiles.selected.titleRes()),
                        summary = stringResource(R.string.cloud_streaming),
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
                                else stringResource(R.string.settings_hotwords_cloud_required),
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
        } else {
            ModelSettingsContent(session, ModelCategory.Recognition, onOpenModels)
        }

        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            SwitchPreference(
                title = stringResource(R.string.settings_continuous_dictation),
                summary = stringResource(R.string.settings_continuous_dictation_summary),
                checked = session.continuousDictation,
                enabled = !session.busy,
                switchColors = settingsSwitchColors(),
                modifier = Modifier.testTag("settings-continuous-dictation"),
                onCheckedChange = session::changeContinuousDictation,
            )
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
            hotwordCount = state.profiles.hotwords.size)
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
            TextButton(stringResource(R.string.settings_cloud_service), onClick = onOpenCloud, minHeight = 48.dp,
                colors = ButtonDefaults.textButtonColorsPrimary())
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
private fun TranslationPage(
    session: SpeakSession,
    onOpenModels: () -> Unit,
    modifier: Modifier,
    scroll: androidx.compose.foundation.ScrollState,
) {
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)) {
        ModelSettingsContent(session, ModelCategory.Translation, onOpenModels)
        Spacer(Modifier.height(8.dp))
        ModelFootnote(VoiceLinkIcon.Shield, stringResource(R.string.settings_translation_private))
    }
}

private enum class BallColorTarget { Background, Icon }

@Composable
private fun BallPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("presentation", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context)) }
    var denied by remember { mutableStateOf(false) }
    var hiddenPackages by remember { mutableStateOf(QuickEntryService.hiddenPackages(context)) }
    var usageAccess by remember { mutableStateOf(ForegroundApp.hasAccess(context)) }
    var hiddenAppsOpen by remember { mutableStateOf(false) }
    val usageAccessPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        usageAccess = ForegroundApp.hasAccess(context)
        if (usageAccess) hiddenAppsOpen = true
    }
    fun enableBall() {
        if (!Settings.canDrawOverlays(context)) { denied = true; return }
        // Persist before starting the service: ON_RESUME can run after the
        // permission result but before the service creates its ball.
        QuickEntryService.enabled(context, true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, QuickEntryService::class.java))
            enabled = true; denied = false
        } catch (_: Exception) {
            QuickEntryService.enabled(context, false)
            enabled = false; denied = true
        }
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
                enabled = prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context)
                hiddenPackages = QuickEntryService.hiddenPackages(context)
                usageAccess = ForegroundApp.hasAccess(context)
                if (enabled) denied = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var size by remember { mutableFloatStateOf(QuickEntryService.size(context).toFloat()) }
    var opacity by remember { mutableFloatStateOf(QuickEntryService.peekAlpha(context) * 100f) }
    var peek by remember { mutableFloatStateOf(QuickEntryService.peekFraction(context)) }
    var iconFraction by remember { mutableFloatStateOf(QuickEntryService.iconFraction(context)) }
    var keepWhole by remember { mutableStateOf(QuickEntryService.keepIconWhole(context)) }
    var doubleAction by remember { mutableStateOf(QuickEntryService.doubleAction(context)) }
    var backgroundColor by remember { mutableIntStateOf(QuickEntryService.ballBackground(context)) }
    var iconColor by remember { mutableIntStateOf(QuickEntryService.ballIcon(context)) }
    var customTarget by remember { mutableStateOf<BallColorTarget?>(null) }
    var draftColor by remember { mutableIntStateOf(0) }
    val backgroundOptions = listOf(
        0xFFF3F3F1.toInt() to R.string.settings_ball_color_offwhite,
        0xFF252525.toInt() to R.string.settings_ball_color_charcoal,
        0xFF1F75DB.toInt() to R.string.settings_ball_color_brand,
    )
    val iconOptions = listOf(
        0xFF252525.toInt() to R.string.settings_ball_color_charcoal,
        0xFFFFFFFF.toInt() to R.string.settings_ball_color_white,
        0xFF1F75DB.toInt() to R.string.settings_ball_color_brand,
    )
    val doubleOptions = listOf(
        QuickEntryService.DOUBLE_CARD to stringResource(R.string.settings_ball_double_card),
        QuickEntryService.DOUBLE_RECORD to stringResource(R.string.settings_ball_double_record),
    )
    val hiddenSummary = when {
        !usageAccess -> stringResource(R.string.settings_ball_hidden_apps_permission)
        hiddenPackages.isEmpty() -> stringResource(R.string.settings_ball_hidden_apps_none)
        else -> pluralStringResource(
            R.plurals.settings_ball_hidden_apps_count,
            hiddenPackages.size,
            hiddenPackages.size,
        )
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_floating_ball),
                summary = stringResource(if (denied) R.string.quick_overlay_denied else R.string.quick_overlay_help),
                checked = enabled,
                startAction = startIcon(VoiceLinkIcon.Phone),
                switchColors = settingsSwitchColors(),
                modifier = Modifier.testTag("quick-ball"),
                onCheckedChange = { checked ->
                    if (!checked) {
                        context.stopService(Intent(context, QuickEntryService::class.java))
                        QuickEntryService.enabled(context, false); enabled = false; denied = false
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else requestOverlay()
                },
            )
            HorizontalDivider()
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
            HorizontalDivider()
            BasicComponent(
                title = stringResource(R.string.settings_ball_hidden_apps),
                summary = stringResource(R.string.settings_ball_hidden_apps_help),
                startAction = startIcon(VoiceLinkIcon.Phone),
                endActions = {
                    Text(
                        hiddenSummary,
                        fontSize = 13.sp,
                        color = if (usageAccess) MiuixTheme.colorScheme.onSurfaceSecondary else warningColor(),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        VoiceLinkIcon.ChevronRight.vector,
                        null,
                        Modifier.size(17.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                },
                onClick = {
                    if (usageAccess) hiddenAppsOpen = true
                    else usageAccessPermission.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                modifier = Modifier.testTag("ball-hidden-apps"),
            )
        }
        val minPeek = BallGeometry.effectivePeekFraction(size, BallGeometry.MIN_PEEK_FRACTION)
        LaunchedEffect(minPeek) {
            if (peek < minPeek) {
                peek = minPeek
                QuickEntryService.setPeekFraction(context, peek)
            }
        }
        val visible = BallGeometry.visibleDp(size, peek)
        val peekIcon = BallGeometry.iconDp(size, iconFraction, peek, peeked = true, keepWhole = keepWhole)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_ball_preview),
                        Modifier.weight(1f),
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    Button(
                        onClick = { QuickEntryService.resetPosition(context) },
                        enabled = enabled,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.testTag("ball-reset-position"),
                    ) {
                        Icon(
                            VoiceLinkIcon.Retry.vector, null, Modifier.size(15.dp),
                            tint = if (enabled) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .45f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.settings_ball_reset_position),
                            fontSize = 12.5.sp,
                            color = if (enabled) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .45f),
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .testTag("ball-preview"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(size.dp)
                            .shadow(6.dp, CircleShape)
                            .background(Color(backgroundColor), CircleShape)
                            .border(1.dp, Color(0x1F000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_sayin_brand_fyan_mark),
                            null,
                            Modifier.fillMaxSize(iconFraction),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(iconColor)),
                        )
                    }
                    Box(
                        Modifier.align(Alignment.CenterEnd)
                            .offset(x = size.dp * (1f - visible / size))
                            .size(size.dp)
                            .alpha(opacity / 100f)
                            .shadow(6.dp, CircleShape)
                            .background(Color(backgroundColor), CircleShape)
                            .border(1.dp, Color(0x1F000000), CircleShape),
                    )
                    Box(
                        Modifier.align(Alignment.CenterEnd)
                            .offset(x = ((peekIcon - visible) / 2f).dp)
                            .size(peekIcon.dp)
                            .alpha(opacity / 100f),
                    ) {
                        Image(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_sayin_brand_fyan_mark),
                            null,
                            Modifier.fillMaxSize(1f),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(iconColor)),
                        )
                    }
                }
                Text(stringResource(R.string.settings_ball_preview_expanded), fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Text(
                    stringResource(R.string.settings_ball_preview_peek, (peek * 100).roundToInt(),
                        visible.roundToInt(), opacity.roundToInt()),
                    fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
        }
        SettingsCard {
            BallColorRow(
                title = stringResource(R.string.settings_ball_background),
                options = backgroundOptions,
                selected = backgroundColor,
                tag = "ball-bg",
                onSelect = { color ->
                    backgroundColor = color
                    QuickEntryService.setBallColors(context, backgroundColor, iconColor)
                },
                onCustom = {
                    customTarget = BallColorTarget.Background
                    draftColor = backgroundColor
                },
            )
            HorizontalDivider()
            BallColorRow(
                title = stringResource(R.string.settings_ball_icon_color),
                options = iconOptions,
                selected = iconColor,
                tag = "ball-fg",
                onSelect = { color ->
                    iconColor = color
                    QuickEntryService.setBallColors(context, backgroundColor, iconColor)
                },
                onCustom = {
                    customTarget = BallColorTarget.Icon
                    draftColor = iconColor
                },
            )
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_ball_size),
                        Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(stringResource(R.string.settings_ball_size_value, size.roundToInt()), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Slider(
                    value = size,
                    onValueChange = { newSize ->
                        size = newSize
                        val newMin = BallGeometry.effectivePeekFraction(newSize, BallGeometry.MIN_PEEK_FRACTION)
                        if (peek < newMin) peek = newMin
                    },
                    valueRange = 44f..72f,
                    steps = 6,
                    onValueChangeFinished = {
                        QuickEntryService.setSize(context, size.roundToInt())
                        QuickEntryService.setPeekFraction(context, peek)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ball-size"),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_ball_peek),
                        Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.settings_ball_peek_value, (peek * 100).roundToInt(), visible.roundToInt()),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
                Slider(
                    value = peek,
                    onValueChange = { peek = it },
                    valueRange = minPeek..BallGeometry.MAX_PEEK_FRACTION,
                    onValueChangeFinished = { QuickEntryService.setPeekFraction(context, peek) },
                    modifier = Modifier.fillMaxWidth().testTag("ball-peek"),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_ball_icon_size),
                        Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(stringResource(R.string.settings_ball_icon_size_value, (iconFraction * 100).roundToInt()),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Slider(
                    value = iconFraction,
                    onValueChange = { iconFraction = it },
                    valueRange = BallGeometry.MIN_ICON_FRACTION..BallGeometry.MAX_ICON_FRACTION,
                    steps = 5,
                    onValueChangeFinished = { QuickEntryService.setIconFraction(context, iconFraction) },
                    modifier = Modifier.fillMaxWidth().testTag("ball-icon-size"),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_ball_opacity),
                        Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                    )
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
            HorizontalDivider()
            SwitchPreference(
                title = stringResource(R.string.settings_ball_keep_icon),
                summary = stringResource(R.string.settings_ball_keep_icon_summary),
                checked = keepWhole,
                switchColors = settingsSwitchColors(),
                modifier = Modifier.testTag("ball-keep-icon"),
                onCheckedChange = {
                    keepWhole = it
                    QuickEntryService.setKeepIconWhole(context, it)
                },
            )
        }
        NoticeText(stringResource(R.string.settings_ball_note))
        NoticeText(stringResource(R.string.settings_ball_fullscreen_note))
    }
    customTarget?.let { target ->
        WindowDialog(
            title = stringResource(
                if (target == BallColorTarget.Background) R.string.settings_ball_custom_background_title
                else R.string.settings_ball_custom_icon_title,
            ),
            show = true,
            onDismissRequest = { customTarget = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorPicker(
                    color = Color(draftColor),
                    onColorChanged = { draftColor = it.toArgb() },
                    modifier = Modifier.fillMaxWidth().testTag("ball-color-picker"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        stringResource(R.string.settings_ball_color_cancel),
                        onClick = { customTarget = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        stringResource(R.string.settings_ball_color_apply),
                        onClick = {
                            when (target) {
                                BallColorTarget.Background -> {
                                    backgroundColor = draftColor
                                    QuickEntryService.setBallColors(context, backgroundColor, iconColor)
                                }
                                BallColorTarget.Icon -> {
                                    iconColor = draftColor
                                    QuickEntryService.setBallColors(context, backgroundColor, iconColor)
                                }
                            }
                            customTarget = null
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
    HiddenAppsSheet(
        show = hiddenAppsOpen,
        selected = hiddenPackages,
        onDismiss = { hiddenAppsOpen = false },
        onToggle = { packageName ->
            hiddenPackages = if (packageName in hiddenPackages) {
                hiddenPackages - packageName
            } else {
                hiddenPackages + packageName
            }
            QuickEntryService.setHiddenPackages(context, hiddenPackages)
        },
    )
}

@Composable
private fun HiddenAppsSheet(
    show: Boolean,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    val sortPreferences = remember { context.getSharedPreferences("presentation", 0) }
    var sort by rememberSaveable {
        mutableStateOf(HiddenAppSort.from(sortPreferences.getString(HIDDEN_APPS_SORT_KEY, null)))
    }
    var direction by rememberSaveable {
        mutableStateOf(
            HiddenAppDirection.from(sortPreferences.getString(HIDDEN_APPS_DIRECTION_KEY, null))
                ?: sort.defaultDirection,
        )
    }
    var apps by remember { mutableStateOf<List<HiddenAppEntry>?>(null) }
    LaunchedEffect(show) {
        if (show && apps == null) {
            apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
    }
    OverlayBottomSheet(
        insideMargin = AppSheetInsideMargin,
        show = show,
        title = stringResource(R.string.settings_ball_hidden_apps),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WindowDropdownPreference(
                title = stringResource(R.string.settings_ball_hidden_apps_sort),
                items = HiddenAppSort.entries.map { stringResource(it.label) },
                selectedIndex = HiddenAppSort.entries.indexOf(sort),
                startAction = startIcon(VoiceLinkIcon.Filter),
                onSelectedIndexChange = { index ->
                    sort = HiddenAppSort.entries[index]
                    direction = sort.defaultDirection
                    sortPreferences.edit()
                        .putString(HIDDEN_APPS_SORT_KEY, sort.name)
                        .putString(HIDDEN_APPS_DIRECTION_KEY, direction.name)
                        .apply()
                },
                modifier = Modifier.testTag("hidden-apps-sort"),
            )
            TabRowWithContour(
                tabs = HiddenAppDirection.entries.map { stringResource(it.label) },
                selectedTabIndex = HiddenAppDirection.entries.indexOf(direction),
                onTabSelected = { index ->
                    direction = HiddenAppDirection.entries[index]
                    sortPreferences.edit().putString(HIDDEN_APPS_DIRECTION_KEY, direction.name).apply()
                },
                modifier = Modifier.padding(horizontal = 16.dp).testTag("hidden-apps-direction"),
            )
            val loaded = apps
            val ordered = loaded?.let { sortedHiddenApps(it, sort, direction, selected) }
            when {
                ordered == null -> Text(
                    stringResource(R.string.settings_ball_hidden_apps_loading),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                ordered.isEmpty() -> Text(
                    stringResource(R.string.settings_ball_hidden_apps_empty),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(ordered, key = { it.packageName }) { app ->
                        HiddenAppRow(
                            app = app,
                            checked = app.packageName in selected,
                            onToggle = { onToggle(app.packageName) },
                        )
                        if (app != ordered.last()) {
                            HorizontalDivider(Modifier.padding(start = 46.dp))
                        }
                    }
                }
            }
            TextButton(
                stringResource(R.string.settings_ball_hidden_apps_done),
                onClick = onDismiss,
                minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun HiddenAppRow(app: HiddenAppEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiddenAppIcon(app.packageName, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(app.label, fontSize = 14.5.sp, maxLines = 1)
            Text(
                app.packageName,
                fontSize = 10.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        CheckboxMark(checked)
    }
}

@Composable
private fun CheckboxMark(checked: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = if (checked) 0.dp else 1.5.dp,
                color = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(13.dp), tint = MiuixTheme.colorScheme.onPrimary)
        }
    }
}

private val hiddenAppIconCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>()

@Composable
private fun HiddenAppIcon(packageName: String, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        if (hiddenAppIconCache.containsKey(packageName)) {
            hiddenAppIconCache[packageName]
        } else {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(72, 72).asImageBitmap()
            }.getOrNull().also { hiddenAppIconCache[packageName] = it }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap, null,
            Modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh))
    }
}

private const val HIDDEN_APPS_SORT_KEY = "quick_ball_hidden_apps_sort"
private const val HIDDEN_APPS_DIRECTION_KEY = "quick_ball_hidden_apps_sort_direction"

private enum class HiddenAppSort(@StringRes val label: Int, val defaultDirection: HiddenAppDirection) {
    Name(R.string.hidden_apps_sort_name, HiddenAppDirection.Ascending),
    Recent(R.string.hidden_apps_sort_recent, HiddenAppDirection.Descending),
    Installed(R.string.hidden_apps_sort_installed, HiddenAppDirection.Descending),
    Selected(R.string.hidden_apps_sort_selected, HiddenAppDirection.Descending),
    ;

    companion object {
        fun from(value: String?): HiddenAppSort = entries.firstOrNull { it.name == value } ?: Name
    }
}

private enum class HiddenAppDirection(@StringRes val label: Int) {
    Ascending(R.string.hidden_apps_sort_ascending),
    Descending(R.string.hidden_apps_sort_descending),
    ;

    companion object {
        fun from(value: String?): HiddenAppDirection? = entries.firstOrNull { it.name == value }
    }
}

private data class HiddenAppEntry(
    val packageName: String,
    val label: String,
    val lastUsed: Long,
    val installedAt: Long,
)

private fun sortedHiddenApps(
    apps: List<HiddenAppEntry>,
    sort: HiddenAppSort,
    direction: HiddenAppDirection,
    selected: Set<String>,
): List<HiddenAppEntry> {
    val ascending = direction == HiddenAppDirection.Ascending
    fun byLabel(): Comparator<HiddenAppEntry> = compareBy { it.label.lowercase(Locale.ROOT) }
    return when (sort) {
        HiddenAppSort.Name -> if (ascending) apps.sortedWith(byLabel()) else apps.sortedWith(byLabel().reversed())
        HiddenAppSort.Recent -> apps.sortedWith(
            (if (ascending) compareBy<HiddenAppEntry> { it.lastUsed }
            else compareByDescending { it.lastUsed }).then(byLabel()),
        )
        HiddenAppSort.Installed -> apps.sortedWith(
            (if (ascending) compareBy<HiddenAppEntry> { it.installedAt }
            else compareByDescending { it.installedAt }).then(byLabel()),
        )
        HiddenAppSort.Selected -> apps.sortedWith(
            (if (ascending) compareBy<HiddenAppEntry> { it.packageName in selected }
            else compareByDescending { it.packageName in selected }).then(byLabel()),
        )
    }
}

private fun loadLaunchableApps(context: Context): List<HiddenAppEntry> {
    val manager = context.packageManager
    val installedPackages = runCatching {
        manager.getInstalledPackages(0).associateBy { it.packageName }
    }.getOrDefault(emptyMap())
    val usageManager = context.getSystemService(UsageStatsManager::class.java)
    val recent = if (usageManager != null && ForegroundApp.hasAccess(context)) {
        val end = System.currentTimeMillis()
        usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 30L * 24 * 60 * 60 * 1000, end)
            ?.associate { it.packageName to it.lastTimeUsed }
            .orEmpty()
    } else {
        emptyMap()
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            val installedAt = installedPackages[info.packageName]?.firstInstallTime ?: 0L
            HiddenAppEntry(
                info.packageName,
                info.loadLabel(manager).toString(),
                recent[info.packageName] ?: 0L,
                installedAt,
            )
        }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
}

@Composable
private fun BallColorRow(
    title: String,
    options: List<Pair<Int, Int>>,
    selected: Int,
    tag: String,
    onSelect: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    val customLabel = stringResource(R.string.settings_ball_custom_color)
    val customChosen = options.none { it.first == selected }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (color, nameRes) ->
                val label = stringResource(nameRes)
                val chosen = color == selected
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(Color(color))
                        .border(
                            if (chosen) 2.dp else 1.dp,
                            if (chosen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .clickable { onSelect(color) }
                        .testTag("$tag-${color.toUInt().toString(16)}")
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(
                        VoiceLinkIcon.Check.vector, null, Modifier.size(15.dp),
                        tint = if (Color(color).luminance() < .5f) Color.White else Color(0xFF252525),
                    )
                }
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (customChosen) Color(selected) else MiuixTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        if (customChosen) 2.dp else 1.dp,
                        if (customChosen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable(onClick = onCustom)
                    .testTag("$tag-custom")
                    .semantics { contentDescription = customLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (customChosen) {
                    Icon(
                        VoiceLinkIcon.Check.vector, null, Modifier.size(15.dp),
                        tint = if (Color(selected).luminance() < .5f) Color.White else Color(0xFF252525),
                    )
                } else {
                    Icon(
                        VoiceLinkIcon.Plus.vector, null, Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundPage(modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeText(stringResource(R.string.settings_background_body))
        TextButton(stringResource(R.string.settings_background_open_quick), onClick = {
            runCatching { context.startActivity(QuickActivity.intent(context)) }
        }, minHeight = 48.dp, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary())
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
            colors = ButtonDefaults.textButtonColorsPrimary(),
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
private fun StoragePage(session: SpeakSession, openModels: () -> Unit, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
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
    }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            DiagnosticRow(stringResource(R.string.settings_storage_data), sizes?.let { Formatter.formatFileSize(context, it.first) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_cache), sizes?.let { Formatter.formatFileSize(context, it.second) } ?: stringResource(R.string.settings_storage_calculating))
            DiagnosticRow(stringResource(R.string.settings_storage_used), sizes?.let { Formatter.formatFileSize(context, it.first + it.second) } ?: stringResource(R.string.settings_storage_calculating))
        }
        SettingsCard {
            ArrowPreference(
                title = stringResource(R.string.local_model_title),
                startAction = startIcon(VoiceLinkIcon.Computer),
                endActions = {
                    Text(if (session.localModels.initialized) Formatter.formatFileSize(context, session.localModels.installed.values.sum())
                        else stringResource(R.string.settings_storage_calculating))
                },
                onClick = openModels,
                modifier = Modifier.testTag("settings-local-models"),
            )
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
