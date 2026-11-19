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
import seeyuer.sayin.polish.userMessage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
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
    if (selected == PolishMode.Translate || selected == PolishMode.List ||
        selected == PolishMode.Formal || selected == PolishMode.Concise) {
        selected = PolishMode.Text
    }
    OverlayBottomSheet(show = show, onDismissRequest = onDismiss) {
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
                    session.startPolish(selected)
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
    val text = when {
        state.running -> stringResource(R.string.polish_running)
        state.confirmationPending -> stringResource(R.string.polish_confirm_send)
        state.outcome is PolishOutcome.Failure ->
            stringResource(R.string.polish_failed, stringResource((state.outcome as PolishOutcome.Failure).kind.labelRes()))
        state.outcome is PolishOutcome.Cancelled -> stringResource(R.string.polish_cancelled_short)
        else -> null
    } ?: return
    Text(text, Modifier.fillMaxWidth().testTag("polish-status"), fontSize = 12.sp,
        color = if (state.outcome is PolishOutcome.Failure) MiuixTheme.colorScheme.error
        else MiuixTheme.colorScheme.onSurfaceSecondary)
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
            Text(stringResource(if (mode == PolishMode.Voice) R.string.polish_mode_voice else R.string.polish_mode_text),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (mode == PolishMode.Voice) R.string.polish_mode_voice_summary else R.string.polish_mode_text_summary),
                fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (selected) Icon(VoiceLinkIcon.Check.vector, null, Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.primary)
    }
}
