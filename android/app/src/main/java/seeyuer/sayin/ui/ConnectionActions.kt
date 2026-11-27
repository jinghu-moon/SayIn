package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.data.PairedTarget
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One form shared by the connection tab and the speak-page sheet. Secrets are not saveable. */
@Composable
internal fun ConnectionActions(
    state: ClientState,
    onScan: () -> Unit,
    onPair: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onUpdateAddress: (String, String) -> Unit,
    onForget: () -> Unit,
    onSelectHost: (String) -> Unit = {},
    onReplace: () -> Unit = {},
    addingOnly: Boolean = false,
) {
    var form by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(TextFieldValue()) }
    var code by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(state.selectedHostId) {
        updating = false
        confirmUnpair = false
    }
    val pairing = state.connection == Connection.PAIRING || state.connection == Connection.APPROVAL
    LaunchedEffect(state.paired, state.connection) {
        if (!state.paired) { updating = false; confirmUnpair = false; form = false; code = TextFieldValue() }
        if (!state.paired && state.busy) { form = false; code = TextFieldValue() }
        if (pairing || !addingOnly && state.connection == Connection.ONLINE) {
            form = false
            updating = false
            endpoint = TextFieldValue()
            code = TextFieldValue()
        }
    }
    if (!state.storageHealthy || state.connection == Connection.LOADING || state.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.replacingPair && !pairing) Caption(stringResource(R.string.connection_replace_help))
        if ((!state.busy || addingOnly && state.connection == Connection.CONNECTING) && !state.pairingRecoveryPending) {
            if (!form) {
                Action(R.string.scan_qr, primary = !state.paired, onClick = onScan)
                ConnectionTextAction(R.string.use_pair_code) { form = true }
            } else {
                EndpointField(endpoint) { endpoint = it }
                TextField(code, { code = it }, Modifier.fillMaxWidth().testTag("pairing-code"),
                    label = stringResource(R.string.pairing_code), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation())
                Action(R.string.pair_computer, primary = true,
                    enabled = parseComputerAddress(endpoint.text) != null && code.text.matches(Regex("[0-9]{8}"))) {
                    val address = checkNotNull(parseComputerAddress(endpoint.text))
                    val secret = code.text; code = TextFieldValue()
                    onPair(address.first, address.second.toString(), secret)
                }
                ConnectionTextAction(R.string.connection_back) { form = false; code = TextFieldValue() }
            }
        }
        if (pairing || state.replacingPair || !state.paired && state.busy) ConnectionTextAction(R.string.cancel_pairing, onClick = onCancel)
        if (!addingOnly && state.paired && !pairing && !form && !state.replacingPair) {
            if (state.pairedTargets.map { it.hostId }.distinct().size > 1) {
                PairedHostSelector(state.pairedTargets, state.selectedHostId, onSelectHost)
            }
            // Connection details stay visible; no overflow menu hides the address or the maintenance actions.
            Caption(stringResource(R.string.connection_network_help))
            Text(stringResource(R.string.connection_endpoint, state.address, state.port))
            if (state.connection != Connection.ONLINE) {
                if (!updating) Action(R.string.connection_update_address) {
                    endpoint = TextFieldValue("${state.address}:${state.port}")
                    updating = true
                } else {
                    EndpointField(endpoint) { endpoint = it }
                    Action(R.string.connection_save_address, primary = true, enabled = parseComputerAddress(endpoint.text) != null) {
                        val address = checkNotNull(parseComputerAddress(endpoint.text))
                        onUpdateAddress(address.first, address.second.toString())
                        updating = false
                    }
                    ConnectionTextAction(R.string.connection_back) { updating = false }
                }
            }
            if (!state.busy && !state.pairingRecoveryPending && !state.discovering) {
                Action(R.string.connection_replace_pairing, onClick = onReplace)
            }
            UnpairButton { confirmUnpair = true }
        }
    }
    if (confirmUnpair) {
        UnpairDialog(
            onCancel = { confirmUnpair = false },
            onConfirm = { onForget(); confirmUnpair = false },
        )
    }
}

@Composable
private fun PairedHostSelector(
    targets: List<PairedTarget>,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
) {
    val hosts = targets.distinctBy { it.hostId }
    val selectedIndex = hosts.indexOfFirst { it.hostId == selectedHostId }.coerceAtLeast(0)
    WindowDropdownPreference(
        title = stringResource(R.string.connection_computer),
        items = hosts.map { stringResource(R.string.connection_endpoint, it.address, it.port) },
        selectedIndex = selectedIndex,
        startAction = { Icon(VoiceLinkIcon.Computer.vector, null, Modifier.size(22.dp)) },
        modifier = Modifier.fillMaxWidth().testTag("paired-computer-selector"),
        onSelectedIndexChange = { index -> hosts.getOrNull(index)?.let { onSelectHost(it.hostId) } },
    )
}

@Composable
private fun UnpairButton(onClick: () -> Unit) {
    val dark = MiuixTheme.colorScheme.background == YuePalette.Neutral100
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unpair-computer"),
        minHeight = 48.dp,
        colors = ButtonDefaults.buttonColors(
            color = if (dark) YuePalette.ErrorContainerLight else YuePalette.ErrorContainerDark,
            contentColor = if (dark) YuePalette.ErrorLight else YuePalette.ErrorDark,
        )) {
        Text(stringResource(R.string.unpair_computer), fontWeight = FontWeight.SemiBold)
    }
}

/** Explicit confirmation: unpairing removes the saved credentials, so the next connection needs pairing. */
@Composable
private fun UnpairDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val dark = MiuixTheme.colorScheme.background == YuePalette.Neutral100
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.padding(24.dp).widthIn(max = 320.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                cornerRadius = 24.dp,
                insideMargin = PaddingValues(24.dp),
            ) {
                Text(stringResource(R.string.unpair_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.unpair_message), fontSize = 14.sp, lineHeight = 22.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onCancel, Modifier.weight(1f).heightIn(min = 48.dp).testTag("unpair-cancel"),
                        cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.keep_computer))
                    }
                    Button(onConfirm, Modifier.weight(1f).heightIn(min = 48.dp).testTag("unpair-confirm"),
                        cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = if (dark) YuePalette.ErrorContainerLight else YuePalette.ErrorContainerDark,
                            contentColor = if (dark) YuePalette.ErrorLight else YuePalette.ErrorDark,
                        )) {
                        Text(stringResource(R.string.unpair_computer), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EndpointField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth().testTag("computer-address"),
        label = stringResource(R.string.connection_address), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
}

/** Numeric IPv4 plus optional port; never starts DNS lookup or changes trust. */
internal fun parseComputerAddress(value: String): Pair<String, Int>? = runCatching {
    val parts = value.trim().split(':')
    require(parts.size in 1..2)
    val address = parts[0]
    val octets = address.split('.')
    require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } &&
        (it.length == 1 || it[0] != '0') && it.toInt() in 0..255 })
    require(octets[0].toInt() !in 224..239 && address != "0.0.0.0")
    val port = if (parts.size == 2) parts[1].toInt().also { require(parts[1].all { it in '0'..'9' }) } else 7788
    require(port in 1..65535)
    address to port
}.getOrNull()

@Composable
private fun ConnectionTextAction(label: Int, onClick: () -> Unit) {
    TextButton(stringResource(label), onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), minHeight = 48.dp,
        colors = ButtonDefaults.textButtonColors(color = androidx.compose.ui.graphics.Color.Transparent,
            textColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary))
}
