package seeyuer.sayin.polish

internal enum class PolishCostSource { Unknown, PresetNotConfigured, Estimated, Reported }

internal data class PolishStatsEntry(
    val callId: String = "",
    val timestamp: Long,
    val provider: String,
    val model: String,
    val responseModel: String? = null,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val durationMillis: Long,
    val outcome: String,
    val costSource: PolishCostSource,
    val status: Int?,
    val configId: String = "",
    val configRevision: Long = 0,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val costMicros: Long? = null,
    val costCurrency: String? = null,
    val catalogVersion: String? = null,
    val priceRule: String? = null,
    val purpose: String = "",
)
