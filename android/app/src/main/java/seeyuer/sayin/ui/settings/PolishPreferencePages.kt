package seeyuer.sayin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.R
import seeyuer.sayin.polish.PolishScene
import seeyuer.sayin.ui.SpeakSession
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PolishAppRulesPage(session: SpeakSession, modifier: Modifier,
                                scroll: androidx.compose.foundation.ScrollState) {
    val manager = session.polish
    val current = session.receiver.foregroundApplication?.name
    val known = (listOfNotNull(current) + session.receiver.historyApplications.keys + manager.appRules.keys)
        .map { it.trim() }.filter { it.isNotBlank() }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .sorted()
    var addOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }
    val effective = manager.sceneFor(current)
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.polish_app_current), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
                Text(current ?: stringResource(R.string.polish_app_none), fontSize = 16.sp)
                Text(stringResource(sceneLabel(effective)), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        Card {
            SwitchPreference(
                title = stringResource(R.string.polish_app_rules),
                summary = stringResource(R.string.polish_app_rules_summary),
                checked = manager.appRulesEnabled,
                onCheckedChange = manager::updateAppRules,
            )
        }
        Card {
            known.forEach { application ->
                val override = manager.appRules[application.lowercase(java.util.Locale.ROOT)]
                WindowDropdownPreference(
                    title = application,
                    summary = stringResource(sceneLabel(manager.sceneFor(application))),
                    items = listOf(stringResource(R.string.polish_scene_auto)) +
                        SCENE_ORDER.map { stringResource(sceneLabel(it)) },
                    selectedIndex = override?.let { SCENE_ORDER.indexOf(it) + 1 } ?: 0,
                    onSelectedIndexChange = { index ->
                        manager.updateAppRule(application,
                            if (index == 0) null else SCENE_ORDER.getOrNull(index - 1))
                    },
                )
            }
            BasicComponent(
                title = stringResource(R.string.polish_app_add),
                onClick = { newName = ""; duplicate = false; addOpen = true },
                modifier = Modifier.testTag("polish-app-add"),
            )
        }
    }
    WindowDialog(
        title = stringResource(R.string.polish_app_add),
        summary = if (duplicate) stringResource(R.string.polish_app_duplicate) else null,
        show = addOpen,
        onDismissRequest = { addOpen = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(newName, { newName = it.take(96); duplicate = false }, Modifier.fillMaxWidth(),
                label = stringResource(R.string.polish_app_name), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.polish_cancel_action), { addOpen = false },
                    Modifier.weight(1f), minHeight = 48.dp)
                Button({
                    val name = newName.trim()
                    if (name.isBlank()) return@Button
                    if (known.any { it.equals(name, ignoreCase = true) }) { duplicate = true; return@Button }
                    manager.updateAppRule(name, PolishScene.General)
                    addOpen = false
                }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.polish_save))
                }
            }
        }
    }
}

@Composable
internal fun PolishTermsPage(session: SpeakSession, modifier: Modifier,
                             scroll: androidx.compose.foundation.ScrollState) {
    val manager = session.polish
    var draft by remember { mutableStateOf(manager.hotwords) }
    var error by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.polish_terms_format), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        TextField(draft, { draft = it; error = false },
            Modifier.fillMaxWidth().heightIn(min = 180.dp).testTag("polish-terms-field"))
        if (error) Text(stringResource(R.string.polish_terms_invalid), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
        Button({
            if (validTerms(draft)) manager.updateHotwords(draft.trim()) else error = true
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.polish_save)) }
    }
}

@Composable
internal fun PolishPromptPage(session: SpeakSession, modifier: Modifier,
                              scroll: androidx.compose.foundation.ScrollState) {
    val manager = session.polish
    var draft by remember { mutableStateOf(manager.customRewrite) }
    var error by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.polish_prompt_summary), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        TextField(draft, { draft = it; error = false },
            Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("polish-prompt-field"))
        if (error) Text(stringResource(R.string.polish_prompt_invalid), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.error)
        Button({
            if (validPrompt(draft)) manager.updateCustomRewrite(draft.trim()) else error = true
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.polish_save)) }
    }
}

private val SCENE_ORDER = listOf(
    PolishScene.General, PolishScene.Chat, PolishScene.Email,
    PolishScene.Code, PolishScene.Docs, PolishScene.Search,
)

private fun sceneLabel(scene: PolishScene?): Int = when (scene) {
    null -> R.string.polish_scene_auto
    PolishScene.General -> R.string.polish_scene_general
    PolishScene.Chat -> R.string.polish_scene_chat
    PolishScene.Email -> R.string.polish_scene_email
    PolishScene.Code -> R.string.polish_scene_code
    PolishScene.Docs -> R.string.polish_scene_docs
    PolishScene.Search -> R.string.polish_scene_search
    PolishScene.Clipboard -> R.string.polish_scene_general
}

private fun validTerms(value: String): Boolean {
    val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size > 20) return false
    return lines.all { line ->
        val eq = line.indexOf('=')
        line.codePointCount(0, line.length) <= 60 &&
            eq in 1 until line.lastIndex &&
            line.substring(0, eq).isNotBlank() && line.substring(eq + 1).isNotBlank()
    }
}

private fun validPrompt(value: String): Boolean {
    if (value.any { it == '\n' || it == '\r' || it.isISOControl() }) return false
    return value.codePointCount(0, value.length) <= 200
}
