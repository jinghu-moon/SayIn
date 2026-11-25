package seeyuer.sayin.data

/** Fixed reconnect schedule required by the v3 mobile client contract. */
internal fun reconnectDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
    0 -> 1_000L
    1 -> 2_000L
    2 -> 5_000L
    else -> 10_000L
}
