package seeyuer.sayin.ui

import androidx.compose.ui.text.input.TextFieldValue
import seeyuer.sayin.translation.*

/** Identity changes on every source mutation, including editing away and back. */
internal data class TranslationTicket(
    val id: String, val draftId: String, val version: Long, val source: String,
    val modelSha256: String, val templateRevision: String,
) {
    fun matches(draftId: String, version: Long) = this.draftId == draftId && this.version == version
}

internal data class DraftTranslation(
    val ticket: TranslationTicket? = null,
    val value: TextFieldValue = TextFieldValue(),
    val running: Boolean = false,
    val end: TranslationEnd? = null,
    val progress: TranslationProgress? = null,
) {
    val complete get() = ticket != null && !running && end == TranslationEnd.Complete
    fun accepts(id: String, draftId: String, version: Long) = ticket?.let { it.id == id && it.matches(draftId, version) } == true
}
