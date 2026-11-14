package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
internal fun ConnectionChip(state: ClientState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick, modifier.heightIn(min = 48.dp).testTag("speak-connect-action"),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface),
        cornerRadius = 24.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.busy || state.recovering || state.discovering) CircularProgressIndicator(size = 12.dp, strokeWidth = 2.dp)
            else Box(Modifier.size(8.dp).background(if (state.ready) {
                if (MiuixTheme.colorScheme.background.luminance() < 0.5f) YuePalette.SuccessDark else YuePalette.SuccessLight
            }
                else MiuixTheme.colorScheme.onSurfaceSecondary, CircleShape))
            Text(stringResource(if (state.discovering) R.string.discovering_computer else connectionChipText(state.connectionPresentation)), Modifier.weight(1f, fill = false),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun ConnectionDetails(state: ClientState, onReconnect: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("connection-details"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.paired) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).background(MiuixTheme.colorScheme.background, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                // The current protocol has no authenticated computer display name. Do not invent one.
                Text(stringResource(R.string.connection_computer), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Caption(stringResource(if (state.connection == Connection.ONLINE) R.string.connected else R.string.connection_pair_saved))
            }
        }
        Text(stringResource(connectionHelpText(state.connectionPresentation)), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (state.paired && state.connection == Connection.OFFLINE && state.storageHealthy) {
            Action(R.string.reconnect, primary = true, onClick = onReconnect)
        }
        actions()
    }
}

internal fun connectionChipText(state: ConnectionPresentation): Int = when (state) {
    ConnectionPresentation.Loading -> R.string.loading_storage
    ConnectionPresentation.Storage -> R.string.connection_storage_label
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

internal fun connectionTitle(state: ConnectionPresentation): Int = when (state) {
    ConnectionPresentation.First -> R.string.connection_first_title
    ConnectionPresentation.Ready -> R.string.connected
    ConnectionPresentation.Review -> R.string.connection_review_title
    else -> connectionChipText(state)
}

private fun connectionHelpText(state: ConnectionPresentation): Int = when (state) {
    ConnectionPresentation.Loading -> R.string.loading_storage
    ConnectionPresentation.Storage -> R.string.error_storage
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
