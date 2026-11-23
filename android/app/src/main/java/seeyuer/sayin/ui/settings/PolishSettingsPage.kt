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
import seeyuer.sayin.ui.AppSheetInsideMargin
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
                                openServices: () -> Unit, openUsage: () -> Unit,
                                openApps: () -> Unit = {}, openTerms: () -> Unit = {}, openPrompt: () -> Unit = {}) {
    val manager = session.polish
    val config = manager.config
    val configs = manager.configurations
    var confirmAuto by remember { mutableStateOf(false) }
    var showPrice by remember { mutableStateOf(false) }
    var displayCurrency by remember { mutableStateOf("USD") }
    val currentTest = config?.let { manager.testResultFor(it) } ?: PolishTestState.Idle
    val locale = LocalConfiguration.current.locales[0]
    val catalogState by manager.catalogState.collectAsState()
    val catalog = catalogState?.first
    val priceEntry = config?.let { current ->
        catalog?.modelsFor(current.provider)?.firstOrNull { it.id == current.model }
    }
    val exchangeRate = manager.exchangeRate
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card {
            if (configs.isNotEmpty()) {
                WindowDropdownPreference(
                    title = config?.let { configLabel(it) } ?: stringResource(R.string.polish_select_service),
                    summary = config?.let { "${it.model} · ${testSummary(currentTest)}" },
                    entry = DropdownEntry(items = configs.map { saved ->
                        DropdownItem(text = configLabel(saved), summary = saved.model,
                            selected = config?.id == saved.id,
                            onClick = { manager.selectConfig(saved.id) },
                            icon = { ProviderLogo(saved.provider, it.size(24.dp)) })
                    }),
                    showValue = false,
                    startAction = config?.let { current -> { ProviderLogo(current.provider, Modifier.size(26.dp)) } },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(stringResource(R.string.polish_test), {
                        config?.let { manager.test(it) }
                    }, minHeight = 44.dp, enabled = config != null && !manager.saving &&
                        currentTest != PolishTestState.Testing, colors = plainTextButtonColors())
                }
                BasicComponent(
                    title = stringResource(R.string.polish_model_price),
                    summary = if (priceEntry == null) stringResource(R.string.polish_price_unknown)
                    else priceSummary(priceEntry, displayCurrency, exchangeRate),
                    onClick = {
                        showPrice = true
                        if (displayCurrency == "CNY") manager.ensureExchangeRate()
                    },
                    modifier = Modifier.testTag("polish-price"),
                )
            } else {
                BasicComponent(
                    title = stringResource(R.string.polish_add_config),
                    summary = stringResource(R.string.polish_auto_unbound),
                    onClick = openServices,
                    modifier = Modifier.testTag("polish-services"),
                )
            }
            if (configs.isNotEmpty()) {
                BasicComponent(title = stringResource(R.string.polish_services), onClick = openServices,
                    modifier = Modifier.testTag("polish-services"))
            }
        }
        if (manager.configWriteFailed) {
            Notice(stringResource(R.string.polish_save_failed), error = true)
            TextButton(stringResource(R.string.polish_reload_config), { manager.reload() },
                minHeight = 44.dp, colors = plainTextButtonColors())
        }
        Card {
            SwitchPreference(title = stringResource(R.string.polish_auto),
                summary = stringResource(R.string.polish_auto_summary),
                checked = manager.autoPolishBound, enabled = config != null && !manager.saving,
                modifier = Modifier.testTag("polish-auto"), onCheckedChange = { enabled ->
                    if (enabled && !manager.autoPolishBound) confirmAuto = true else manager.updateAutoPolish(enabled)
                })
            BasicComponent(
                title = stringResource(R.string.polish_app_rules),
                summary = stringResource(R.string.polish_app_rules_summary),
                onClick = openApps,
                modifier = Modifier.testTag("polish-app-rules"),
            )
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
        Card {
            BasicComponent(
                title = stringResource(R.string.polish_terms_title),
                summary = stringResource(R.string.polish_terms_format),
                onClick = openTerms,
                modifier = Modifier.testTag("polish-terms"),
            )
            BasicComponent(
                title = stringResource(R.string.polish_prompt_title),
                summary = stringResource(R.string.polish_prompt_summary),
                onClick = openPrompt,
                modifier = Modifier.testTag("polish-prompt"),
            )
        }
        Notice(stringResource(R.string.polish_upload_note))
    }
    if (showPrice) {
        OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = true, onDismissRequest = { showPrice = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().imePadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.polish_model_price), style = MiuixTheme.textStyles.title2)
                if (priceEntry == null) {
                    Notice(stringResource(R.string.polish_price_manual))
                } else {
                    WindowDropdownPreference(
                        title = stringResource(R.string.polish_currency),
                        items = listOf(stringResource(R.string.polish_currency_usd),
                            stringResource(R.string.polish_currency_cny)),
                        selectedIndex = if (displayCurrency == "USD") 0 else 1,
                        onSelectedIndexChange = {
                            displayCurrency = if (it == 0) "USD" else "CNY"
                            if (displayCurrency == "CNY") manager.ensureExchangeRate()
                        },
                    )
                    Text(stringResource(R.string.polish_price_reference), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    priceLine(stringResource(R.string.polish_price_input), priceEntry.inputUsdPerMillion,
                        displayCurrency, exchangeRate)
                    priceLine(stringResource(R.string.polish_price_output), priceEntry.outputUsdPerMillion,
                        displayCurrency, exchangeRate)
                    priceLine(stringResource(R.string.polish_price_cache_read), priceEntry.cacheReadUsdPerMillion,
                        displayCurrency, exchangeRate)
                    priceLine(stringResource(R.string.polish_price_cache_write), priceEntry.cacheWriteUsdPerMillion,
                        displayCurrency, exchangeRate)
                    if (priceEntry.tiers.isNotEmpty()) {
                        Text(stringResource(R.string.polish_price_tiers), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        priceEntry.tiers.forEach { tier ->
                            Text(stringResource(R.string.polish_price_tier_threshold, tier.minContext), fontSize = 11.sp)
                            priceLine(stringResource(R.string.polish_price_input), tier.rates.input,
                                displayCurrency, exchangeRate)
                            priceLine(stringResource(R.string.polish_price_output), tier.rates.output,
                                displayCurrency, exchangeRate)
                        }
                    }
                    priceEntry.schedule?.let { schedule ->
                        Text(stringResource(R.string.polish_price_schedule), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text(stringResource(R.string.polish_price_schedule_line, schedule.zoneId, schedule.windows.size), fontSize = 11.sp)
                    }
                    Notice(stringResource(R.string.polish_price_catalog,
                        catalog?.version?.take(12) ?: "?"))
                }
                if (displayCurrency == "CNY") {
                    if (manager.exchangeRateLoading) Notice(stringResource(R.string.polish_testing))
                    else if (exchangeRate != null) Notice(stringResource(R.string.polish_exchange_source,
                        exchangeRate.cnyPerUsd.toPlainString(),
                        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(exchangeRate.fetchedAt))))
                    else if (manager.exchangeRateError != null) Notice(stringResource(R.string.polish_exchange_failed), error = true)
                    TextButton(stringResource(R.string.polish_catalog_refresh), { manager.ensureExchangeRate(true) },
                        minHeight = 44.dp, colors = plainTextButtonColors())
                }
                TextButton(stringResource(R.string.polish_cancel_action), { showPrice = false },
                    Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
            }
        }
    }
    WindowDialog(
        title = stringResource(R.string.polish_auto_confirm_title),
        summary = stringResource(R.string.polish_auto_confirm_summary),
        show = confirmAuto,
        onDismissRequest = { confirmAuto = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.polish_cancel_action), { confirmAuto = false },
                Modifier.weight(1f), minHeight = 48.dp)
            TextButton(stringResource(R.string.polish_auto), {
                confirmAuto = false
                manager.updateAutoPolish(true)
            }, Modifier.weight(1f).testTag("polish-auto-confirm"), minHeight = 48.dp,
                colors = plainTextButtonColors())
        }
    }
}

