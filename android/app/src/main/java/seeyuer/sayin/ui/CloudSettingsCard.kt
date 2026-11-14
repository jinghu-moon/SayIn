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

@Composable
internal fun CloudSettingsCard(settings: CloudSettings, state: CloudSettingsState) {
    val config = state.config
    // Secrets are deliberately not rememberSaveable and are never stored in the view hierarchy bundle.
    var auth by remember(config) { mutableStateOf(config?.auth ?: DoubaoAuth.AppKey) }
    var appKey by remember(config) { mutableStateOf(config?.appKey.orEmpty()) }
    var appId by remember(config) { mutableStateOf(config?.appId.orEmpty()) }
    var token by remember(config) { mutableStateOf(config?.accessToken.orEmpty()) }
    var words by remember(config) { mutableStateOf(config?.hotwords?.joinToString("\n").orEmpty()) }
    val dirty = config == null || auth != config.auth || appKey != config.appKey || appId != config.appId ||
        token != config.accessToken || words != config.hotwords.joinToString("\n")
    Panel {
        Text(stringResource(R.string.cloud_title), fontWeight = FontWeight.SemiBold)
        Notice(stringResource(R.string.cloud_disclosure), VoiceLinkIcon.Shield)
        if (auth == DoubaoAuth.AppKey) {
            TextField(appKey, { if (it.length <= 4096) appKey = it }, Modifier.fillMaxWidth().testTag("cloud-app-key"),
                label = stringResource(R.string.cloud_app_key), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation())
        } else {
            TextField(appId, { if (it.length <= 4096) appId = it }, Modifier.fillMaxWidth().testTag("cloud-app-id"),
                label = stringResource(R.string.cloud_app_id), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation())
            TextField(token, { if (it.length <= 4096) token = it }, Modifier.fillMaxWidth().testTag("cloud-token"),
                label = stringResource(R.string.cloud_token), singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation())
        }
        TextButton(stringResource(if (auth == DoubaoAuth.AppKey) R.string.cloud_use_legacy else R.string.cloud_use_key),
            onClick = { auth = if (auth == DoubaoAuth.AppKey) DoubaoAuth.AppIdToken else DoubaoAuth.AppKey; appKey = ""; appId = ""; token = "" },
            enabled = !state.busy, minHeight = 48.dp)
        TextField(words, { if (it.length <= 4096) words = it }, Modifier.fillMaxWidth().testTag("cloud-hotwords"),
            label = stringResource(R.string.cloud_hotwords), enabled = !state.busy, maxLines = 5)
        Caption(stringResource(R.string.cloud_hotwords_help))
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
            TextButton(stringResource(R.string.cloud_save), onClick = { settings.save(auth, appKey, appId, token, words) },
                enabled = !state.busy && dirty, minHeight = 48.dp, modifier = Modifier.weight(1f).testTag("cloud-save"))
            TextButton(stringResource(R.string.cloud_test), onClick = { settings.test() },
                enabled = !state.busy && !dirty, minHeight = 48.dp,
                modifier = Modifier.weight(1f).testTag("cloud-test"))
        }
        Caption(stringResource(R.string.cloud_test_help))
        if (config != null || state.issue == CloudIssue.Storage) TextButton(stringResource(R.string.cloud_clear),
            onClick = { settings.clear() }, enabled = !state.busy, minHeight = 48.dp,
            modifier = Modifier.fillMaxWidth().testTag("cloud-clear"))
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
