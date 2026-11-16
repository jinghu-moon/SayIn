package seeyuer.sayin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.basic.*

/** One form shared by the connection tab and the speak-page sheet. Secrets are not saveable. */
@Composable
internal fun ConnectionActions(
    state: ClientState,
    onScan: () -> Unit,
    onPair: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onUpdateAddress: (String, String) -> Unit,
    onForget: () -> Unit,
) {
    var form by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(TextFieldValue()) }
    var code by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(state.paired, state.connection) {
        if (!state.paired) { help = false; updating = false; confirmForget = false }
        if (!state.paired && state.busy) { form = false; code = TextFieldValue() }
        if (state.connection == Connection.ONLINE) { form = false; updating = false; code = TextFieldValue() }
    }
    if (!state.storageHealthy || state.connection == Connection.LOADING || state.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.paired && !state.busy) {
            if (!form) {
                Action(R.string.scan_qr, primary = true, onClick = onScan)
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
        if (!state.paired && state.busy) ConnectionTextAction(R.string.cancel_pairing, onClick = onCancel)
        if (state.paired) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WindowIconDropdownMenu(DropdownEntry(buildList {
                    add(DropdownItem(stringResource(R.string.connection_help), onClick = { help = !help; updating = false; confirmForget = false }))
                    if (state.connection != Connection.ONLINE) add(DropdownItem(stringResource(R.string.connection_update_address), onClick = {
                        help = true; updating = true; confirmForget = false; endpoint = TextFieldValue("${state.address}:${state.port}")
                    }))
                    add(DropdownItem(stringResource(R.string.forget_computer), onClick = { help = true; confirmForget = true; updating = false }))
                }), Modifier.size(48.dp).testTag("connection-help")) {
                    Icon(VoiceLinkIcon.More.vector, stringResource(R.string.connect_more), Modifier.size(22.dp))
                }
            }
            if (help) {
                Caption(stringResource(R.string.connection_network_help))
                Text(stringResource(R.string.connection_endpoint, state.address, state.port))
                if (state.connection != Connection.ONLINE) {
                    if (!updating) Action(R.string.connection_update_address) {
                        endpoint = TextFieldValue("${state.address}:${state.port}"); updating = true
                    } else {
                        EndpointField(endpoint) { endpoint = it }
                        Action(R.string.connection_save_address, primary = true, enabled = parseComputerAddress(endpoint.text) != null) {
                            val address = checkNotNull(parseComputerAddress(endpoint.text))
                            onUpdateAddress(address.first, address.second.toString()); updating = false
                        }
                        ConnectionTextAction(R.string.connection_back) { updating = false }
                    }
                }
                if (!confirmForget) Action(R.string.forget_computer) { confirmForget = true }
                else {
                    Caption(stringResource(R.string.forget_warning))
                    Action(R.string.confirm_forget) { onForget(); confirmForget = false }
                    Action(R.string.keep_computer) { confirmForget = false }
                }
            }
        }
    }
}

@Composable
private fun EndpointField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
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
