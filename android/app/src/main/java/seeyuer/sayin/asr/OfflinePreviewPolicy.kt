package seeyuer.sayin.asr

/** Per-model preview cadence and RTF budget for non-streaming recognition. */
internal data class OfflinePreviewPolicy(
    val intervalMs: Long,
    val minSamples: Int,
    val maxSamples: Int,
    val rtfBudget: Double,
)

internal object OfflinePreviewPolicies {
    private const val SAMPLE_RATE = 16_000
    private val qwen06 = OfflinePreviewPolicy(2_000L, 2 * SAMPLE_RATE, 20 * SAMPLE_RATE, 0.20)
    private val heavy = OfflinePreviewPolicy(3_000L, 3 * SAMPLE_RATE, 20 * SAMPLE_RATE, 0.15)

    fun forModel(id: String): OfflinePreviewPolicy? = when (id) {
        "qwen-06" -> qwen06
        "qwen-17", "fun-int8" -> heavy
        else -> null
    }
}
