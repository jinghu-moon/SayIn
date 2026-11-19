package seeyuer.sayin.asr

/** Counts captured PCM samples so decoder or UI scheduling cannot create silence. */
internal class SpeechPause {
    private var voicedSamples = 0
    private var quietSamples = 0
    private var heardSpeech = false

    fun update(rms: Float, samples: Int): Boolean {
        if (rms >= if (heardSpeech) 0.005f else 0.01f) {
            quietSamples = 0
            if (!heardSpeech) {
                voicedSamples += samples
                heardSpeech = voicedSamples >= 3200 // 200 ms at 16 kHz
            }
        } else {
            voicedSamples = 0
            if (heardSpeech) quietSamples = (quietSamples + samples).coerceAtMost(PAUSE_SAMPLES)
        }
        return quietSamples >= PAUSE_SAMPLES
    }

    private companion object {
        const val PAUSE_SAMPLES = 24000 // 1.5 s at 16 kHz
    }
}
