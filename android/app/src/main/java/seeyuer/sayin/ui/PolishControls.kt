package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import seeyuer.sayin.polish.PolishFailure
import seeyuer.sayin.polish.PolishMode
import seeyuer.sayin.polish.PolishPrompts
import seeyuer.sayin.polish.PolishTranslateStyle
import seeyuer.sayin.polish.userMessage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PolishSheet(
    session: SpeakSession,
    show: Boolean,
    onDismiss: () -> Unit,
    onConfigure: () -> Unit,
) {
    val state = session.polishState
    var selected by remember(show) { mutableStateOf(session.suggestedPolishMode()) }
    var targetLanguage by remember(show) { mutableStateOf(session.polish.targetLanguage) }
    var targetError by remember(show) { mutableStateOf(false) }
    var translateStyle by remember(show) { mutableStateOf<PolishTranslateStyle?>(null) }
    OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.polish_title), style = MiuixTheme.textStyles.title2)
            Text(stringResource(R.string.polish_sheet_description), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            if (!session.polish.ready) {
                Text(stringResource(R.string.polish_not_configured), fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error)
                Button({ onDismiss(); onConfigure() }, Modifier.fillMaxWidth().testTag("polish-open-settings")) {
                    Text(stringResource(R.string.polish_open_settings))
                }
                return@Column
            }
            val selection = session.draft.selection
            val selectedChars = if (selection.min != selection.max) selection.max - selection.min else session.draft.text.length
            Text(stringResource(R.string.polish_scope, selectedChars), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
            PolishModeRow(PolishMode.Voice, selected, enabled = !state.running) { selected = it }
            PolishModeRow(PolishMode.Text, selected, enabled = !state.running) { selected = it }
            PolishModeRow(PolishMode.List, selected, enabled = !state.running) { selected = it }
            PolishModeRow(PolishMode.Formal, selected, enabled = !state.running) { selected = it }
            PolishModeRow(PolishMode.Concise, selected, enabled = !state.running) { selected = it }
            if (session.polish.customRewrite.isNotBlank()) {
                PolishModeRow(PolishMode.Custom, selected, enabled = !state.running) { selected = it }
            }
            PolishModeRow(PolishMode.Translate, selected, enabled = !state.running) { selected = it }
            if (selected == PolishMode.Translate) {
                Text(stringResource(R.string.polish_target_language), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                TextField(targetLanguage, { targetLanguage = it.take(35); targetError = false },
                    Modifier.fillMaxWidth().testTag("polish-target-language"),
                    label = stringResource(R.string.polish_target_language), singleLine = true, enabled = !state.running)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "English", "ja" to "日本語", "fr" to "Français", "ar" to "العربية").forEach { (code, name) ->
                        TextButton(name, { targetLanguage = code; targetError = false }, minHeight = 36.dp,
                            colors = plainTextButtonColors())
                    }
                }
                if (targetError) Text(stringResource(R.string.polish_target_invalid), fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.error)
                val styles = listOf(null, PolishTranslateStyle.Chat, PolishTranslateStyle.Formal,
                    PolishTranslateStyle.Tech)
                WindowDropdownPreference(
                    title = stringResource(R.string.polish_translate_style),
                    items = listOf(stringResource(R.string.polish_style_auto),
                        stringResource(R.string.polish_style_chat),
                        stringResource(R.string.polish_style_formal),
                        stringResource(R.string.polish_style_tech)),
                    selectedIndex = styles.indexOf(translateStyle),
                    onSelectedIndexChange = { translateStyle = styles[it] },
                )
            }
            when (val outcome = state.outcome) {
                is PolishOutcome.Failure -> Text(stringResource(outcome.kind.labelRes()), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error)
                is PolishOutcome.Success -> Text(stringResource(R.string.polish_done), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary)
                PolishOutcome.Cancelled -> Text(stringResource(R.string.polish_cancelled), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                null -> Unit
            }
            if (state.running) {
                Button({ session.cancelPolish() }, Modifier.fillMaxWidth().testTag("polish-cancel")) {
                    Text(stringResource(R.string.polish_cancel))
                }
            } else {
                Button({
                    if (selected == PolishMode.Translate && PolishPrompts.validateTargetLanguage(targetLanguage) != null) {
                        targetError = true
                        return@Button
                    }
                    if (selected == PolishMode.Custom && session.polish.customRewrite.isBlank()) return@Button
                    session.startPolish(selected, targetLanguage.takeIf { selected == PolishMode.Translate },
                        translateStyle.takeIf { selected == PolishMode.Translate })
                    onDismiss()
                }, Modifier.fillMaxWidth().testTag("polish-run")) {
                    Text(stringResource(if (state.confirmationPending) R.string.polish_run_again else R.string.polish_run))
                }
            }
            Text(stringResource(R.string.polish_send_note), fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}

@Composable
internal fun PolishStatus(session: SpeakSession) {
    val state = session.polishState
    var compare by remember { mutableStateOf(false) }
    val failure = state.outcome as? PolishOutcome.Failure
    val text = when {
        state.running -> stringResource(R.string.polish_running)
        state.confirmationPending -> stringResource(R.string.polish_confirm_send)
        failure != null -> stringResource(R.string.polish_failed, stringResource(failure.kind.labelRes()))
        state.outcome is PolishOutcome.Cancelled -> stringResource(R.string.polish_cancelled_short)
        else -> null
    }
    if (text == null && state.originalText == null) return
    Column(Modifier.fillMaxWidth()) {
        text?.let {
            Text(it, Modifier.fillMaxWidth().testTag("polish-status"), fontSize = 12.sp,
                color = if (failure != null) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        state.preview?.takeIf { state.running && it.isNotBlank() }?.let {
            Text(stringResource(R.string.polish_preview) + ": " + it, Modifier.fillMaxWidth().testTag("polish-preview"),
                fontSize = 12.sp, maxLines = 3, color = MiuixTheme.colorScheme.primary)
        }
        val original = state.originalText
        val result = state.resultText
        if (original != null && result != null && original != result && !state.running) {
            TextButton(stringResource(R.string.polish_compare), { compare = true }, minHeight = 36.dp,
                colors = plainTextButtonColors())
        }
    }
    val original = state.originalText
    val result = state.resultText
    if (compare && original != null && result != null) {
        OverlayBottomSheet(insideMargin = AppSheetInsideMargin, show = true, onDismissRequest = { compare = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.polish_compare_title), style = MiuixTheme.textStyles.title2)
                Text(stringResource(R.string.polish_original), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(original, fontSize = 14.sp)
                Text(stringResource(R.string.polish_result), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(result, fontSize = 14.sp)
                TextButton(stringResource(R.string.polish_cancel_action), { compare = false },
                    Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
            }
        }
    }
}

internal fun PolishFailure.labelRes(): Int = when (this) {
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

@Composable
private fun PolishModeRow(mode: PolishMode, selectedMode: PolishMode, enabled: Boolean, onSelect: (PolishMode) -> Unit) {
    val selected = mode == selectedMode
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .10f) else MiuixTheme.colorScheme.surfaceContainerHigh)
        .clickable(enabled = enabled) { onSelect(mode) }
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("polish-mode-${mode.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(modeLabel(mode)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(modeSummary(mode)), fontSize = 11.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (selected) Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.primary)
    }
}

private fun modeLabel(mode: PolishMode): Int = when (mode) {
    PolishMode.Voice -> R.string.polish_mode_voice
    PolishMode.Text -> R.string.polish_mode_text
    PolishMode.Translate -> R.string.polish_mode_translate
    PolishMode.List -> R.string.polish_mode_list
    PolishMode.Formal -> R.string.polish_mode_formal
    PolishMode.Concise -> R.string.polish_mode_concise
    PolishMode.Custom -> R.string.polish_mode_custom
}

private fun modeSummary(mode: PolishMode): Int = when (mode) {
    PolishMode.Voice -> R.string.polish_mode_voice_summary
    PolishMode.Text -> R.string.polish_mode_text_summary
    PolishMode.Translate -> R.string.polish_mode_translate_summary
    PolishMode.List -> R.string.polish_mode_list_summary
    PolishMode.Formal -> R.string.polish_mode_formal_summary
    PolishMode.Concise -> R.string.polish_mode_concise_summary
    PolishMode.Custom -> R.string.polish_mode_custom_summary
}
