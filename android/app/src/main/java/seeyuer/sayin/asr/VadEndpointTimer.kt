package seeyuer.sayin.asr

internal object LocalSilence {
    const val DefaultMillis = 2_500
    val options = listOf(2_000, DefaultMillis, 3_000)
}

/** Endpoint and segment timing in PCM samples, independent of decoder output. */
internal class VadEndpointTimer(
    private val autoFinish: Boolean = true,
    private val minSpeechSamples: Long = 150L * SAMPLE_RATE / 1000,
    private val noSpeechSamples: Long = 10L * SAMPLE_RATE,
    localSilenceMillis: Int? = null,
) {
    data class State(
        val segmentBoundary: Boolean = false,
        val ended: Boolean = false,
        val noSpeech: Boolean = false,
        val speech: Boolean = false,
    )

    private var totalSamples = 0L
    private var initialVoiceSamples = 0L
    private var voicedSamples = 0L
    private var quietSamples = 0L
    private var heardSpeech = false
    private var terminal: State? = null
    private val localSilenceSamples = localSilenceMillis?.let { millis ->
        require(millis in LocalSilence.options)
        millis.toLong() * SAMPLE_RATE / 1000
    }
    private var lastConfirmedVoice = 0L
    private var boundaryReported = false
    private var recentVoice = 0
    var lastPossibleVoiceSample = 0L
        private set

    fun accept(probability: Float, threshold: Float, samples: Int): State {
        terminal?.let { return it }
        totalSamples += samples
        localSilenceSamples?.let { return acceptLocal(probability >= threshold, samples, it) }
        if (probability >= threshold) {
            quietSamples = 0
            initialVoiceSamples += samples
            voicedSamples += samples
            if (initialVoiceSamples >= minSpeechSamples) heardSpeech = true
            return State(speech = true)
        }
        initialVoiceSamples = 0
        if (heardSpeech) {
            val previousQuiet = quietSamples
            quietSamples += samples
            val boundary = previousQuiet < SEGMENT_SILENCE && quietSamples >= SEGMENT_SILENCE
            if (autoFinish && quietSamples >= finalizeSamples()) {
                return State(segmentBoundary = boundary, ended = true).also { terminal = it.copy(segmentBoundary = false) }
            }
            return State(segmentBoundary = boundary)
        }
        if (autoFinish && totalSamples >= noSpeechSamples) return State(noSpeech = true).also { terminal = it }
        return State()
    }

    private fun acceptLocal(speech: Boolean, samples: Int, stopAfter: Long): State {
        // Brief gaps between syllables must not erase the evidence of a short or quiet utterance.
        recentVoice = ((recentVoice shl 1) or if (speech) 1 else 0) and RECENT_VOICE_MASK
        if (speech) {
            lastPossibleVoiceSample = totalSamples
            val continued = heardSpeech && totalSamples - samples == lastConfirmedVoice
            val confirmed = if (heardSpeech) Integer.bitCount(recentVoice and 0b1111) >= 3
                else Integer.bitCount(recentVoice) >= 4
            if (continued || confirmed) {
                heardSpeech = true
                lastConfirmedVoice = totalSamples
                boundaryReported = false
                return State(speech = true)
            }
            // Give a candidate which begins before a deadline time to confirm.
            val deadline = if (heardSpeech) lastConfirmedVoice + stopAfter else noSpeechSamples
            val consecutive = Integer.numberOfTrailingZeros(recentVoice.inv())
            if (consecutive * samples < 160L * SAMPLE_RATE / 1000 &&
                totalSamples - consecutive * samples < deadline
            ) return State(speech = true)
        }
        if (!heardSpeech) {
            return if (autoFinish && totalSamples >= noSpeechSamples) State(noSpeech = true).also { terminal = it }
            else State(speech = speech)
        }
        val quiet = totalSamples - lastConfirmedVoice
        val boundary = !boundaryReported && quiet >= SEGMENT_SILENCE
        if (boundary) boundaryReported = true
        val state = State(segmentBoundary = boundary, ended = autoFinish && quiet >= stopAfter, speech = speech)
        if (state.ended) terminal = state.copy(segmentBoundary = false)
        return state
    }

    private fun finalizeSamples(): Long = when {
        voicedSamples < 1_500L * SAMPLE_RATE / 1000 -> 800L * SAMPLE_RATE / 1000
        voicedSamples < 6_000L * SAMPLE_RATE / 1000 -> 1_200L * SAMPLE_RATE / 1000
        else -> 1_500L * SAMPLE_RATE / 1000
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SEGMENT_SILENCE = 600L * SAMPLE_RATE / 1000
        const val RECENT_VOICE_MASK = 0b11111
    }
}
