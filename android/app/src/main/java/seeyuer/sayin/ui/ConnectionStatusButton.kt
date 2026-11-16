package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class StatusTone { Success, Warning, Error, Primary, Neutral }

private data class StatusVisual(
    val icon: VoiceLinkIcon,
    val label: Int,
    val tone: StatusTone,
    val busy: Boolean = false,
)

/**
 * Single connection entry on the home top bar, matching the HTML design:
 * status icon + short label + downward chevron, colored by the real connection state.
 * The other tabs intentionally have no duplicate entry.
 */
@Composable
internal fun ConnectionStatusButton(state: ClientState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val visual = connectionStatusVisual(state)
    val tone = statusToneColor(visual.tone)
    val label = stringResource(visual.label)
    val description = stringResource(R.string.connection_status_description_action, label)
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("speak-connect-action")
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(36.dp).widthIn(min = 96.dp, max = 148.dp).clip(RoundedCornerShape(18.dp))
                .background(tone.copy(alpha = .12f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visual.busy) {
                CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                        foregroundColor = tone, backgroundColor = Color.Transparent))
            } else {
                Icon(visual.icon.vector, null, Modifier.size(18.dp), tint = tone)
            }
            Text(label, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(VoiceLinkIcon.ChevronDown.vector, null, Modifier.size(14.dp), tint = tone.copy(alpha = .75f))
        }
    }
}

private fun connectionStatusVisual(state: ClientState): StatusVisual = when {
    state.connectionPresentation == ConnectionPresentation.Storage ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopExclamation, R.string.connection_status_storage, StatusTone.Error)
    state.connectionPresentation == ConnectionPresentation.Permission ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopX, R.string.connection_status_permission, StatusTone.Warning)
    state.connectionPresentation == ConnectionPresentation.Loading ->
        StatusVisual(VoiceLinkIcon.Loader2, R.string.connection_status_loading, StatusTone.Neutral, busy = true)
    state.connectionPresentation == ConnectionPresentation.First ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopPlus, R.string.connection_status_first, StatusTone.Neutral)
    state.discovering ->
        StatusVisual(VoiceLinkIcon.Radar, R.string.connection_status_discovering, StatusTone.Primary)
    state.connectionPresentation == ConnectionPresentation.Pairing ->
        StatusVisual(VoiceLinkIcon.Loader2, R.string.connection_status_pairing, StatusTone.Primary, busy = true)
    state.connectionPresentation == ConnectionPresentation.Approval ->
        StatusVisual(VoiceLinkIcon.Hourglass, R.string.connection_status_approval, StatusTone.Primary)
    state.connectionPresentation == ConnectionPresentation.Connecting ->
        StatusVisual(VoiceLinkIcon.Loader2, R.string.connection_status_connecting, StatusTone.Primary, busy = true)
    state.connectionPresentation == ConnectionPresentation.Recovering ->
        StatusVisual(VoiceLinkIcon.ArrowsLeftRight, R.string.connection_status_syncing, StatusTone.Primary)
    state.connectionPresentation == ConnectionPresentation.Paused ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopPause, R.string.connection_status_paused, StatusTone.Warning)
    state.connectionPresentation == ConnectionPresentation.Review ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopExclamation, R.string.connection_status_review, StatusTone.Warning)
    state.connectionPresentation == ConnectionPresentation.Inactive ->
        StatusVisual(VoiceLinkIcon.Dots, R.string.connection_status_inactive, StatusTone.Warning)
    state.connectionPresentation == ConnectionPresentation.Unavailable ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopMinus, R.string.connection_status_unavailable, StatusTone.Warning)
    state.connectionPresentation == ConnectionPresentation.Offline ->
        StatusVisual(VoiceLinkIcon.Retry, R.string.connection_status_offline, StatusTone.Neutral)
    state.connection == Connection.ONLINE ->
        StatusVisual(VoiceLinkIcon.DeviceDesktopCheck, R.string.connection_status_connected, StatusTone.Success)
    else -> StatusVisual(VoiceLinkIcon.DeviceDesktopMinus, R.string.connection_status_offline, StatusTone.Neutral)
}

@Composable
private fun statusToneColor(tone: StatusTone): Color {
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    return when (tone) {
        StatusTone.Success -> if (dark) YuePalette.SuccessDark else YuePalette.SuccessLight
        StatusTone.Warning -> if (dark) YuePalette.WarningDark else YuePalette.WarningLight
        StatusTone.Error -> if (dark) YuePalette.ErrorDark else YuePalette.ErrorLight
        StatusTone.Primary -> MiuixTheme.colorScheme.primary
        StatusTone.Neutral -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
}
