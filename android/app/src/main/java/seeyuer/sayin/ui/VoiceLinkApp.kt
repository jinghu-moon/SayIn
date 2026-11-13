package seeyuer.sayin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import java.util.UUID
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.data.Connection
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun VoiceLinkApp() {
    val model: VoiceLinkViewModel = viewModel()
    val repository = model.repository
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var port by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("7788")) }
    // Pairing secrets never enter saved instance state.
    var code by remember { mutableStateOf(TextFieldValue()) }
    var showCode by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun networkGranted(): Boolean = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        permissionDenied = !granted
        if (granted) { repository.foreground(true); action?.invoke() }
    }
    fun withNetwork(action: () -> Unit) {
        if (networkGranted()) { repository.foreground(true); action() }
        else if (Build.VERSION.SDK_INT >= 37) { pendingAction = action; networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
    }
    val prompt = stringResource(R.string.scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value -> withNetwork { repository.pairQr(value) } }
    }
    fun launchScanner() {
        scanner.launch(pairingScanOptions(prompt))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        if (granted) launchScanner()
    }
    DisposableEffect(lifecycle, repository) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> repository.foreground(networkGranted())
                Lifecycle.Event.ON_STOP -> repository.foreground(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        repository.foreground(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && networkGranted())
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.savedDraft) {
        if (state.savedDraft == draftId) { draft = TextFieldValue(); draftId = UUID.randomUUID().toString() }
    }
    VoiceLinkContent(
        state = state,
        draft = draft,
        onDraftChange = {
            if (it.text != draft.text) draftId = UUID.randomUUID().toString()
            draft = it
        },
        onSend = { repository.send(draft.text, draftId) },
        onHistoryAction = repository::action,
        connectionActions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.connection == Connection.ONLINE) {
                    Action(if (state.paused) R.string.resume_input else R.string.pause_input,
                        enabled = state.active && state.target != null, onClick = repository::togglePause)
                } else if (state.paired) {
                    Action(R.string.reconnect, enabled = !state.busy) { withNetwork(repository::connect) }
                } else if (!state.busy && state.storageHealthy) {
                    Action(R.string.scan_qr, primary = true) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    Action(if (showCode) R.string.hide_pair_code else R.string.use_pair_code) { showCode = !showCode }
                    if (showCode) {
                        TextField(address, { address = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.ip_address),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        TextField(port, { port = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.port),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextField(code, { code = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.pairing_code),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation())
                        Action(R.string.pair_computer, primary = true, enabled = code.text.length == 8 && address.text.isNotEmpty()) {
                            val entered = code.text
                            code = TextFieldValue()
                            withNetwork { repository.pairCode(address.text, port.text, entered) }
                        }
                    }
                }
                if (!state.paired && state.busy && state.connection != Connection.LOADING) {
                    Text(stringResource(R.string.approve_on_computer))
                    Action(R.string.cancel_pairing, onClick = repository::cancelPairing)
                }
                if (state.paired) {
                    Action(R.string.forget_computer) { confirmForget = true }
                    if (confirmForget) {
                        Text(stringResource(R.string.forget_warning))
                        Action(R.string.confirm_forget) { repository.forget(); confirmForget = false }
                        Action(R.string.keep_computer) { confirmForget = false }
                    }
                }
                state.issue?.let { Notice(stringResource(issueText(it)), VoiceLinkIcon.Alert) }
                if (permissionDenied) Notice(stringResource(R.string.network_permission_denied), VoiceLinkIcon.Alert)
                if (cameraDenied) Notice(stringResource(R.string.camera_permission_denied), VoiceLinkIcon.Alert)
            }
        },
    )
}

@Composable
internal fun Action(label: Int, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val icon = when (label) {
        R.string.scan_qr -> VoiceLinkIcon.Scan
        R.string.send_to_computer -> VoiceLinkIcon.Send
        R.string.reconnect -> VoiceLinkIcon.Wifi
        R.string.mobile_connect_action, R.string.mobile_manage_connection -> VoiceLinkIcon.Computer
        else -> null
    }
    Button(onClick, Modifier.fillMaxWidth(), enabled = enabled, minHeight = 48.dp,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it.vector, null, Modifier.size(20.dp)) }
            Text(stringResource(label))
        }
    }
}

internal fun connectionText(value: Connection): Int = when (value) {
    Connection.LOADING -> R.string.loading_storage
    Connection.OFFLINE -> R.string.connection_offline
    Connection.CONNECTING -> R.string.connecting
    Connection.PAIRING -> R.string.pairing
    Connection.APPROVAL -> R.string.waiting_approval
    Connection.ONLINE -> R.string.connected
}
internal fun issueText(value: ClientIssue): Int = when (value) {
    ClientIssue.INVALID_PAIRING -> R.string.error_pairing
    ClientIssue.CONNECTION -> R.string.error_connection
    ClientIssue.STORAGE -> R.string.error_storage
    ClientIssue.INVALID_TEXT -> R.string.error_text
    ClientIssue.QUOTA -> R.string.error_quota
    ClientIssue.NOT_READY -> R.string.error_not_ready
    ClientIssue.PROTOCOL -> R.string.error_protocol
    ClientIssue.TLS_REQUIRED -> R.string.error_tls
}
internal fun receiptText(value: String): Int = when (value) {
    "accepted" -> R.string.receipt_accepted
    "held" -> R.string.receipt_held
    "executing" -> R.string.receipt_executing
    "injected" -> R.string.receipt_injected
    "cancelled" -> R.string.receipt_cancelled
    "expired" -> R.string.receipt_expired
    "failed" -> R.string.receipt_failed
    "uncertain" -> R.string.receipt_uncertain
    "review" -> R.string.receipt_review
    else -> R.string.receipt_saved
}

internal fun receiptHelp(state: String): Int? = when (state) {
    "accepted" -> R.string.receipt_accepted_help
    "expired" -> R.string.receipt_expired_help
    "held" -> R.string.receipt_held_help
    else -> null
}

internal fun receiptCause(reason: String?): Int? = when (reason) {
        "receiver_focused" -> R.string.receipt_receiver_focused
        "target_missing" -> R.string.receipt_target_missing
        "elevated_target" -> R.string.receipt_elevated_target
        "modifier_held" -> R.string.receipt_modifier_held
        "session_unavailable" -> R.string.receipt_session_unavailable
        else -> null
}
