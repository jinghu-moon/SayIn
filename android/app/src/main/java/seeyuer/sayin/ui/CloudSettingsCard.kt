package seeyuer.sayin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowDialog

internal class CloudEditorState(config: CloudConfiguration?) {
    var auth by mutableStateOf(config?.auth ?: DoubaoAuth.AppKey)
    var appKey by mutableStateOf(config?.appKey.orEmpty())
    var appId by mutableStateOf(config?.appId.orEmpty())
    var token by mutableStateOf(config?.accessToken.orEmpty())
    var words by mutableStateOf(config?.hotwords?.joinToString("\n").orEmpty())
    var reveal by mutableStateOf(false)
    private var base = config

    val dirty: Boolean get() = base == null || auth != base!!.auth || appKey != base!!.appKey ||
        appId != base!!.appId || token != base!!.accessToken || words != base!!.hotwords.joinToString("\n")
    val filled: Boolean get() = if (auth == DoubaoAuth.AppKey) appKey.isNotBlank() else appId.isNotBlank() && token.isNotBlank()

    fun reset(config: CloudConfiguration?) {
        base = config
        auth = config?.auth ?: DoubaoAuth.AppKey
        appKey = config?.appKey.orEmpty()
        appId = config?.appId.orEmpty()
        token = config?.accessToken.orEmpty()
        words = config?.hotwords?.joinToString("\n").orEmpty()
        reveal = false
    }

    fun save(settings: CloudSettings) = settings.save(auth, appKey, appId, token, words)
}

@Composable
internal fun CloudSettingsCard(
    editor: CloudEditorState,
    settings: CloudSettings,
    state: CloudSettingsState,
    showHotwords: Boolean = true,
    showSave: Boolean = true,
) {
    val config = state.config
    var confirmClear by remember { mutableStateOf(false) }
    val transformation = if (editor.reveal) androidx.compose.ui.text.input.VisualTransformation.None
        else PasswordVisualTransformation()
    Panel {
        Text(stringResource(R.string.cloud_title), fontWeight = FontWeight.SemiBold)
        Notice(stringResource(R.string.cloud_disclosure), VoiceLinkIcon.Shield)
        if (editor.auth == DoubaoAuth.AppKey) {
            TextField(editor.appKey, { if (it.length <= 4096) editor.appKey = it }, Modifier.fillMaxWidth().testTag("cloud-app-key"),
                label = stringResource(R.string.cloud_app_key), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = transformation)
        } else {
            TextField(editor.appId, { if (it.length <= 4096) editor.appId = it }, Modifier.fillMaxWidth().testTag("cloud-app-id"),
                label = stringResource(R.string.cloud_app_id), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = transformation)
            TextField(editor.token, { if (it.length <= 4096) editor.token = it }, Modifier.fillMaxWidth().testTag("cloud-token"),
                label = stringResource(R.string.cloud_token), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = transformation)
        }
        TextButton(stringResource(if (editor.auth == DoubaoAuth.AppKey) R.string.cloud_use_legacy else R.string.cloud_use_key),
            onClick = {
                editor.auth = if (editor.auth == DoubaoAuth.AppKey) DoubaoAuth.AppIdToken else DoubaoAuth.AppKey
                editor.appKey = ""; editor.appId = ""; editor.token = ""
            },
            enabled = !state.busy, minHeight = 48.dp)
        TextButton(stringResource(if (editor.reveal) R.string.cloud_hide_secret else R.string.cloud_show_secret),
            onClick = { editor.reveal = !editor.reveal }, enabled = !state.busy, minHeight = 48.dp,
            modifier = Modifier.testTag("cloud-reveal"))
        if (showHotwords) {
            TextField(editor.words, { if (it.length <= 4096) editor.words = it }, Modifier.fillMaxWidth().testTag("cloud-hotwords"),
                label = stringResource(R.string.cloud_hotwords), enabled = !state.busy, maxLines = 5)
            Caption(stringResource(R.string.cloud_hotwords_help))
        }
        val message = state.issue?.let(::cloudIssueText) ?: when (state.status) {
            CloudSettingsStatus.Loading -> R.string.cloud_loading
            CloudSettingsStatus.Saving -> R.string.cloud_saving
            CloudSettingsStatus.Clearing -> R.string.cloud_clearing
            CloudSettingsStatus.Testing -> R.string.cloud_testing
            CloudSettingsStatus.Tested -> R.string.cloud_tested
            CloudSettingsStatus.Saved -> R.string.cloud_saved
            CloudSettingsStatus.Ready -> if (config != null) R.string.cloud_configured else R.string.cloud_setup_hint
        }
        Caption(stringResource(message))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSave) TextButton(stringResource(R.string.cloud_save), onClick = { editor.save(settings) },
                enabled = !state.busy && editor.dirty && editor.filled, minHeight = 48.dp,
                modifier = Modifier.weight(1f).testTag("cloud-save"))
            TextButton(stringResource(R.string.cloud_test), onClick = { settings.test() },
                enabled = !state.busy && !editor.dirty, minHeight = 48.dp,
                modifier = Modifier.weight(1f).testTag("cloud-test"))
        }
        Caption(stringResource(R.string.cloud_test_help))
        if (config != null || state.issue == CloudIssue.Storage) TextButton(stringResource(R.string.cloud_clear),
            onClick = { confirmClear = true }, enabled = !state.busy, minHeight = 48.dp,
            modifier = Modifier.fillMaxWidth().testTag("cloud-clear"))
        WindowDialog(
            title = stringResource(R.string.cloud_clear_confirm_title),
            summary = stringResource(R.string.cloud_clear_confirm_message),
            show = confirmClear,
            onDismissRequest = { confirmClear = false },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.asr_cancel), onClick = { confirmClear = false },
                    modifier = Modifier.weight(1f).testTag("cloud-clear-cancel"))
                TextButton(stringResource(R.string.cloud_clear_confirm), onClick = {
                    confirmClear = false; settings.clear()
                }, modifier = Modifier.weight(1f).testTag("cloud-clear-confirm"))
            }
        }
    }
}

private fun cloudIssueText(issue: CloudIssue): Int = when (issue) {
    CloudIssue.Configuration -> R.string.cloud_error_configuration
    CloudIssue.Storage -> R.string.cloud_error_storage
    CloudIssue.Network -> R.string.cloud_test_error_network
    CloudIssue.Authentication -> R.string.cloud_error_authentication
    CloudIssue.AccessDenied -> R.string.cloud_error_access_denied
    CloudIssue.Quota -> R.string.cloud_error_quota
    CloudIssue.Timeout -> R.string.cloud_test_error_timeout
    CloudIssue.Protocol -> R.string.cloud_test_error_protocol
    CloudIssue.Service -> R.string.cloud_error_service
}
