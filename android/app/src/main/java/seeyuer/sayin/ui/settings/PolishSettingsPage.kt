package seeyuer.sayin.ui.settings

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.polish.*
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.plainTextButtonColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal class PolishEditorState(config: PolishConfig?, creating: Boolean = false) {
    private val original = config
    private val id = config?.id?.takeUnless { creating } ?: java.util.UUID.randomUUID().toString()
    private val revision = config?.revision?.takeUnless { creating } ?: 1L
    var name by mutableStateOf(config?.name.orEmpty())
    var provider by mutableStateOf(config?.provider ?: PolishProvider.DeepSeek)
    var protocol by mutableStateOf(config?.protocol ?: PolishProtocol.Chat)
    var baseUrl by mutableStateOf(config?.baseUrl ?: PolishConfig.preset(PolishProvider.DeepSeek).baseUrl)
    var model by mutableStateOf(config?.model ?: PolishConfig.preset(PolishProvider.DeepSeek).model)
    var key by mutableStateOf(config?.key.orEmpty())
    var apiVersion by mutableStateOf(config?.apiVersion.orEmpty())
    var reveal by mutableStateOf(false)
    var localError by mutableStateOf<Int?>(null)

    fun selectProvider(value: PolishProvider) {
        if (value == provider) return
        provider = value
        val preset = PolishConfig.presets.firstOrNull { it.provider == value } ?: PolishConfig.custom(protocol)
        protocol = preset.protocol
        baseUrl = preset.baseUrl
        model = preset.model
        apiVersion = preset.apiVersion
        key = ""
        localError = null
    }

    fun updateBaseUrl(value: String) {
        if (original != null && value.trim() != original.baseUrl && key.isNotEmpty()) key = ""
        baseUrl = value
        localError = null
    }

    fun selectProtocol(value: PolishProtocol) {
        if (original != null && value != original.protocol && key.isNotEmpty()) key = ""
        protocol = value
        apiVersion = if (value == PolishProtocol.Messages) "2023-06-01" else ""
    }

    fun build(): PolishConfig {
        val preset = PolishConfig.presets.firstOrNull { it.provider == provider }
        return (preset ?: PolishConfig.custom(protocol)).copy(
            id = id,
            name = name.trim(),
            revision = revision,
            protocol = if (preset != null) preset.protocol else protocol,
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            key = key.trim(),
            apiVersion = if ((preset?.protocol ?: protocol) == PolishProtocol.Messages) apiVersion.trim() else "",
        )
    }

    fun validate(): Int? = when {
        baseUrl.isBlank() || !baseUrl.trim().startsWith("https://") -> R.string.polish_invalid_url
        model.isBlank() -> R.string.polish_invalid_model
        key.isBlank() -> R.string.polish_required
        else -> null
    }
}

