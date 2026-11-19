package seeyuer.sayin.ui

import seeyuer.sayin.data.normalizedText
import seeyuer.sayin.data.AutoSendDestination

internal enum class SpeakInputSource { Local, Cloud, Keyboard; val isRecognition get() = this != Keyboard }
internal enum class SpeakSendMode { Manual, Automatic }
internal enum class AppSendPolicy { Inherit, Automatic, Manual }
internal enum class DraftReview { Manual, Empty, Invalid, Composing, Edited, TargetUnavailable, TargetChanged, Automatic }

internal data class AutoSendCountdown(
    val text: String,
    val version: Long,
    val destination: AutoSendDestination,
    val deadlineMillis: Long,
    /** Total armed window; the send ring shrinks from this span and never from a UI-side constant. */
    val durationMillis: Long,
) {
    fun matches(text: String, version: Long, composing: Boolean, destination: AutoSendDestination?): Boolean =
        this.text == text && this.version == version && !composing && this.destination == destination

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

    fun finish(
        text: String,
        composing: Boolean,
        mode: SpeakSendMode,
        destination: AutoSendDestination?,
        nowMillis: Long,
        delayMillis: Long,
    ): SpeakInteraction {
        val normalized = runCatching { normalizedText(text) }.getOrNull()
        val reason = when {
            text.isBlank() -> DraftReview.Empty
            normalized == null -> DraftReview.Invalid
            mode == SpeakSendMode.Manual -> DraftReview.Manual
            destination == null -> DraftReview.TargetUnavailable
            composing -> DraftReview.Composing
            else -> DraftReview.Automatic
        }
        return copy(
            review = reason,
            countdown = if (reason == DraftReview.Automatic) {
                AutoSendCountdown(text, version, checkNotNull(destination), nowMillis + delayMillis, delayMillis)
            } else null,
        )
    }

    /** Consuming removes the ticket before the caller invokes transport. */
    fun consumeDue(text: String, composing: Boolean, destination: AutoSendDestination?, nowMillis: Long): SpeakInteraction? {
        val pending = countdown ?: return null
        if (!pending.matches(text, version, composing, destination) || nowMillis < pending.deadlineMillis) return null
        return cancelCountdown()
    }

    companion object {
        /** User-selectable auto-send delay in seconds; the default keeps the established three-second window. */
        const val DEFAULT_DELAY_SECONDS = 3
        const val MIN_DELAY_SECONDS = 1
        const val MAX_DELAY_SECONDS = 10
    }
}
