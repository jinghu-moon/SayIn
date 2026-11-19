package seeyuer.sayin.ui

import seeyuer.sayin.polish.PolishFailure
import seeyuer.sayin.polish.PolishMode

internal data class PolishTicket(
    val id: String,
    val draftId: String,
    val version: Long,
    val start: Int,
    val end: Int,
    val sourceText: String,
    val mode: PolishMode,
    val targetLanguage: String?,
    val configFingerprint: String,
    val promptHash: String,
) {
    fun matches(draftId: String, version: Long) = this.draftId == draftId && this.version == version
}

internal sealed interface PolishOutcome {
    data class Success(val mode: PolishMode, val durationMillis: Long) : PolishOutcome
    data class Failure(val kind: PolishFailure) : PolishOutcome
    data object Cancelled : PolishOutcome
}

internal data class PolishState(
    val ticket: PolishTicket? = null,
    val running: Boolean = false,
    val outcome: PolishOutcome? = null,
    val confirmationPending: Boolean = false,
) {
    val hasResult get() = outcome is PolishOutcome.Success
}
