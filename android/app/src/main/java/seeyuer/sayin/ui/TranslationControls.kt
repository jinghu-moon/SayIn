package seeyuer.sayin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.R
import seeyuer.sayin.translation.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationPreparationSheet(session: SpeakSession, show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.prepareTranslationModel { checkNotNull(context.contentResolver.openInputStream(uri)) }
    }
    OverlayBottomSheet(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.translation_prepare_title), style = MiuixTheme.textStyles.title2)
            Text(stringResource(R.string.translation_prepare_description))
            Button({ onDismiss(); session.prepareTranslationModel() }, Modifier.fillMaxWidth().testTag("prepare-translation-model")) {
                Text(stringResource(R.string.translation_prepare_download))
            }
            if (BuildConfig.DEBUG) TextButton(stringResource(R.string.translation_import_development), {
                onDismiss(); importer.launch(arrayOf("*/*"))
            }, Modifier.fillMaxWidth(), minHeight = 48.dp, colors = plainTextButtonColors())
        }
    }
}

internal fun translationEndText(end: TranslationEnd?): Int = when (end) {
    TranslationEnd.Complete -> R.string.translation_complete
    TranslationEnd.Cancelled -> R.string.translation_cancelled
    TranslationEnd.Truncated -> R.string.translation_truncated
    TranslationEnd.InputTooLong -> R.string.translation_input_limit
    TranslationEnd.Timeout -> R.string.translation_timeout
    TranslationEnd.ModelMissing, TranslationEnd.Integrity -> R.string.translation_model_unavailable
    else -> R.string.translation_error
}
