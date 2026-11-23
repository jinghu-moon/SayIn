package seeyuer.sayin.quick

/**
 * Pure geometry shared by the floating ball and its settings preview.
 *
 * Sizes are dp. A peeking ball must leave at least [MIN_VISIBLE_DP] on screen so it stays
 * tappable; a contained icon must stay at least [MIN_ICON_DP] so it remains recognizable.
 */
internal object BallGeometry {
    const val MIN_VISIBLE_DP = 24f
    const val MIN_ICON_DP = 16f
    const val PEEK_PADDING_DP = 4f
    const val MIN_PEEK_FRACTION = 0.30f
    const val MAX_PEEK_FRACTION = 0.70f
    const val MIN_ICON_FRACTION = 0.40f
    const val MAX_ICON_FRACTION = 0.70f

    /** Requested reveal, raised when necessary so a small ball still leaves 24dp visible. */
    fun effectivePeekFraction(sizeDp: Float, requested: Float): Float {
        val size = sizeDp.coerceAtLeast(1f)
        val fraction = requested.coerceIn(MIN_PEEK_FRACTION, MAX_PEEK_FRACTION)
        return (MIN_VISIBLE_DP / size).coerceAtLeast(fraction).coerceAtMost(1f)
    }

    fun visibleDp(sizeDp: Float, requested: Float): Float =
        sizeDp * effectivePeekFraction(sizeDp, requested)

    /**
     * [keepWhole] only applies while peeking: the icon is shrunk just enough to fit the
     * visible strip, never below [MIN_ICON_DP]. Expanded state always uses the base size.
     */
    fun iconDp(
        sizeDp: Float,
        iconFraction: Float,
        requestedPeek: Float,
        peeked: Boolean,
        keepWhole: Boolean,
    ): Float {
        val base = sizeDp * iconFraction.coerceIn(MIN_ICON_FRACTION, MAX_ICON_FRACTION)
        if (!peeked || !keepWhole) return base
        val available = visibleDp(sizeDp, requestedPeek) - PEEK_PADDING_DP * 2
        return base.coerceAtMost(available.coerceAtLeast(MIN_ICON_DP))
    }
}
