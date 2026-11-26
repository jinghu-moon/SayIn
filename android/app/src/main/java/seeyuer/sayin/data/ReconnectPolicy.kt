package seeyuer.sayin.data

/** Fixed reconnect schedule required by the v3 mobile client contract. */
internal fun reconnectDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
    0 -> 1_000L
    1 -> 2_000L
    2 -> 5_000L
    else -> 10_000L
}

/** The repository's mailbox owns retries across target switches. */
internal class PairReconnectPolicy {
    private val attempts = mutableMapOf<Pair<String, String>, Int>()

    fun nextDelay(hostId: String, pairId: String): Long {
        val key = hostId to pairId
        val attempt = attempts[key] ?: 0
        attempts[key] = (attempt + 1).coerceAtMost(3)
        return reconnectDelayMs(attempt)
    }

    fun reset(hostId: String, pairId: String) {
        attempts.remove(hostId to pairId)
    }
}

internal fun <T : Any> hotPairIds(current: T, previous: T?, previousUnresolved: Boolean): List<T> =
    if (previousUnresolved && previous != null && previous != current) listOf(current, previous)
    else listOf(current)
