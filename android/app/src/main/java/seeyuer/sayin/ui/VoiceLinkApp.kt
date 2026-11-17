package seeyuer.sayin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.core.app.ActivityCompat
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
import seeyuer.sayin.data.receptionScope
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun VoiceLinkApp(quick: Boolean = false, onExpand: (Boolean) -> Unit = {}, onDismiss: () -> Unit = {},
    openSettings: Boolean = false, entryVersion: Int = 0,
    sharedImage: android.net.Uri? = null, shareVersion: Int = 0,
) {
    val context = LocalContext.current
    val application = context.applicationContext as seeyuer.sayin.SayInApplication
    val model = application.model
    val repository = model.repository
    val state by repository.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val speaking = model.speaking
    val uiLanguageTags = androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
    LaunchedEffect(uiLanguageTags) { speaking.uiLanguageChanged(uiLanguageTags) }
    val foregroundOwner = remember { Any() }
    val activity = LocalActivity.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun networkGranted(): Boolean = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        permissionDenied = !granted
        if (granted) { model.foreground(foregroundOwner, true); action?.invoke() }
    }
    fun withNetwork(action: () -> Unit) {
        if (networkGranted()) { model.foreground(foregroundOwner, true); action() }
        else if (Build.VERSION.SDK_INT >= 37) { pendingAction = action; networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
    }
    val prompt = stringResource(R.string.scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value -> withNetwork { repository.pairQr(value) } }
    }
    fun launchScanner() {
        cameraDenied = false
        scanner.launch(pairingScanOptions(prompt))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        if (granted) launchScanner()
    }
    DisposableEffect(lifecycle, repository) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (networkGranted()) permissionDenied = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraDenied = false
                    model.foreground(foregroundOwner, true)
                }
                Lifecycle.Event.ON_STOP -> model.foreground(foregroundOwner, false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        model.foreground(foregroundOwner, lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.foreground(foregroundOwner, false) }
    }
    VoiceLinkContent(
        state = state,
        session = speaking,
        phrases = application.phrases,
        onSend = model::send,
        cloudSettings = application.cloudSettings,
        quick = quick, onExpand = onExpand, onDismiss = onDismiss, openSettings = openSettings, entryVersion = entryVersion,
        sharedImage = sharedImage, shareVersion = shareVersion,
        onReconnect = { withNetwork(repository::connect) },
        onHistoryAction = repository::action,
        loadHistoryImage = repository::historyImage,
        loadDraftImage = repository::draftImage,
        onSelectImage = repository::selectImage,
        onSendImage = { repository.sendImage(it, state.receptionScope) },
        onCancelImage = repository::cancelImage,
        onClearImage = repository::clearImage,
        connectionActions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionActions(state,
                    onScan = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    onPair = { address, port, code -> withNetwork { repository.pairCode(address, port, code) } },
                    onCancel = repository::cancelPairing,
                    onUpdateAddress = { address, port -> withNetwork { repository.updateAddress(address, port) } },
                    onForget = repository::forget)
                state.issue?.takeIf { it !in setOf(ClientIssue.STORAGE, ClientIssue.CONNECTION, ClientIssue.LOCAL_NETWORK_PERMISSION) }
                    ?.let { Notice(stringResource(issueText(it)), VoiceLinkIcon.Alert) }
                if (permissionDenied || state.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) {
                    PermissionRecovery(R.string.network_permission_denied, R.string.connect_allow_network,
                        permanent = permissionDenied && activity != null && Build.VERSION.SDK_INT >= 37 &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_LOCAL_NETWORK),
                        onRequest = { withNetwork {} }, onSettings = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}")))
                        })
                }
                if (cameraDenied && !state.paired && !state.busy) PermissionRecovery(R.string.camera_permission_denied, R.string.connect_camera_retry,
                    permanent = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
                    onRequest = { cameraPermission.launch(Manifest.permission.CAMERA) }, onSettings = {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")))
                    })
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
        R.string.mobile_connect_action -> VoiceLinkIcon.Computer
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
    ClientIssue.LOCAL_NETWORK_PERMISSION -> R.string.network_permission_denied
    ClientIssue.DISCOVERY -> R.string.error_discovery
    ClientIssue.CANDIDATE_CONNECTION -> R.string.error_candidate_connection
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

/** Permission recovery never forgets a host or starts a new pairing implicitly. */
@Composable
internal fun PermissionRecovery(message: Int, request: Int, permanent: Boolean, onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Notice(stringResource(message), VoiceLinkIcon.Shield)
        Action(if (permanent) R.string.connect_open_settings else request, primary = true,
            onClick = if (permanent) onSettings else onRequest)
    }
}