@Composable
internal fun PolishSettingsPage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState,
                                openServices: () -> Unit, openUsage: () -> Unit) {
    val manager = session.polish
    val config = manager.config
    val configs = manager.configurations
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card {
            if (configs.isNotEmpty()) {
                WindowDropdownPreference(
                    title = config?.let { configLabel(it) } ?: stringResource(R.string.polish_select_service),
                    summary = config?.model,
                    entry = DropdownEntry(items = configs.map { saved ->
                        DropdownItem(text = configLabel(saved), summary = saved.model,
                            selected = config?.id == saved.id,
                            onClick = { manager.selectConfig(saved.id) },
                            icon = { ProviderLogo(saved.provider, it.size(24.dp)) })
                    }),
                    showValue = false,
                    startAction = config?.let { current -> { ProviderLogo(current.provider, Modifier.size(26.dp)) } },
                )
            }
            BasicComponent(title = stringResource(R.string.polish_services), onClick = openServices,
                modifier = Modifier.testTag("polish-services"))
        }
        if (manager.configWriteFailed) Notice(stringResource(R.string.polish_save_failed), error = true)
        Card {
            SwitchPreference(title = stringResource(R.string.polish_auto),
                summary = stringResource(R.string.polish_auto_summary),
                checked = manager.autoPolishBound, enabled = config != null && !manager.saving,
                modifier = Modifier.testTag("polish-auto"), onCheckedChange = manager::updateAutoPolish)
            SwitchPreference(title = stringResource(R.string.polish_app_rules),
                summary = stringResource(R.string.polish_app_rules_summary), checked = manager.appRulesEnabled,
                modifier = Modifier.testTag("polish-app-rules"), onCheckedChange = manager::updateAppRules)
            SwitchPreference(title = stringResource(R.string.polish_streaming),
                summary = stringResource(R.string.polish_streaming_summary), checked = manager.streamingPreview,
                modifier = Modifier.testTag("polish-streaming"), onCheckedChange = manager::updateStreamingPreview)
        }
        val usageVersion by manager.usageVersion.collectAsState()
        val summary by produceState<PolishUsageReport?>(null, usageVersion) {
            try { value = manager.usageReport(30, includeDetails = false) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
        Card {
            BasicComponent(title = stringResource(R.string.polish_stats),
                summary = summary?.let { usageSummary(it) }, onClick = openUsage,
                modifier = Modifier.testTag("polish-usage"))
            summary?.let { UsageCosts(it) }
        }
        var targetDraft by remember { mutableStateOf(manager.targetLanguage) }
        TextField(targetDraft, {
            targetDraft = it.take(35)
            if (PolishPrompts.validateTargetLanguage(targetDraft) == null) manager.updateTargetLanguage(targetDraft)
        }, Modifier.fillMaxWidth().testTag("polish-default-language"),
            label = stringResource(R.string.polish_translation_language), singleLine = true)
        TextField(manager.customRewrite, manager::updateCustomRewrite,
            Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("polish-custom-rewrite"),
            label = stringResource(R.string.polish_custom_rewrite_label))
        TextField(manager.hotwords, manager::updateHotwords,
            Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("polish-hotwords"),
            label = stringResource(R.string.polish_hotwords))
        Notice(stringResource(R.string.polish_upload_note))
    }
}

@Composable
private fun configLabel(config: PolishConfig): String =
    config.name.ifBlank { stringResource(providerLabel(config.provider)) }

@Composable
private fun usageSummary(report: PolishUsageReport): String = stringResource(R.string.polish_usage_summary,
    report.totals.calls, report.totals.tokens.toString(), report.medianMillis)

@Composable
private fun UsageCosts(report: PolishUsageReport) {
    val locale = LocalConfiguration.current.locales[0]
    if (report.totals.unknownCost > 0 || report.costs.isNotEmpty()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            report.costs.forEach { amount ->
                val label = stringResource(if (amount.source == PolishCostSource.Reported.name)
                    R.string.polish_stats_cost_reported else R.string.polish_stats_cost_estimated)
                Notice(stringResource(R.string.polish_usage_amount, label,
                    String.format(locale, "%.6f", java.math.BigDecimal.valueOf(amount.micros, 6)), amount.currency))
            }
            if (report.totals.unknownCost > 0) Notice(stringResource(R.string.polish_usage_unknown_cost, report.totals.unknownCost))
        }
    }
}

