package seeyuer.sayin.ui.settings

import androidx.compose.foundation.clickable
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

internal class PolishEditorState(config: PolishConfig?) {
    private val original = config
    private val id = config?.id ?: java.util.UUID.randomUUID().toString()
    private val revision = config?.revision ?: 1L
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
internal fun PolishSettingsPage(session: SpeakSession, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val configState = session.polish.state
    val config = (configState as? PolishConfigState.Ready)?.config
    var creating by remember { mutableStateOf(false) }
    var newSeed by remember { mutableStateOf(PolishConfig.preset(PolishProvider.DeepSeek)) }
    val editorSource = if (creating) newSeed else config
    val editor = remember(creating, editorSource?.fingerprint) { PolishEditorState(editorSource) }
    val test = session.polish.testState
    val saving = session.polish.saving
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { configuration.locales[0] }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var usageDays by remember { mutableStateOf(7) }
    val catalog = session.polish.catalog
    val candidates = (catalog?.modelsFor(editor.provider).orEmpty() +
        if (editor.provider == PolishProvider.Custom) session.polish.customModels.map {
            PolishCatalogModel(it, it, "custom", false, null, null, null)
        } else emptyList()).distinctBy { it.id }
    val providers = listOf(PolishProvider.DeepSeek, PolishProvider.Qwen, PolishProvider.OpenAi,
        PolishProvider.Anthropic, PolishProvider.Custom)
    Column(modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.polish_upload_note), fontSize = 12.sp, lineHeight = 18.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        when (configState) {
            PolishConfigState.Missing -> Notice(stringResource(R.string.polish_config_missing))
            PolishConfigState.Corrupt, PolishConfigState.Unauthorized ->
                Notice(stringResource(R.string.polish_config_corrupt), error = true)
            is PolishConfigState.Ready -> Notice(stringResource(R.string.polish_saved))
        }
        if (session.polish.configurations.isNotEmpty()) {
            Card {
                val savedItems = session.polish.configurations.map { saved ->
                    DropdownItem(
                        text = saved.name.ifBlank { stringResource(providerLabel(saved.provider)) },
                        summary = saved.model,
                        selected = !creating && saved.id == config?.id,
                        onClick = {
                            creating = false
                            session.polish.selectConfig(saved.id)
                        },
                        icon = { iconModifier -> ProviderLogo(saved.provider, iconModifier.size(24.dp)) },
                    )
                }
                WindowDropdownPreference(
                    entry = DropdownEntry(items = savedItems),
                    title = stringResource(R.string.polish_saved_configs),
                    summary = if (creating) stringResource(R.string.polish_new_config) else null,
                    startAction = { ProviderLogo(if (creating) editor.provider else config?.provider ?: editor.provider, Modifier.size(26.dp)) },
                    showValue = false,
                )
            }
        }
        TextButton(
            stringResource(R.string.polish_add_config),
            onClick = {
                creating = true
                newSeed = PolishConfig.preset(PolishProvider.DeepSeek)
            },
            minHeight = 44.dp,
            colors = plainTextButtonColors(),
        )
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
                TextField(editor.model, { editor.model = it.take(120); editor.localError = null },
                    Modifier.fillMaxWidth().testTag("polish-model"), label = stringResource(R.string.polish_model),
                    singleLine = true, enabled = !saving)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(stringResource(R.string.polish_choose_model), {
                        modelQuery = ""
                        showModels = true
                    }, minHeight = 40.dp, colors = plainTextButtonColors())
                    TextButton(
                        stringResource(if (session.polish.catalogChecking) R.string.polish_catalog_refreshing else R.string.polish_catalog_refresh),
                        { session.polish.refreshCatalog() },
                        minHeight = 40.dp,
                        enabled = !session.polish.catalogChecking,
                        colors = plainTextButtonColors(),
                    )
                }
                Text(
                    if (session.polish.catalogLastCheckedAt == 0L) stringResource(R.string.polish_catalog_never)
                    else stringResource(R.string.polish_catalog_checked,
                        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(session.polish.catalogLastCheckedAt))),
                    fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                session.polish.catalogError?.let {
                    Text(stringResource(R.string.polish_catalog_error, it), fontSize = 10.5.sp,
                        color = MiuixTheme.colorScheme.error)
                }
                if (editor.provider == PolishProvider.Custom) {
                    TextButton(
                        stringResource(if (session.polish.customModelsLoading) R.string.polish_fetching_models else R.string.polish_fetch_models),
                        {
                            val error = editor.validate()
                            if (error != null) { editor.localError = error; return@TextButton }
                            session.polish.refreshCustomModels(editor.build())
                        },
                        minHeight = 40.dp,
                        enabled = !session.polish.customModelsLoading,
                        colors = plainTextButtonColors(),
                    )
                    session.polish.customModelsError?.let {
                        Text(stringResource(R.string.polish_gateway_models_error, it), fontSize = 10.5.sp,
                            color = MiuixTheme.colorScheme.error)
                    }
                }
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
                session.polish.save(editor.build()) { error -> if (error == null) creating = false }
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
        Card {
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.polish_auto),
                    summary = stringResource(if (config == null) R.string.polish_auto_unbound
                    else if (session.polish.autoPolishBound) R.string.polish_auto_bound else R.string.polish_auto_summary),
                    checked = session.polish.autoPolishBound,
                    enabled = config != null,
                    modifier = Modifier.testTag("polish-auto"),
                    onCheckedChange = { session.polish.updateAutoPolish(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.polish_app_rules),
                    summary = stringResource(R.string.polish_app_rules_summary),
                    checked = session.polish.appRulesEnabled,
                    modifier = Modifier.testTag("polish-app-rules"),
                    onCheckedChange = { session.polish.updateAppRules(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.polish_streaming),
                    summary = stringResource(R.string.polish_streaming_summary),
                    checked = session.polish.streamingPreview,
                    modifier = Modifier.testTag("polish-streaming"),
                    onCheckedChange = { session.polish.updateStreamingPreview(it) },
                )
            }
        }
        var targetDraft by remember { mutableStateOf(session.polish.targetLanguage) }
        TextField(targetDraft, {
            targetDraft = it.take(35)
            if (PolishPrompts.validateTargetLanguage(targetDraft) == null) session.polish.updateTargetLanguage(targetDraft)
        }, Modifier.fillMaxWidth().testTag("polish-default-language"),
            label = stringResource(R.string.polish_target_language), singleLine = true)
        TextField(session.polish.customRewrite, { session.polish.updateCustomRewrite(it) },
            Modifier.fillMaxWidth().testTag("polish-custom-rewrite").heightIn(min = 88.dp),
            label = stringResource(R.string.polish_custom_rewrite_label), singleLine = false)
        Text(stringResource(R.string.polish_custom_rewrite_summary), fontSize = 11.5.sp, lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        TextField(session.polish.hotwords, { session.polish.updateHotwords(it) },
            Modifier.fillMaxWidth().testTag("polish-hotwords").heightIn(min = 72.dp),
            label = stringResource(R.string.polish_hotwords), singleLine = false)
        Text(stringResource(R.string.polish_hotwords_summary), fontSize = 11.5.sp, lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (!creating && config != null) {
            TextButton(stringResource(R.string.polish_delete_config), { confirmDelete = true }, minHeight = 48.dp,
                colors = plainTextButtonColors())
        }
        if (config != null) {
            TextButton(stringResource(R.string.polish_clear), { confirmClear = true }, minHeight = 48.dp,
                colors = plainTextButtonColors())
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.polish_stats), Modifier.weight(1f), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            if (session.polish.stats.isNotEmpty()) TextButton(stringResource(R.string.polish_stats_clear), {
                session.polish.clearStats()
            }, minHeight = 36.dp, colors = plainTextButtonColors())
        }
        if (session.polish.stats.isEmpty()) {
            Text(stringResource(R.string.polish_stats_empty), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to R.string.polish_usage_today, 7 to R.string.polish_usage_7d,
                    30 to R.string.polish_usage_30d).forEach { (days, label) ->
                    TextButton(stringResource(label), { usageDays = days }, minHeight = 36.dp,
                        colors = plainTextButtonColors())
                }
            }
            val now = System.currentTimeMillis()
            val cutoff = if (usageDays == 0) {
                java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else now - usageDays * 24L * 60L * 60L * 1000L
            val filtered = session.polish.stats.filter { it.timestamp >= cutoff }
            if (filtered.isEmpty()) {
                Text(stringResource(R.string.polish_stats_empty), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            } else {
                val knownTokens = filtered.sumOf { (it.inputTokens ?: 0L) + (it.outputTokens ?: 0L) }
                val durations = filtered.map { it.durationMillis }.sorted()
                val median = durations.getOrElse(durations.size / 2) { 0L }
                val unknownCost = filtered.count { it.costMicros == null }
                Text(stringResource(R.string.polish_usage_summary, filtered.size, knownTokens.toString(), median),
                    fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                if (unknownCost > 0) Text(stringResource(R.string.polish_usage_unknown_cost, unknownCost),
                    fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                filtered.take(20).forEach { entry -> StatsRow(entry, locale) }
            }
        }
    }
    if (showModels) {
        val filtered = if (modelQuery.isBlank()) candidates
        else candidates.filter {
            it.id.contains(modelQuery, ignoreCase = true) || it.name.contains(modelQuery, ignoreCase = true)
        }
        OverlayBottomSheet(show = true, onDismissRequest = { showModels = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.polish_catalog_title), style = MiuixTheme.textStyles.title2)
                TextField(modelQuery, { modelQuery = it.take(80) }, Modifier.fillMaxWidth(),
                    label = stringResource(R.string.polish_search_model), singleLine = true)
                if (filtered.isEmpty()) {
                    Text(stringResource(R.string.polish_catalog_empty), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                } else {
                    filtered.take(200).forEach { model ->
                        Row(Modifier.fillMaxWidth().clickable {
                            editor.model = model.id
                            editor.localError = null
                            showModels = false
                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.name, fontSize = 13.sp)
                                Text(model.id, fontSize = 10.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                            if (editor.model == model.id) Icon(VoiceLinkIcon.Check.vector, null,
                                Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
                Text(stringResource(R.string.polish_manual_model_note), fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                TextButton(stringResource(R.string.polish_cancel_action), { showModels = false },
                    Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
            }
        }
    }
    WindowDialog(
        title = stringResource(R.string.polish_clear),
        summary = stringResource(R.string.polish_confirm_clear),
        show = confirmClear,
        onDismissRequest = { confirmClear = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.polish_cancel_action), { confirmClear = false },
                modifier = Modifier.weight(1f), minHeight = 48.dp)
            TextButton(stringResource(R.string.polish_remove), {
                confirmClear = false
                session.polish.clear()
            }, modifier = Modifier.weight(1f).testTag("polish-clear-confirm"), minHeight = 48.dp,
                colors = plainTextButtonColors())
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
                config?.let { session.polish.deleteConfig(it.id) }
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
            Text(stringResource(R.string.polish_stats_entry_title, entry.provider, entry.model) + " · " +
                stringResource(usagePurposeLabel(entry.purpose)), Modifier.weight(1f), fontSize = 12.sp)
            Text(SimpleDateFormat("MM-dd HH:mm", locale).format(Date(entry.timestamp)),
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Text(stringResource(R.string.polish_stats_tokens, entry.inputTokens?.toString() ?: "?", entry.outputTokens?.toString() ?: "?"),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Text(stringResource(R.string.polish_stats_duration, entry.durationMillis.toInt()) + " · " +
            stringResource(R.string.polish_stats_outcome,
                stringResource(if (entry.outcome == "success" || entry.outcome == "test") R.string.polish_stats_success else R.string.polish_stats_failure)),
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
