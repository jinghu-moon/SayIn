package seeyuer.sayin.asr

/** Per-model preview cadence and RTF budget for non-streaming recognition. */
internal data class OfflinePreviewPolicy(
    val intervalSamples: Int,
    val minSamples: Int,
    val rtfBudget: Double,
    val warmupOnPrepare: Boolean,
    val maxBudgetMisses: Int,
)

internal object OfflinePreviewPolicies {
    private const val SAMPLE_RATE = 16_000
    private val qwen06 = OfflinePreviewPolicy(2 * SAMPLE_RATE, 2 * SAMPLE_RATE, 0.20, true, 2)

    fun forModel(id: String): OfflinePreviewPolicy? = when (id) {
        "qwen-06" -> qwen06
        else -> null
    }
}