@Composable
internal fun PolishUsagePage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val manager = session.polish
    val version by manager.usageVersion.collectAsState()
    var days by remember { mutableIntStateOf(30) }
    var purpose by remember { mutableStateOf<String?>(null) }
    var service by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val state by produceState<Result<PolishUsageReport>?>(null, version, days, purpose, service, model, page) {
        value = null
        value = try { Result.success(manager.usageReport(days, purpose, service, model, page)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    val report = state?.getOrNull()
    val configurations = manager.configurations
    val all = stringResource(R.string.polish_usage_all)
    val purposes = listOf(null, "polish", "translation", "test")
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WindowDropdownPreference(title = stringResource(R.string.polish_usage_period),
            items = listOf(R.string.polish_usage_today, R.string.polish_usage_7d, R.string.polish_usage_30d).map { stringResource(it) },
            selectedIndex = listOf(1, 7, 30).indexOf(days),
            onSelectedIndexChange = { days = listOf(1, 7, 30)[it]; page = 0; model = null })
        WindowDropdownPreference(title = stringResource(R.string.polish_usage_purpose),
            items = purposes.map { if (it == null) all else stringResource(usagePurposeLabel(it)) },
            selectedIndex = purposes.indexOf(purpose),
            onSelectedIndexChange = { purpose = purposes[it]; page = 0 })
        if (configurations.isNotEmpty()) {
            WindowDropdownPreference(title = stringResource(R.string.polish_usage_service),
                items = listOf(all) + configurations.map { configLabel(it) },
                selectedIndex = configurations.indexOfFirst { it.id == service } + 1,
                onSelectedIndexChange = { service = configurations.getOrNull(it - 1)?.id; page = 0; model = null })
        }
        val models = report?.models.orEmpty()
        if (models.isNotEmpty()) {
            WindowDropdownPreference(title = stringResource(R.string.polish_model), items = listOf(all) + models,
                selectedIndex = models.indexOf(model) + 1,
                onSelectedIndexChange = { model = models.getOrNull(it - 1); page = 0 })
        }
        when {
            state?.isFailure == true -> Notice(stringResource(R.string.polish_usage_load_failed), error = true)
            report == null -> Notice(stringResource(R.string.polish_usage_loading))
            report.totals.calls == 0L -> Notice(stringResource(R.string.polish_stats_empty))
            else -> {
                Notice(usageSummary(report))
                UsageCosts(report)
                report.entries.forEach { StatsRow(it, locale) }
                if (report.totals.calls > POLISH_USAGE_PAGE_SIZE) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(stringResource(R.string.polish_usage_previous), { page-- }, minHeight = 48.dp, enabled = page > 0)
                        TextButton(stringResource(R.string.polish_usage_next), { page++ }, minHeight = 48.dp,
                            enabled = (page + 1L) * POLISH_USAGE_PAGE_SIZE < report.totals.calls)
                    }
                }
            }
        }
        if (report != null) TextButton(stringResource(R.string.polish_stats_clear), { confirmClear = true }, minHeight = 48.dp)
    }
    WindowDialog(title = stringResource(R.string.polish_stats_clear), summary = stringResource(R.string.polish_stats_clear_summary),
        show = confirmClear, onDismissRequest = { confirmClear = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.polish_cancel_action), { confirmClear = false }, Modifier.weight(1f), minHeight = 48.dp)
            TextButton(stringResource(R.string.polish_stats_clear), {
                confirmClear = false
                page = 0
                manager.clearStats()
            }, Modifier.weight(1f), minHeight = 48.dp)
        }
    }
}

