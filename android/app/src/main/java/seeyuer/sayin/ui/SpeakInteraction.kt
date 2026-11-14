package seeyuer.sayin.ui

import seeyuer.sayin.data.normalizedText
import seeyuer.sayin.data.ReceptionScope

internal enum class SpeakInputSource { Local, Cloud, Keyboard; val isRecognition get() = this != Keyboard }
internal enum class SpeakSendMode { Manual, Automatic }
internal enum class DraftReview { Manual, Empty, Invalid, Composing, Edited, Offline, Automatic }

internal data class AutoSendCountdown(
    val text: String,
    val version: Long,
    val scope: ReceptionScope,
    val deadlineMillis: Long,
) {
    fun matches(text: String, version: Long, composing: Boolean, scope: ReceptionScope?): Boolean =
        this.text == text && this.version == version && !composing && this.scope == scope

    fun secondsRemaining(nowMillis: Long): Int =
        ((deadlineMillis - nowMillis).coerceAtLeast(0) + 999).div(1000).toInt()
}

/** Input-source selection never owns or replaces the draft. Only Finish can arm sending. */
internal data class SpeakInteraction(
    val version: Long = 0,
    val review: DraftReview? = null,
    val countdown: AutoSendCountdown? = null,
) {
    fun inputChanged(): SpeakInteraction = copy(
        version = version + 1,
        review = null,
        countdown = null,
    )

    fun edit(): SpeakInteraction = copy(
        countdown = null,
        review = if (review != null) DraftReview.Edited else null,
    )

    fun cancelCountdown(): SpeakInteraction = copy(
        countdown = null,
        review = if (countdown != null) DraftReview.Manual else review,
    )

    fun finish(text: String, composing: Boolean, mode: SpeakSendMode, scope: ReceptionScope?, nowMillis: Long): SpeakInteraction {
        val normalized = runCatching { normalizedText(text) }.getOrNull()
        val reason = when {
            text.isBlank() -> DraftReview.Empty
            normalized == null -> DraftReview.Invalid
            composing -> DraftReview.Composing
            mode == SpeakSendMode.Manual -> DraftReview.Manual
            scope == null -> DraftReview.Offline
            else -> DraftReview.Automatic
        }
        return copy(
            review = reason,
            countdown = if (reason == DraftReview.Automatic) {
                AutoSendCountdown(text, version, checkNotNull(scope), nowMillis + COUNTDOWN_MILLIS)
            } else null,
        )
    }

    /** Consuming removes the ticket before the caller invokes transport. */
    fun consumeDue(text: String, composing: Boolean, scope: ReceptionScope?, nowMillis: Long): SpeakInteraction? {
        val pending = countdown ?: return null
        if (!pending.matches(text, version, composing, scope) || nowMillis < pending.deadlineMillis) return null
        return cancelCountdown()
    }

    companion object {
        const val COUNTDOWN_MILLIS = 3000L
    }
}
