package seeyuer.sayin.ui.assist

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import seeyuer.sayin.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Fixed footer action; the panel owns system insets, the list owns scrolling. */
@Composable
internal fun AssistClearAction(enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Button(
            onClick,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
            enabled = enabled,
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
            ),
        ) { Text(stringResource(R.string.assist_clear_all), fontWeight = FontWeight.SemiBold) }
    }
}
