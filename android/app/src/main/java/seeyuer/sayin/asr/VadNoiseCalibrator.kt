package seeyuer.sayin.asr

/** Estimates an initial threshold from the first few hundred milliseconds. */
internal class VadNoiseCalibrator(
    private val calibrationSamples: Int = 400 * SAMPLE_RATE / 1000,
    private val defaultThreshold: Float = 0.45f,
    private val minThreshold: Float = 0.35f,
    private val maxThreshold: Float = 0.60f,
) {
    private var samples = 0
    private var sum = 0.0
    private var max = 0f
    private var threshold: Float? = null

    fun observe(probability: Float, count: Int): Float? {
        threshold?.let { return null }
        samples += count
        sum += probability * count
        max = maxOf(max, probability)
        if (samples < calibrationSamples) return null
        val average = (sum / samples).toFloat()
        threshold = if (max >= 0.70f) {
            defaultThreshold
        } else {
            (average + 0.18f).coerceIn(minThreshold, maxThreshold)
        }
        return threshold
    }

    val value: Float
        get() = threshold ?: defaultThreshold

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
