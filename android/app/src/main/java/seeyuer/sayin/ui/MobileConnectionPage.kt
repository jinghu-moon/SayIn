package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.PairedTarget
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ComputerAction { Edit, Details, Unpair }

@Composable
internal fun MobileConnectionPage(
    state: ClientState,
    onSelect: (String) -> Unit,
    onConnect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onRetryPairing: () -> Unit,
    onScan: () -> Unit,
    onPair: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onUpdateAddress: (String, String, String) -> Unit,
    onReplace: (String) -> Unit,
    maintenance: @Composable () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var openHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var action by rememberSaveable { mutableStateOf(ComputerAction.Edit) }
    val pairing = state.connection in setOf(Connection.PAIRING, Connection.APPROVAL)
    val pairInProgress = pairing || state.pairingRecoveryPending || state.replacingPair
    val canConnect = state.storageHealthy && !pairInProgress && state.connection != Connection.LOADING
    val showPairing = state.storageHealthy && state.issue != ClientIssue.LOCAL_NETWORK_PERMISSION && (adding || pairInProgress)
    val completedId = state.pairingCompletedHostId
    LaunchedEffect(completedId) {
        if (completedId != null) {
            adding = false
            openHostId = completedId
            action = ComputerAction.Edit
        }
    }
    val selected = state.pairedTargets.firstOrNull { it.hostId == state.selectedHostId }
    val openHost = state.pairedTargets.firstOrNull { it.hostId == openHostId }
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp).testTag("page-Connection"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.connection_paired_computers), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Caption(stringResource(R.string.connection_computer_count, state.pairedTargets.size))
                }
                if (state.pairedTargets.isEmpty() && state.storageHealthy && state.connection != Connection.LOADING) {
                    Panel {
                        Text(stringResource(R.string.connection_empty), fontWeight = FontWeight.SemiBold)
                        Caption(stringResource(R.string.connection_empty_hint))
                    }
                }
                for (computer in state.pairedTargets) key(computer.hostId) {
                    val current = computer.hostId == state.selectedHostId
                    val connection = when {
                        current && state.discovering -> Connection.CONNECTING
                        current -> state.connection
                        computer.hostId == state.secondaryHostId -> state.secondaryConnection
                        else -> null
                    }
                    ComputerCard(computer, current, connection, canConnect, state.storageHealthy && !pairInProgress,
                        onSelect = { onSelect(computer.hostId) },
                        onConnect = { onConnect(computer.hostId) },
                        onAction = { openHostId = computer.hostId; action = it })
                }
                if (selected == null && state.pairedTargets.isNotEmpty()) Caption(stringResource(R.string.connection_choose_target))
                if (!state.storageHealthy) Notice(stringResource(R.string.error_storage), VoiceLinkIcon.Alert)
                if (state.connection == Connection.LOADING) Caption(stringResource(R.string.loading_storage))
                if (!pairInProgress && selected != null && state.connection == Connection.ONLINE && !state.ready) {
                    Notice(stringResource(connectionHelpText(state.connectionPresentation)), VoiceLinkIcon.Alert)
                }
                if (!showPairing) maintenance()
            }
            if (canConnect && state.issue != ClientIssue.LOCAL_NETWORK_PERMISSION && state.pairedTargets.size < 8) {
                FloatingActionButton(
                    onClick = { adding = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(64.dp).testTag("add-computer"),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Icon(VoiceLinkIcon.Plus.vector, stringResource(R.string.connection_add_computer), Modifier.size(32.dp), tint = colors.onPrimary)
                }
            }
        }
    }
    OverlayBottomSheet(
        insideMargin = AppSheetInsideMargin,
        title = stringResource(R.string.connection_add_computer),
        show = showPairing,
        allowDismiss = !pairing && !state.replacingPair,
        onDismissRequest = { adding = false },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pairing || state.pairingRecoveryPending) {
                Text(stringResource(connectionText(state.connection)), fontWeight = FontWeight.SemiBold)
                Caption(stringResource(connectionHelpText(state.connectionPresentation)))
                if (state.pairingRecoveryPending && !state.busy) Action(R.string.connection_retry_pairing, onClick = onRetryPairing)
            } else if (!state.replacingPair) {
                Caption(stringResource(R.string.connect_step_open))
            }
            ConnectionActions(state, onScan, onPair, {
                adding = false
                onCancel()
            }, { _, _ -> }, {}, addingOnly = true)
            if (state.issue == ClientIssue.CONNECTION) Notice(stringResource(R.string.error_connection), VoiceLinkIcon.Alert)
            maintenance()
        }
    }
    OverlayBottomSheet(
        insideMargin = AppSheetInsideMargin,
        title = stringResource(when (action) {
            ComputerAction.Edit -> R.string.connection_edit_computer
            ComputerAction.Details -> R.string.connection_device_details
            ComputerAction.Unpair -> R.string.unpair_title
        }),
        show = openHost != null,
        onDismissRequest = {
            openHostId = null
            completedId?.let(onAcknowledge)
        },
    ) {
        if (openHost != null) key(openHost.hostId, action) {
            val close = {
                openHostId = null
                if (completedId == openHost.hostId) onAcknowledge(openHost.hostId)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (action) {
                    ComputerAction.Edit -> ComputerEditor(openHost, selected, completedId == openHost.hostId, close) { alias, setTarget ->
                        onRename(openHost.hostId, alias)
                        if (setTarget) onSelect(openHost.hostId)
                        close()
                    }
                    ComputerAction.Details -> {
                        var editingAddress by remember { mutableStateOf(false) }
                        var endpoint by remember { mutableStateOf(TextFieldValue("${openHost.address}:${openHost.port}")) }
                        ComputerIdentity(openHost)
                        if (openHost.alias.isNotEmpty()) Caption(stringResource(R.string.connection_device_alias, openHost.alias))
                        Text(stringResource(R.string.connection_endpoint, openHost.address, openHost.port))
                        if (editingAddress) {
                            EndpointField(endpoint) { endpoint = it }
                            val address = parseComputerAddress(endpoint.text)
                            Action(R.string.connection_save_address, primary = true, enabled = address != null) {
                                if (address != null) onUpdateAddress(openHost.hostId, address.first, address.second.toString())
                                editingAddress = false
                            }
                        } else {
                            Action(R.string.connection_update_address) { editingAddress = true }
                            if (openHost.hostId == selected?.hostId && !state.busy && !state.discovering) {
                                Action(R.string.connection_replace_pairing) { close(); onReplace(openHost.hostId) }
                            }
                        }
                    }
                    ComputerAction.Unpair -> {
                        Text(openHost.displayName, fontWeight = FontWeight.SemiBold)
                        Caption(stringResource(R.string.connection_device_short_id, openHost.shortId))
                        Caption(stringResource(R.string.unpair_message))
                        if (openHost.hostId == selected?.hostId && state.pairedTargets.size > 1) {
                            Caption(stringResource(R.string.connection_choose_target))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(stringResource(R.string.keep_computer), close, Modifier.weight(1f), minHeight = 48.dp)
                            val dark = colors.background.luminance() < .5f
                            TextButton(stringResource(R.string.unpair_computer), { onForget(openHost.hostId); close() },
                                Modifier.weight(1f).testTag("unpair-confirm"), minHeight = 48.dp,
                                colors = ButtonDefaults.textButtonColors(
                                    color = if (dark) YuePalette.ErrorContainerDark else YuePalette.ErrorContainerLight,
                                    textColor = if (dark) YuePalette.ErrorDark else YuePalette.ErrorLight))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputerCard(
    computer: PairedTarget,
    selected: Boolean,
    connection: Connection?,
    connectEnabled: Boolean,
    manageEnabled: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onAction: (ComputerAction) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val connected = connection == Connection.ONLINE
    val offline = connection == Connection.OFFLINE
    val status = when (connection) {
        Connection.OFFLINE -> R.string.connection_status_offline
        null -> R.string.connection_waiting_to_connect
        else -> connectionText(connection)
    }
    val tone = if (connected) {
        if (colors.background.luminance() < .5f) YuePalette.SuccessDark else YuePalette.SuccessLight
    } else colors.onSurfaceSecondary
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, if (selected) colors.primary else colors.outline.copy(alpha = .55f), shape)
            .testTag("computer-${computer.hostId}"),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 92.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 88.dp)
                    .selectable(selected = selected, enabled = connectEnabled, onClick = onSelect)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(40.dp).background(
                    if (selected) colors.primary.copy(alpha = .12f) else colors.surfaceContainer,
                    RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(22.dp), tint = if (selected) colors.primary else colors.onSurfaceSecondary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(computer.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (computer.systemName != null && computer.systemName != computer.displayName) {
                        Text(computer.systemName, fontSize = 12.sp, color = colors.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.connection_device_short_id, computer.shortId),
                        fontSize = 11.sp, color = colors.onSurfaceSecondary, fontFamily = FontFamily.Monospace)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(tone, CircleShape))
                        Text(stringResource(status), Modifier.weight(1f, fill = false), fontSize = 12.sp, color = tone)
                        if (selected) Box(Modifier.size(22.dp).background(colors.primary.copy(alpha = .12f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(VoiceLinkIcon.Send.vector, stringResource(R.string.connection_current_target), Modifier.size(14.dp), tint = colors.primary)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (manageEnabled) WindowIconDropdownMenu(
                    DropdownEntry(listOf(
                        DropdownItem(stringResource(R.string.connection_edit_computer), onClick = { onAction(ComputerAction.Edit) }),
                        DropdownItem(stringResource(R.string.connection_device_details), onClick = { onAction(ComputerAction.Details) }),
                        DropdownItem(stringResource(R.string.unpair_computer), onClick = { onAction(ComputerAction.Unpair) }),
                    )),
                    Modifier.size(48.dp).testTag("computer-more-${computer.hostId}"), minHeight = 48.dp, minWidth = 48.dp,
                ) {
                    Icon(VoiceLinkIcon.Dots.vector, stringResource(R.string.connection_device_more, computer.displayName), Modifier.size(20.dp))
                }
                if (connectEnabled && (offline || connection == null)) TextButton(
                    stringResource(if (offline) R.string.reconnect else R.string.connection_connect_computer),
                    onConnect, Modifier.testTag("computer-connect-${computer.hostId}"), minHeight = 48.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.textButtonColors(color = colors.primary.copy(alpha = .08f), textColor = colors.primary),
                )
            }
        }
    }
}

@Composable
private fun ComputerIdentity(computer: PairedTarget) {
    Panel {
        Caption(stringResource(R.string.connection_device_system_name))
        Text(computer.systemName ?: stringResource(R.string.connection_device_unknown_name))
        Caption(stringResource(R.string.connection_device_id))
        Text(computer.hostId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun ComputerEditor(computer: PairedTarget, selected: PairedTarget?, newlyPaired: Boolean,
    onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var alias by rememberSaveable { mutableStateOf(computer.alias) }
    var setTarget by rememberSaveable { mutableStateOf(false) }
    if (newlyPaired) Text(stringResource(R.string.connection_pairing_completed), fontWeight = FontWeight.SemiBold)
    ComputerIdentity(computer)
    TextField(alias, { if (it.length <= 40 && it.none(Char::isISOControl)) alias = it },
        Modifier.fillMaxWidth().testTag("computer-alias"), label = stringResource(R.string.connection_device_alias_label), singleLine = true)
    Caption(stringResource(R.string.connection_device_alias_hint))
    if (selected?.hostId == computer.hostId) {
        Caption(stringResource(R.string.connection_current_target))
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(setTarget, role = Role.Checkbox) { setTarget = it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(androidx.compose.ui.state.ToggleableState(setTarget), onClick = null)
            Text(stringResource(R.string.connection_make_target))
        }
        if (selected != null) Caption(stringResource(R.string.connection_target_name, selected.displayName))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(stringResource(if (newlyPaired) R.string.connection_setup_later else android.R.string.cancel),
            onDismiss, Modifier.weight(1f), minHeight = 48.dp)
        TextButton(stringResource(R.string.connection_save_computer), { onSave(alias.trim(), setTarget) },
            Modifier.weight(1f).testTag("save-computer"), minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary())
    }
}
