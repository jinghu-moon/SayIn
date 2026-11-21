package seeyuer.sayin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import seeyuer.sayin.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Send-mode shortcut for the draft card. It edits the global default that the send-policy page
 * also owns; a per-app rule can still override it, so the sheet says so instead of pretending the
 * chip on the card will always follow this value.
 */
@Composable
internal fun SendModeSheet(session: SpeakSession, show: Boolean, onDismiss: () -> Unit) {
    val canChange = !session.busy && !session.requestLocked && !session.translation.running &&
        !session.translationStopping && !session.translationPreparing
    val automatic = session.sendMode == SpeakSendMode.Automatic
    val overridden = session.effectiveSendMode() != session.sendMode
    var delayDraft by remember(show) { mutableIntStateOf(session.autoSendSeconds) }
    OverlayBottomSheet(show = show, onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.draft_tool_send_mode), style = MiuixTheme.textStyles.title2)
            Text(
                stringResource(R.string.settings_auto_send_hint),
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.settings_policy_manual),
                    stringResource(R.string.settings_policy_auto),
                ),
                selectedTabIndex = if (automatic) 1 else 0,
                onTabSelected = { index ->
                    if (canChange) {
                        session.changeSendMode(
                            if (index == 1) SpeakSendMode.Automatic else SpeakSendMode.Manual,
                        )
                    }
                },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                    selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                    selectedContentColor = MiuixTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().alpha(if (canChange) 1f else .5f).testTag("send-mode-tabs"),
            )
            if (automatic) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_send_policy_wait), Modifier.weight(1f), fontSize = 14.sp)
                    Text(
                        stringResource(R.string.settings_send_policy_seconds, delayDraft),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
                Slider(
                    value = delayDraft.toFloat(),
                    onValueChange = { delayDraft = it.roundToInt() },
                    onValueChangeFinished = { if (canChange) session.changeAutoSendSeconds(delayDraft) },
                    valueRange = SpeakInteraction.MIN_DELAY_SECONDS.toFloat()..SpeakInteraction.MAX_DELAY_SECONDS.toFloat(),
                    steps = SpeakInteraction.MAX_DELAY_SECONDS - SpeakInteraction.MIN_DELAY_SECONDS - 1,
                    enabled = canChange,
                    modifier = Modifier.fillMaxWidth().testTag("send-mode-wait"),
                )
            }
            Text(
                stringResource(R.string.draft_tool_send_mode_note),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            if (overridden) Text(
                stringResource(R.string.draft_tool_send_mode_override),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** Recognition-engine shortcut; selecting an engine also switches the active input source, as in settings. */
@Composable
internal fun RecognitionEngineSheet(
    session: SpeakSession,
    cloudConfigured: Boolean,
    show: Boolean,
    onDismiss: () -> Unit,
    onConfigureCloud: () -> Unit,
) {
    val canChange = !session.busy && !session.requestLocked && !session.translation.running &&
        !session.translationStopping && !session.translationPreparing
    val cloud = session.lastVoiceSource == SpeakInputSource.Cloud
    val note = when {
        cloud && !cloudConfigured -> R.string.settings_cloud_need_config
        cloud -> R.string.settings_cloud_upload_note
        else -> R.string.settings_local_private_note
    }
    OverlayBottomSheet(show = show, onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.draft_tool_engine), style = MiuixTheme.textStyles.title2)
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.settings_engine_local),
                    stringResource(R.string.settings_engine_cloud),
                ),
                selectedTabIndex = if (cloud) 1 else 0,
                onTabSelected = { index ->
                    if (canChange) {
                        session.selectSource(if (index == 1) SpeakInputSource.Cloud else SpeakInputSource.Local)
                    }
                },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                    selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                    selectedContentColor = MiuixTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().alpha(if (canChange) 1f else .5f).testTag("engine-tabs"),
            )
            Text(stringResource(note), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            if (cloud && !cloudConfigured) Button(
                { onDismiss(); onConfigureCloud() },
                Modifier.fillMaxWidth().testTag("engine-configure"),
            ) {
                Text(stringResource(R.string.draft_tool_engine_configure))
            }
            Text(
                stringResource(R.string.draft_tool_engine_note),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
    }
}