@Composable
internal fun PolishServicesPage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val manager = session.polish
    var editingId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val config = manager.configurations.firstOrNull { it.id == editingId }
    if (!creating && config == null) {
        Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (manager.state == PolishConfigState.Corrupt) Notice(stringResource(R.string.polish_config_corrupt), error = true)
            manager.configurations.forEach { saved ->
                BasicComponent(
                    title = configLabel(saved),
                    summary = saved.model,
                    startAction = { ProviderLogo(saved.provider, Modifier.size(24.dp)) },
                    onClick = { editingId = saved.id; manager.resetEditorResults() },
                )
            }
            Button(onClick = { creating = true; manager.resetEditorResults() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("polish-add"), enabled = !manager.saving) {
                Text(stringResource(R.string.polish_add_config))
            }
        }
        return
    }
    fun closeEditor() { editingId = null; creating = false; manager.resetEditorResults() }
    BackHandler { if (!manager.saving) closeEditor() }
    val editor = remember(creating, config?.id, config?.revision) { PolishEditorState(config, creating) }
    val test = manager.testResultFor(editor.build())
    val saving = manager.saving
    val locale = LocalConfiguration.current.locales[0]
    var confirmDelete by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    val catalog by manager.catalogState.collectAsState()
    val candidates = (catalog?.first?.modelsByProvider?.get(editor.build().officialCatalogProvider).orEmpty() +
        manager.modelsFor(editor.build()).map { PolishCatalogModel(it, it, "custom", false, null, null, null) })
        .distinctBy { it.id }
    val providers = listOf(PolishProvider.DeepSeek, PolishProvider.Qwen, PolishProvider.OpenAi,
        PolishProvider.Anthropic, PolishProvider.Custom)
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(stringResource(R.string.polish_services), { closeEditor() }, minHeight = 48.dp,
            enabled = !saving, colors = plainTextButtonColors())
        Card {
            val providerItems = providers.map { provider ->
                DropdownItem(
                    text = stringResource(providerLabel(provider)),
                    selected = provider == editor.provider,
                    onClick = { editor.selectProvider(provider) },
                    icon = { iconModifier -> ProviderLogo(provider, iconModifier.size(24.dp)) },
                )
            }
            WindowDropdownPreference(
                entry = DropdownEntry(items = providerItems),
                title = stringResource(providerLabel(editor.provider)),
                summary = editor.model,
                startAction = { ProviderLogo(editor.provider, Modifier.size(26.dp)) },
                showValue = false,
            )
            if (editor.provider == PolishProvider.Custom) {
                WindowDropdownPreference(
                    title = stringResource(R.string.polish_protocol),
                    items = listOf(stringResource(R.string.polish_protocol_chat), stringResource(R.string.polish_protocol_messages)),
                    selectedIndex = if (editor.protocol == PolishProtocol.Chat) 0 else 1,
                    onSelectedIndexChange = {
                        editor.selectProtocol(if (it == 0) PolishProtocol.Chat else PolishProtocol.Messages)
                    },
                )
            } else {
                BasicComponent(
                    title = stringResource(R.string.polish_protocol),
                    summary = stringResource(if ((PolishConfig.presets.firstOrNull { p -> p.provider == editor.provider }?.protocol ?: editor.protocol) == PolishProtocol.Chat)
                        R.string.polish_protocol_chat else R.string.polish_protocol_messages),
                )
            }
        }
        Card {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(editor.name, { editor.name = it.take(80) },
                    Modifier.fillMaxWidth().testTag("polish-config-name"),
                    label = stringResource(R.string.polish_config_name), singleLine = true, enabled = !saving)
                TextField(editor.baseUrl, { editor.updateBaseUrl(it.take(300)) },
                    Modifier.fillMaxWidth().testTag("polish-base-url"), label = stringResource(R.string.polish_base_url),
                    singleLine = true, enabled = !saving)
                BasicComponent(
                    title = stringResource(R.string.polish_model),
                    summary = editor.model.ifBlank { null },
                    onClick = { modelQuery = ""; showModels = true },
                    modifier = Modifier.testTag("polish-model"),
                )
                if ((PolishConfig.presets.firstOrNull { it.provider == editor.provider }?.protocol ?: editor.protocol) == PolishProtocol.Messages &&
                    editor.provider == PolishProvider.Custom) {
                    TextField(editor.apiVersion, { editor.apiVersion = it.take(40) },
                        Modifier.fillMaxWidth().testTag("polish-api-version"), label = stringResource(R.string.polish_api_version),
                        singleLine = true, enabled = !saving)
                }
                TextField(editor.key, { editor.key = it.take(8192); editor.localError = null },
                    Modifier.fillMaxWidth().testTag("polish-key"), label = stringResource(R.string.polish_key),
                    singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    visualTransformation = if (editor.reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ editor.reveal = !editor.reveal }, Modifier.size(48.dp)) {
                            Icon((if (editor.reveal) VoiceLinkIcon.EyeOff else VoiceLinkIcon.Eye).vector,
                                stringResource(if (editor.reveal) R.string.polish_hide_key else R.string.polish_show_key),
                                Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                        }
                    })
            }
        }
        editor.localError?.let { Text(stringResource(it), fontSize = 12.sp, color = MiuixTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({
                val error = editor.validate()
                if (error != null) { editor.localError = error; return@Button }
                session.polish.test(editor.build())
            }, Modifier.weight(1f).testTag("polish-test"), enabled = !saving && test != PolishTestState.Testing) {
                Text(stringResource(if (test == PolishTestState.Testing) R.string.polish_testing else R.string.polish_test))
            }
            Button({
                val error = editor.validate()
                if (error != null) { editor.localError = error; return@Button }
                session.polish.save(editor.build()) { error ->
                    if (error == null) closeEditor() else editor.localError = R.string.polish_save_failed
                }
            }, Modifier.weight(1f).testTag("polish-save"), enabled = !saving) {
                Text(stringResource(if (saving) R.string.polish_saving else R.string.polish_save))
            }
        }
        when (test) {
            PolishTestState.Testing -> Notice(stringResource(R.string.polish_testing))
            is PolishTestState.Success -> Notice(stringResource(R.string.polish_test_ready, test.durationMillis.toInt()))
            is PolishTestState.Failure -> Notice(stringResource(R.string.polish_test_failed, stringResource(test.kind.labelRes())), error = true)
            PolishTestState.Idle -> Unit
        }
        Text(stringResource(R.string.polish_key_binding_note), fontSize = 11.5.sp, lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (!creating && config != null) {
            TextButton(stringResource(R.string.polish_delete_config), { confirmDelete = true },
                minHeight = 48.dp, enabled = !saving, colors = plainTextButtonColors())
        }
    }
    if (showModels) {
        val filtered = if (modelQuery.isBlank()) candidates
        else candidates.filter {
            it.id.contains(modelQuery, ignoreCase = true) || it.name.contains(modelQuery, ignoreCase = true)
        }
        OverlayBottomSheet(show = true, onDismissRequest = { showModels = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .imePadding().navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.polish_catalog_title), style = MiuixTheme.textStyles.title2)
                TextField(modelQuery, { modelQuery = it.take(120) }, Modifier.fillMaxWidth(),
                    label = stringResource(R.string.polish_search_model), singleLine = true)
                if (editor.build().officialCatalogProvider != null) {
                    TextButton(stringResource(if (manager.catalogChecking) R.string.polish_catalog_refreshing else R.string.polish_catalog_refresh),
                        { manager.refreshCatalog() }, minHeight = 48.dp, enabled = !manager.catalogChecking)
                    val checked = catalog?.second ?: 0L
                    if (checked > 0) Notice(stringResource(R.string.polish_catalog_checked,
                        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(checked))))
                    if (manager.catalogError != null) Notice(stringResource(R.string.polish_catalog_refresh_failed), error = true)
                } else if (editor.provider == PolishProvider.Custom) {
                    TextButton(stringResource(if (manager.customModelsLoading) R.string.polish_fetching_models else R.string.polish_fetch_models),
                        { manager.refreshCustomModels(editor.build()) }, minHeight = 48.dp,
                        enabled = !manager.customModelsLoading && editor.key.isNotBlank())
                    if (manager.customModelsErrorFor(editor.build())) Notice(stringResource(R.string.polish_models_refresh_failed), error = true)
                }
                if (modelQuery.isNotBlank() && filtered.none { it.id == modelQuery.trim() }) {
                    TextButton(stringResource(R.string.polish_use_model_id, modelQuery.trim()), {
                        editor.model = modelQuery.trim()
                        editor.localError = null
                        showModels = false
                    }, minHeight = 48.dp)
                }
                if (filtered.isEmpty()) Notice(stringResource(R.string.polish_catalog_empty))
                filtered.forEach { model ->
                    BasicComponent(title = model.name, summary = model.id, onClick = {
                        editor.model = model.id
                        editor.localError = null
                        showModels = false
                    }, endActions = {
                        if (editor.model == model.id) Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(20.dp))
                    })
                }
                TextButton(stringResource(R.string.polish_cancel_action), { showModels = false },
                    Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
            }
        }
    }
    WindowDialog(
        title = stringResource(R.string.polish_delete_config),
        summary = stringResource(R.string.polish_delete_config_confirm),
        show = confirmDelete,
        onDismissRequest = { confirmDelete = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.polish_cancel_action), { confirmDelete = false },
                modifier = Modifier.weight(1f), minHeight = 48.dp)
            TextButton(stringResource(R.string.polish_delete_config_action), {
                confirmDelete = false
                config?.let { session.polish.deleteConfig(it.id) { error ->
                    if (error == null) closeEditor() else editor.localError = R.string.polish_save_failed
                } }
            }, modifier = Modifier.weight(1f).testTag("polish-delete-confirm"), minHeight = 48.dp,
                colors = plainTextButtonColors())
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
}