@Composable
private fun testSummary(state: PolishTestState): String = when (state) {
    PolishTestState.Idle -> stringResource(R.string.polish_test_idle)
    PolishTestState.Testing -> stringResource(R.string.polish_testing)
    is PolishTestState.Success -> stringResource(R.string.polish_test_last_success, state.durationMillis.toInt())
    is PolishTestState.Failure -> stringResource(R.string.polish_test_last_failure,
        stringResource(state.kind.labelRes()))
}

@Composable
private fun configLabel(config: PolishConfig): String =
    config.name.ifBlank { stringResource(providerLabel(config.provider)) }

@Composable
private fun usageSummary(report: PolishUsageReport): String = stringResource(R.string.polish_usage_summary,
    report.totals.calls, report.totals.tokens.toString(), report.medianMillis)

@Composable
private fun UsageCosts(report: PolishUsageReport, displayCurrency: String = "USD",
                             rate: PolishExchangeRate? = null) {
    val locale = LocalConfiguration.current.locales[0]
    if (report.totals.unknownCost > 0 || report.costs.isNotEmpty()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            report.costs.forEach { amount ->
                val (text, currency) = convertedCost(amount, displayCurrency, rate, locale)
                Notice(stringResource(if (amount.source == PolishCostSource.Reported.name)
                    R.string.polish_stats_cost_reported else R.string.polish_stats_cost_estimated, text, currency))
            }
            if (report.totals.unknownCost > 0) Notice(stringResource(R.string.polish_usage_unknown_cost, report.totals.unknownCost))
            if (displayCurrency == "CNY") {
                if (rate != null) Notice(stringResource(R.string.polish_exchange_source,
                    rate.cnyPerUsd.toPlainString(),
                    SimpleDateFormat("MM-dd HH:mm", locale).format(Date(rate.fetchedAt))))
                else Notice(stringResource(R.string.polish_exchange_failed), error = true)
            }
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
    var displayCurrency by remember { mutableStateOf("USD") }
    var expandedCallId by remember { mutableStateOf<String?>(null) }
    val exchangeRate = manager.exchangeRate
    var showFilters by remember { mutableStateOf(false) }
    var draftPurpose by remember { mutableStateOf<String?>(null) }
    var draftService by remember { mutableStateOf<String?>(null) }
    var draftModel by remember { mutableStateOf<String?>(null) }
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
    val filterCount = listOf(purpose, service, model).count { it != null }
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRowWithContour(
            tabs = listOf(R.string.polish_usage_today, R.string.polish_usage_7d, R.string.polish_usage_30d)
                .map { stringResource(it) },
            selectedTabIndex = listOf(1, 7, 30).indexOf(days).coerceAtLeast(0),
            onTabSelected = { days = listOf(1, 7, 30)[it]; page = 0; model = null },
            modifier = Modifier.fillMaxWidth().testTag("polish-usage-period"),
            height = 44.dp,
            cornerRadius = 14.dp,
        )
        when {
            state?.isFailure == true -> Notice(stringResource(R.string.polish_usage_load_failed), error = true)
            report == null -> Notice(stringResource(R.string.polish_usage_loading))
            report.totals.calls == 0L -> Notice(stringResource(R.string.polish_stats_empty))
            else -> {
                Card {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            UsageMetric(stringResource(R.string.polish_usage_calls), report.totals.calls.toString())
                            UsageMetric(stringResource(R.string.polish_usage_token_label),
                                report.totals.tokens.takeIf { it > 0 }?.toString() ?: stringResource(R.string.polish_usage_unknown_tokens))
                            UsageMetric(stringResource(R.string.polish_usage_median),
                                report.medianMillis.takeIf { it > 0 }?.let { "$it ms" } ?: "—")
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        UsageCosts(report, displayCurrency, exchangeRate)
                        if (displayCurrency == "CNY" && exchangeRate == null && !manager.exchangeRateLoading) {
                            TextButton(stringResource(R.string.polish_catalog_refresh), { manager.ensureExchangeRate(true) },
                                minHeight = 36.dp, colors = plainTextButtonColors())
                        }
                    }
                }
                TabRowWithContour(
                    tabs = listOf(stringResource(R.string.polish_currency_usd), stringResource(R.string.polish_currency_cny)),
                    selectedTabIndex = if (displayCurrency == "USD") 0 else 1,
                    onTabSelected = { displayCurrency = if (it == 0) "USD" else "CNY"; if (displayCurrency == "CNY") manager.ensureExchangeRate() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 36.dp,
                    cornerRadius = 12.dp,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.polish_usage_details), Modifier.weight(1f), fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    TextButton(stringResource(
                        if (filterCount > 0) R.string.polish_usage_filter_count else R.string.polish_usage_filter,
                        filterCount,
                    ), {
                        draftPurpose = purpose; draftService = service; draftModel = model
                        showFilters = true
                    }, minHeight = 44.dp, colors = plainTextButtonColors())
                }
                report.entries.groupBy {
                    SimpleDateFormat("yyyy-MM-dd", locale).format(Date(it.timestamp))
                }.forEach { (day, entries) ->
                    Text(SimpleDateFormat("MM-dd", locale).format(Date(entries.first().timestamp)),
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 4.dp))
                    entries.forEach { StatsRow(it, locale, displayCurrency, exchangeRate,
                        expanded = expandedCallId == it.callId,
                        onToggle = { expandedCallId = if (expandedCallId == it.callId) null else it.callId }) }
                }
                if (report.totals.calls > POLISH_USAGE_PAGE_SIZE) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(stringResource(R.string.polish_usage_previous), { page-- }, minHeight = 48.dp, enabled = page > 0)
                        TextButton(stringResource(R.string.polish_usage_next), { page++ }, minHeight = 48.dp,
                            enabled = (page + 1L) * POLISH_USAGE_PAGE_SIZE < report.totals.calls)
                    }
                }
            }
        }
    }
    if (showFilters) {
        OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = true, onDismissRequest = { showFilters = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.polish_usage_filter), style = MiuixTheme.textStyles.title2)
                WindowDropdownPreference(title = stringResource(R.string.polish_usage_purpose),
                    items = purposes.map { if (it == null) all else stringResource(usagePurposeLabel(it)) },
                    selectedIndex = purposes.indexOf(draftPurpose),
                    onSelectedIndexChange = { draftPurpose = purposes[it] })
                WindowDropdownPreference(title = stringResource(R.string.polish_usage_service),
                    items = listOf(all) + configurations.map { configLabel(it) },
                    selectedIndex = configurations.indexOfFirst { it.id == draftService } + 1,
                    onSelectedIndexChange = { draftService = configurations.getOrNull(it - 1)?.id; draftModel = null })
                val models = report?.models.orEmpty()
                if (models.isNotEmpty()) {
                    WindowDropdownPreference(title = stringResource(R.string.polish_model), items = listOf(all) + models,
                        selectedIndex = models.indexOf(draftModel) + 1,
                        onSelectedIndexChange = { draftModel = models.getOrNull(it - 1) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(stringResource(R.string.polish_usage_reset_filters), {
                        draftPurpose = null; draftService = null; draftModel = null
                    }, Modifier.weight(1f), minHeight = 48.dp)
                    TextButton(stringResource(R.string.polish_usage_apply_filters), {
                        purpose = draftPurpose; service = draftService; model = draftModel; page = 0
                        showFilters = false
                    }, Modifier.weight(1f), minHeight = 48.dp, colors = plainTextButtonColors())
                }
                TextButton(stringResource(R.string.polish_cancel_action), { showFilters = false },
                    Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
            }
        }
    }
}

