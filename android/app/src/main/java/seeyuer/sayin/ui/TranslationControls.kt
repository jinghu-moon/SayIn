package seeyuer.sayin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.R
import seeyuer.sayin.translation.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationControls(session: SpeakSession, compact: Boolean = false, onTranslate: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (session.translation.ticket != null) {
            TextButton(stringResource(R.string.translation_original), { session.showTranslation(false) },
                Modifier.weight(1f).testTag("translation-original").semantics { selected = !session.translationShown }, minHeight = 48.dp, minWidth = 0.dp,
                colors = plainTextButtonColors(), insideMargin = PaddingValues(4.dp))
            TextButton(stringResource(R.string.translation_english), { session.showTranslation(true) },
                Modifier.weight(1f).testTag("translation-english").semantics { selected = session.translationShown }, minHeight = 48.dp, minWidth = 0.dp,
                colors = plainTextButtonColors(), insideMargin = PaddingValues(4.dp))
        }
        if (session.translation.running || session.translationStopping) {
            TextButton(stringResource(R.string.asr_cancel), { session.stopTranslation() },
                Modifier.weight(1f).testTag("cancel-translation"), minHeight = 48.dp, minWidth = 0.dp,
                colors = plainTextButtonColors(), enabled = !session.translationStopping)
        } else TextButton(stringResource(R.string.translation_action), onTranslate,
            Modifier.weight(if (session.translation.ticket != null) 1.5f else 1f).testTag("translate-draft"),
            minHeight = 48.dp, minWidth = 0.dp, colors = plainTextButtonColors(),
            enabled = session.canTranslateDraft)
    }
    if (session.translationShown && !compact) Text(stringResource(R.string.translation_manual_hint), Modifier.testTag("translation-manual-hint"),
        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    if (session.modelPreparation != null) {
        val value = checkNotNull(session.modelPreparation)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (value.stage == ModelPreparationStage.Verifying) R.string.translation_preparing_verify else R.string.translation_preparing,
                (100 * value.bytes / value.total.coerceAtLeast(1)).toInt()), Modifier.weight(1f).testTag("translation-model-progress"), fontSize = 12.sp)
            TextButton(stringResource(R.string.asr_cancel), session::cancelModelPreparation, minHeight = 48.dp,
                colors = plainTextButtonColors())
        }
    }
    if (session.modelPreparationFailed) Text(stringResource(R.string.translation_prepare_failed), fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
}

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