private fun usagePurposeLabel(purpose: String): Int = when (purpose) {
    "translation" -> R.string.polish_usage_purpose_translation
    "test" -> R.string.polish_usage_purpose_test
    else -> R.string.polish_usage_purpose_polish
}

@Composable
private fun StatsRow(entry: PolishStatsEntry, locale: Locale) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.polish_stats_entry_title, entry.provider, entry.responseModel ?: entry.model) + " · " +
                stringResource(usagePurposeLabel(entry.purpose)), Modifier.weight(1f), fontSize = 12.sp)
            Text(SimpleDateFormat("MM-dd HH:mm", locale).format(Date(entry.timestamp)),
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Text(stringResource(R.string.polish_stats_tokens, entry.inputTokens?.toString() ?: "?", entry.outputTokens?.toString() ?: "?"),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(R.string.polish_stats_duration, entry.durationMillis.toInt()) + " · " +
            stringResource(R.string.polish_stats_outcome,
                stringResource(when (entry.outcome) {
                    "success", "test" -> R.string.polish_stats_success
                    "unsent" -> R.string.polish_stats_unsent
                    else -> R.string.polish_stats_failure
                })),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        val cost = if (entry.costMicros != null && entry.costCurrency != null) {
            stringResource(
                if (entry.costSource == PolishCostSource.Reported) R.string.polish_stats_cost_reported
                else R.string.polish_stats_cost_estimated,
                String.format(locale, "%.6f", entry.costMicros / 1_000_000.0),
                entry.costCurrency,
            )
        } else stringResource(R.string.polish_stats_cost_unknown)
        Text(cost, fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

internal fun providerLabel(provider: PolishProvider): Int = when (provider) {
    PolishProvider.DeepSeek -> R.string.polish_provider_deepseek
    PolishProvider.Qwen -> R.string.polish_provider_qwen
    PolishProvider.OpenAi -> R.string.polish_provider_openai
    PolishProvider.Anthropic -> R.string.polish_provider_anthropic
    PolishProvider.Custom -> R.string.polish_provider_custom
}

private fun PolishFailure.labelRes(): Int = when (this) {
    PolishFailure.MissingConfig -> R.string.polish_error_missing
    PolishFailure.Unauthorized -> R.string.polish_error_unauthorized
    PolishFailure.RateLimited -> R.string.polish_error_rate
    PolishFailure.Timeout -> R.string.polish_error_timeout
    PolishFailure.Network -> R.string.polish_error_network
    PolishFailure.Server -> R.string.polish_error_server
    PolishFailure.InvalidResponse -> R.string.polish_error_invalid
    PolishFailure.Truncated -> R.string.polish_error_truncated
    PolishFailure.Refused -> R.string.polish_error_refused
    PolishFailure.TooLarge -> R.string.polish_error_too_large
    PolishFailure.Unknown -> R.string.polish_error_unknown
}
