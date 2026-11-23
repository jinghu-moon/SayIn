package seeyuer.sayin.asr

/** Pure endpoint timing. VAD inference stays outside; every product delay lives here. */
internal class VadEndpointTimer(
    private val minSpeechSamples: Long = 150L * SAMPLE_RATE / 1000,
    private val trailingSamples: Long = 400L * SAMPLE_RATE / 1000,
    private val noSpeechSamples: Long = 10L * SAMPLE_RATE,
) {
    data class State(
        val heardSpeech: Boolean = false,
        val ending: Boolean = false,
        val ended: Boolean = false,
        val noSpeech: Boolean = false,
    )

    private var totalSamples = 0L
    private var voicedSamples = 0L
    private var quietSamples = 0L
    private var heardSpeech = false
    private var ended = false

    fun accept(probability: Float, threshold: Float, samples: Int): State {
        if (ended) return State(heardSpeech = true, ended = true)
        totalSamples += samples
        if (probability >= threshold) {
            quietSamples = 0
            voicedSamples += samples
            if (!heardSpeech && voicedSamples >= minSpeechSamples) heardSpeech = true
            return State(heardSpeech = heardSpeech)
        }
        if (heardSpeech) {
            quietSamples += samples
            if (quietSamples >= finalizeSamples()) {
                ended = true
                return State(heardSpeech = true, ended = true)
            }
            return State(heardSpeech = true, ending = quietSamples >= trailingSamples)
        }
        if (totalSamples >= noSpeechSamples) {
            ended = true
            return State(noSpeech = true)
        }
        return State()
    }

    private fun finalizeSamples(): Long = when {
        voicedSamples < 1_500L * SAMPLE_RATE / 1000 -> 800L * SAMPLE_RATE / 1000
        voicedSamples < 6_000L * SAMPLE_RATE / 1000 -> 1_200L * SAMPLE_RATE / 1000
        else -> 1_500L * SAMPLE_RATE / 1000
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
