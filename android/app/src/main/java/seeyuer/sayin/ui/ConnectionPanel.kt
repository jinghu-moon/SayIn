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
                        Text(state.pairedTargets.firstOrNull { it.hostId == state.selectedHostId }?.displayName
                            ?: stringResource(R.string.connection_computer),
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
                } else if (!state.replacingPair && presentation != ConnectionPresentation.Ready && presentation != ConnectionPresentation.Permission) {
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
        if (state.pairingRecoveryPending && !state.busy && state.storageHealthy &&
            presentation != ConnectionPresentation.Permission) {
            TextButton(stringResource(R.string.connection_retry_pairing), onReconnect,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairing-recovery"),
                minHeight = 48.dp,
                colors = ButtonDefaults.textButtonColors(color = androidx.compose.ui.graphics.Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary))
        }
        actions()
    }
}

internal fun connectionStatusText(state: ClientState): Int = when {
    !state.storageHealthy || state.connectionPresentation == ConnectionPresentation.Permission -> connectionChipText(state.connectionPresentation)
    state.discovering -> R.string.discovering_computer
    state.ready -> R.string.connect_ready
    state.replacingPair -> R.string.connection_replace_pairing
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