@Composable
private fun UsageMetric(label: String, value: String) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(label, fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
    var showAdvanced by remember(creating, config?.id) { mutableStateOf(false) }
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
        }
        Card {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(editor.name, { editor.name = it.take(80) },
                    Modifier.fillMaxWidth().testTag("polish-config-name"),
                    label = stringResource(R.string.polish_config_name), singleLine = true, enabled = !saving)
                BasicComponent(
                    title = stringResource(R.string.polish_model),
                    summary = editor.model.ifBlank { null },
                    onClick = { modelQuery = ""; showModels = true },
                    modifier = Modifier.testTag("polish-model"),
                )
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
                BasicComponent(
                    title = stringResource(R.string.polish_advanced),
                    summary = editor.baseUrl,
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.testTag("polish-advanced"),
                )
                if (showAdvanced) {
                    TextField(editor.baseUrl, { editor.updateBaseUrl(it.take(300)) },
                        Modifier.fillMaxWidth().testTag("polish-base-url"), label = stringResource(R.string.polish_base_url),
                        singleLine = true, enabled = !saving)
                    if (editor.provider == PolishProvider.Custom) {
                        WindowDropdownPreference(
                            title = stringResource(R.string.polish_protocol),
                            items = listOf(stringResource(R.string.polish_protocol_chat),
                                stringResource(R.string.polish_protocol_messages)),
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
                    if ((PolishConfig.presets.firstOrNull { it.provider == editor.provider }?.protocol ?: editor.protocol) == PolishProtocol.Messages &&
                        editor.provider == PolishProvider.Custom) {
                        TextField(editor.apiVersion, { editor.apiVersion = it.take(40) },
                            Modifier.fillMaxWidth().testTag("polish-api-version"), label = stringResource(R.string.polish_api_version),
                            singleLine = true, enabled = !saving)
                    }
                }
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
        OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = true, onDismissRequest = { showModels = false }) {
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
                        val input = model.inputUsdPerMillion
                        val output = model.outputUsdPerMillion
                        if (input != null && output != null) {
                            Text(stringResource(R.string.polish_price_candidate_line, formatPrice(input, "USD", null), formatPrice(output, "USD", null)),
                                fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        }
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

private fun convertedCost(amount: PolishUsageAmount, displayCurrency: String,
                          rate: PolishExchangeRate?, locale: Locale): Pair<String, String> {
    val original = java.math.BigDecimal.valueOf(amount.micros, 6)
    if (displayCurrency == "CNY" && amount.currency == "USD" && rate != null) {
        return String.format(locale, "%.6f", original.multiply(rate.cnyPerUsd)) to "CNY"
    }
    return String.format(locale, "%.6f", original) to amount.currency
}

@Composable
private fun priceLine(label: String, usdPerMillion: Double?, currency: String,
                      rate: PolishExchangeRate?) {
    if (usdPerMillion == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp)
        Text(stringResource(R.string.polish_price_per_million,
            formatPrice(usdPerMillion, currency, rate)), fontSize = 12.sp)
    }
}

@Composable
private fun priceSummary(entry: PolishCatalogModel, currency: String,
                         rate: PolishExchangeRate?): String {
    val input = entry.inputUsdPerMillion?.let { formatPrice(it, currency, rate) } ?: "?"
    val output = entry.outputUsdPerMillion?.let { formatPrice(it, currency, rate) } ?: "?"
    return "${stringResource(R.string.polish_price_input)} $input · " +
        "${stringResource(R.string.polish_price_output)} $output"
}

private fun formatPrice(usdPerMillion: Double, currency: String, rate: PolishExchangeRate?): String {
    val usd = java.math.BigDecimal.valueOf(usdPerMillion)
    val value = if (currency == "CNY") rate?.cnyPerUsd?.multiply(usd) else usd
    if (value == null) return "—"
    return (if (currency == "CNY") "¥" else "$") +
        value.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
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
private fun StatsRow(entry: PolishStatsEntry, locale: Locale,
                        displayCurrency: String = "USD", rate: PolishExchangeRate? = null,
                        expanded: Boolean = false, onToggle: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .testTag("polish-usage-row-${entry.callId}").padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            val amount = PolishUsageAmount(entry.costCurrency, entry.costSource.name, entry.costMicros)
            val (text, currency) = convertedCost(amount, displayCurrency, rate, locale)
            stringResource(
                if (entry.costSource == PolishCostSource.Reported) R.string.polish_stats_cost_reported
                else R.string.polish_stats_cost_estimated, text, currency,
            )
        } else stringResource(R.string.polish_stats_cost_unknown)
        Text(cost, fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (expanded) {
            Text(stringResource(R.string.polish_usage_request_model, entry.model), fontSize = 10.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            entry.responseModel?.let {
                Text(stringResource(R.string.polish_usage_response_model, it), fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            if (entry.cacheReadTokens != null || entry.cacheWriteTokens != null) {
                Text(stringResource(R.string.polish_usage_cache,
                    entry.cacheReadTokens?.toString() ?: "?", entry.cacheWriteTokens?.toString() ?: "?"),
                    fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            entry.reasoningTokens?.let {
                Text(stringResource(R.string.polish_usage_reasoning, it.toString()), fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            entry.priceRule?.let {
                Text(stringResource(R.string.polish_usage_price_source, it), fontSize = 10.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
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
