package seeyuer.sayin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import seeyuer.sayin.R
import seeyuer.sayin.models.ModelCategory
import seeyuer.sayin.translation.TranslationEnd
import seeyuer.sayin.ui.settings.ModelSelectionSheet
import seeyuer.sayin.ui.settings.ModelImportPreview
import seeyuer.sayin.ui.settings.ModelProgressSheet
import seeyuer.sayin.ui.settings.rememberModelFolderPicker

@Composable
internal fun LocalModelPreparationSheet(session: SpeakSession, category: ModelCategory?, onDismiss: () -> Unit) {
    val models = session.localModels
    val pickFolder = rememberModelFolderPicker(models)
    var importing by rememberSaveable { mutableStateOf(false) }
    if (category != null) {
        ModelSelectionSheet(session, category, true, onDismiss) {
            importing = true
            onDismiss()
            pickFolder()
        }
    }
    if (importing) {
        ModelImportPreview(models, pickFolder)
        ModelProgressSheet(models)
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
