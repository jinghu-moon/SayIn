package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConnectionChip(state: ClientState, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val description = stringResource(connectionChipText(state.connectionPresentation))
    Button(onClick, modifier.heightIn(min = 48.dp).testTag("speak-connect-action").semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface),
        cornerRadius = 24.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val working = state.busy || state.recovering || state.discovering
            val dotColor = if (state.ready) {
                if (MiuixTheme.colorScheme.background.luminance() < 0.5f) YuePalette.SuccessDark else YuePalette.SuccessLight
            } else if (working) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
            if (compact) Box(Modifier.size(24.dp)) {
                Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(22.dp).align(Alignment.Center))
                if (working) CircularProgressIndicator(Modifier.align(Alignment.TopEnd), size = 9.dp, strokeWidth = 1.5.dp)
                else Box(Modifier.size(8.dp).align(Alignment.TopEnd).background(dotColor, CircleShape))
            } else if (working) CircularProgressIndicator(size = 12.dp, strokeWidth = 2.dp)
            else Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            if (!compact || !state.ready && !state.busy && !state.recovering && !state.discovering) Text(stringResource(if (state.discovering) R.string.discovering_computer else connectionChipText(state.connectionPresentation)), Modifier.weight(1f, fill = false),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (!compact) Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(16.dp))
        }
    }
}

/** Non-interactive summary on the connection page; the speaking chip remains an entry point. */
@Composable
internal fun ConnectionBadge(state: ClientState) {
    val text = when (state.connectionPresentation) {
        ConnectionPresentation.First, ConnectionPresentation.Offline, ConnectionPresentation.Permission -> R.string.connect_not_connected
        ConnectionPresentation.Ready -> R.string.connected
        else -> connectionChipText(state.connectionPresentation)
    }
    val color = connectionTone(state)
    Row(Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(20.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag("connection-badge")
        .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(stringResource(text), color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun connectionTone(state: ClientState): androidx.compose.ui.graphics.Color {
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    return when (state.connectionPresentation) {
        ConnectionPresentation.Storage -> if (dark) YuePalette.ErrorDark else YuePalette.ErrorLight
        ConnectionPresentation.Ready -> if (dark) YuePalette.SuccessDark else YuePalette.SuccessLight
        ConnectionPresentation.Approval, ConnectionPresentation.Recovering, ConnectionPresentation.Review,
        ConnectionPresentation.Inactive, ConnectionPresentation.Unavailable, ConnectionPresentation.Paused ->
            if (dark) YuePalette.WarningDark else YuePalette.WarningLight
        ConnectionPresentation.Pairing, ConnectionPresentation.Connecting -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
}

@Composable
internal fun ConnectionDetails(state: ClientState, onReconnect: () -> Unit, actions: @Composable () -> Unit) {
    val presentation = state.connectionPresentation
    Column(Modifier.fillMaxWidth().testTag("connection-details"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            presentation == ConnectionPresentation.First -> {
                listOf(R.string.connect_step_open, R.string.connect_step_scan, R.string.connect_step_approve)
                    .forEachIndexed { index, label ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp).background(MiuixTheme.colorScheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text((index + 1).toString(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                            Text(stringResource(label), Modifier.weight(1f), fontSize = 14.sp)
                        }
                    }
                Caption(stringResource(R.string.connect_approval_notice))
            }
            state.paired -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(46.dp).background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(25.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.connection_computer),
                            Modifier.testTag("connection-computer-name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(connectionStatusText(state)), Modifier.testTag("connection-receive-status")
                            .semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, color = connectionTone(state))
                    }
                }
                if (state.discovering || state.busy || state.recovering) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        Caption(stringResource(if (state.discovering) R.string.discovering_computer else connectionHelpText(presentation)))
                    }
                } else if (presentation != ConnectionPresentation.Ready && presentation != ConnectionPresentation.Permission) {
                    Caption(stringResource(connectionHelpText(presentation)))
                }
                if (state.showConnectionRetry) TextButton(stringResource(R.string.reconnect), onReconnect,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-retry"), minHeight = 48.dp,
                    colors = ButtonDefaults.textButtonColors(color = androidx.compose.ui.graphics.Color.Transparent,
                        textColor = MiuixTheme.colorScheme.primary))
            }
            presentation == ConnectionPresentation.Pairing || presentation == ConnectionPresentation.Approval -> {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(size = 30.dp, strokeWidth = 3.dp)
                    Text(stringResource(connectionChipText(presentation)), fontWeight = FontWeight.SemiBold)
                    Caption(stringResource(connectionHelpText(presentation)))
                }
            }
            presentation != ConnectionPresentation.Permission -> Caption(stringResource(connectionHelpText(presentation)))
        }
        actions()
    }
}

internal fun connectionStatusText(state: ClientState): Int = when {
    !state.storageHealthy || state.connectionPresentation == ConnectionPresentation.Permission -> connectionChipText(state.connectionPresentation)
    state.discovering -> R.string.discovering_computer
    state.ready -> R.string.connect_ready
    state.connectionPresentation == ConnectionPresentation.Offline -> R.string.connect_retry_wait
    else -> connectionChipText(state.connectionPresentation)
}

internal fun connectionChipText(state: ConnectionPresentation): Int = when (state) {
    ConnectionPresentation.Loading -> R.string.loading_storage
    ConnectionPresentation.Storage -> R.string.connection_storage_label
    ConnectionPresentation.Permission -> R.string.connect_lan_title
    ConnectionPresentation.First -> R.string.mobile_connect_action
    ConnectionPresentation.Pairing -> R.string.pairing
    ConnectionPresentation.Approval -> R.string.waiting_approval
    ConnectionPresentation.Connecting -> R.string.connecting
    ConnectionPresentation.Offline -> R.string.mobile_reconnect_title
    ConnectionPresentation.Recovering -> R.string.mobile_recovering
    ConnectionPresentation.Review -> R.string.connection_review_label
    ConnectionPresentation.Inactive -> R.string.not_active_device
    ConnectionPresentation.Unavailable -> R.string.connection_unavailable_label
    ConnectionPresentation.Paused -> R.string.receiver_paused
    ConnectionPresentation.Ready -> R.string.connection_computer
}

internal fun connectionHelpText(state: ConnectionPresentation): Int = when (state) {
    ConnectionPresentation.Loading -> R.string.loading_storage
    ConnectionPresentation.Storage -> R.string.error_storage
    ConnectionPresentation.Permission -> R.string.network_permission_denied
    ConnectionPresentation.First -> R.string.connection_first_help
    ConnectionPresentation.Pairing -> R.string.connection_pairing_help
    ConnectionPresentation.Approval -> R.string.approve_on_computer
    ConnectionPresentation.Connecting -> R.string.connection_reconnecting_help
    ConnectionPresentation.Offline -> R.string.connection_offline_help
    ConnectionPresentation.Recovering -> R.string.connection_recovering_help
    ConnectionPresentation.Review -> R.string.connection_review_help
    ConnectionPresentation.Inactive -> R.string.connection_inactive_help
    ConnectionPresentation.Unavailable -> R.string.connection_unavailable_help
    ConnectionPresentation.Paused -> R.string.connection_paused_help
    ConnectionPresentation.Ready -> R.string.connection_ready_help
}
