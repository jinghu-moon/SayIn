package seeyuer.sayin.ui.localization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import seeyuer.sayin.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppLanguagePicker(modifier: Modifier = Modifier) {
    // Re-read the platform/AppCompat preference after configuration changes.
    val configuration = LocalConfiguration.current
    val selected = remember(configuration) { AppLanguages.current() }
    val focusManager = LocalFocusManager.current
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.language_title), style = MiuixTheme.textStyles.title2)
            Text(stringResource(R.string.language_description), color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(
                                selected = selected == language,
                                role = Role.RadioButton,
                                onClick = {
                                    if (selected != language) {
                                        focusManager.clearFocus()
                                        AppLanguages.select(language)
                                    }
                                },
                            ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected == language, onClick = null)
                        Text(stringResource(language.labelRes))
                    }
                }
            }
        }
    }
}
