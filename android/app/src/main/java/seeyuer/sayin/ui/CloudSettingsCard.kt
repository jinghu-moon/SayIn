package seeyuer.sayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.designsystem.YuePalette
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal class CloudEditorState(config: CloudConfiguration?) {
    var auth by mutableStateOf(config?.auth ?: DoubaoAuth.AppKey)
    var appKey by mutableStateOf(config?.appKey.orEmpty())
    var appId by mutableStateOf(config?.appId.orEmpty())
    var token by mutableStateOf(config?.accessToken.orEmpty())
    var words by mutableStateOf(config?.hotwords?.joinToString("\n").orEmpty())
    var reveal by mutableStateOf(false)
    private var base = config

    val dirty: Boolean get() = if (base == null) {
        auth != DoubaoAuth.AppKey || appKey.isNotBlank() || appId.isNotBlank() ||
            token.isNotBlank() || words.isNotBlank()
    } else auth != base!!.auth || appKey != base!!.appKey ||
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
    onOpenHotwords: (() -> Unit)? = null,
    hotwordCount: Int = 0,
) {
    val config = state.config
    var confirmClear by remember { mutableStateOf(false) }
    val transformation = if (editor.reveal) androidx.compose.ui.text.input.VisualTransformation.None
        else PasswordVisualTransformation()
    val status = cloudStatus(editor, state)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(
                title = stringResource(R.string.settings_cloud_service_name),
                summary = stringResource(R.string.settings_cloud_service_sub),
                startAction = { Icon(VoiceLinkIcon.Cloud.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
                endActions = { Text(status.first, fontSize = 12.sp, color = status.second) },
            )
        }

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(VoiceLinkIcon.Cloud.vector, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.settings_cloud_upload_note), fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary, lineHeight = 17.sp)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(VoiceLinkIcon.Shield.vector, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.settings_cloud_credentials_note), fontSize = 11.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary, lineHeight = 17.sp)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column {
                Text(stringResource(R.string.settings_cloud_auth_method), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Spacer(Modifier.height(8.dp))
                TabRowWithContour(
                    tabs = listOf(stringResource(R.string.cloud_app_key), stringResource(R.string.settings_cloud_auth_token)),
                    selectedTabIndex = if (editor.auth == DoubaoAuth.AppKey) 0 else 1,
                    onTabSelected = {
                        if (!state.busy) {
                            editor.auth = if (it == 0) DoubaoAuth.AppKey else DoubaoAuth.AppIdToken
                            editor.appKey = ""; editor.appId = ""; editor.token = ""
                        }
                    },
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                        selectedContentColor = MiuixTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().alpha(if (state.busy) .5f else 1f).testTag("cloud-auth-method"),
                )
                if (editor.auth == DoubaoAuth.AppKey) {
                    Spacer(Modifier.height(12.dp))
                    TextField(editor.appKey, { if (it.length <= 4096) editor.appKey = it },
                        Modifier.fillMaxWidth().testTag("cloud-app-key"),
                        label = stringResource(R.string.cloud_app_key), singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = transformation,
                        trailingIcon = {
                            IconButton(onClick = { editor.reveal = !editor.reveal },
                                modifier = Modifier.size(48.dp).testTag("cloud-reveal")) {
                                Icon(
                                    (if (editor.reveal) VoiceLinkIcon.EyeOff else VoiceLinkIcon.Eye).vector,
                                    stringResource(if (editor.reveal) R.string.cloud_hide_secret else R.string.cloud_show_secret),
                                    Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                        })
                } else {
                    Spacer(Modifier.height(12.dp))
                    TextField(editor.appId, { if (it.length <= 4096) editor.appId = it },
                        Modifier.fillMaxWidth().testTag("cloud-app-id"),
                        label = stringResource(R.string.cloud_app_id), singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = transformation)
                    Spacer(Modifier.height(10.dp))
                    TextField(editor.token, { if (it.length <= 4096) editor.token = it },
                        Modifier.fillMaxWidth().testTag("cloud-token"),
                        label = stringResource(R.string.cloud_token), singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = transformation,
                        trailingIcon = {
                            IconButton(onClick = { editor.reveal = !editor.reveal },
                                modifier = Modifier.size(48.dp).testTag("cloud-reveal")) {
                                Icon(
                                    (if (editor.reveal) VoiceLinkIcon.EyeOff else VoiceLinkIcon.Eye).vector,
                                    stringResource(if (editor.reveal) R.string.cloud_hide_secret else R.string.cloud_show_secret),
                                    Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                        })
                }
                val message = state.issue?.let(::cloudIssueText) ?: when (state.status) {
                    CloudSettingsStatus.Loading -> R.string.cloud_loading
                    CloudSettingsStatus.Saving -> R.string.cloud_saving
                    CloudSettingsStatus.Clearing -> R.string.cloud_clearing
                    CloudSettingsStatus.Testing -> R.string.cloud_testing
                    CloudSettingsStatus.Tested -> R.string.cloud_tested
                    CloudSettingsStatus.Saved -> R.string.cloud_saved
                    CloudSettingsStatus.Ready -> null
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(it), fontSize = 11.5.sp,
                        color = if (state.issue != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(
                title = stringResource(R.string.settings_verify_title),
                summary = stringResource(R.string.settings_verify_sub),
                endActions = {
                    TextButton(stringResource(R.string.settings_verify_action), onClick = { settings.test() },
                        enabled = config != null && !state.busy && !editor.dirty, minHeight = 40.dp,
                        colors = plainTextButtonColors(), modifier = Modifier.testTag("cloud-test"))
                },
            )
        }

        if (onOpenHotwords != null) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = stringResource(R.string.settings_hotwords),
                    startAction = { Icon(VoiceLinkIcon.Input.vector, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurfaceSecondary) },
                    endActions = {
                        Text(
                            if (config != null) stringResource(R.string.settings_hotwords_count_only, hotwordCount)
                            else stringResource(R.string.settings_engine_cloud_missing_short),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(VoiceLinkIcon.ChevronRight.vector, null, Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary)
                    },
                    onClick = onOpenHotwords,
                )
            }
        }

        if (config != null || state.issue == CloudIssue.Storage) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = stringResource(R.string.cloud_clear),
                    startAction = { Icon(VoiceLinkIcon.Clear.vector, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.error) },
                    titleColor = BasicComponentColors(color = MiuixTheme.colorScheme.error, disabledColor = MiuixTheme.colorScheme.error),
                    onClick = { confirmClear = true },
                )
            }
        }
    }
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

@Composable
private fun cloudStatus(editor: CloudEditorState, state: CloudSettingsState): Pair<String, Color> {
    val blank = editor.appKey.isBlank() && editor.appId.isBlank() && editor.token.isBlank()
    return when {
        state.issue == CloudIssue.Storage -> stringResource(R.string.settings_engine_cloud_read_error) to MiuixTheme.colorScheme.error
        state.busy && state.status == CloudSettingsStatus.Saving -> stringResource(R.string.cloud_saving) to MiuixTheme.colorScheme.primary
        state.busy && state.status == CloudSettingsStatus.Testing -> stringResource(R.string.cloud_testing) to MiuixTheme.colorScheme.primary
        state.busy -> stringResource(R.string.cloud_loading) to MiuixTheme.colorScheme.primary
        state.config == null && blank -> stringResource(R.string.settings_engine_cloud_missing_short) to warningColor()
        editor.dirty -> stringResource(R.string.settings_cloud_dirty) to warningColor()
        state.config != null -> stringResource(R.string.settings_engine_cloud_configured_short) to successColor()
        else -> stringResource(R.string.settings_engine_cloud_missing_short) to warningColor()
    }
}

@Composable
private fun warningColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.WarningDark else YuePalette.WarningLight

@Composable
private fun successColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < .5f) YuePalette.SuccessDark else YuePalette.SuccessLight

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
