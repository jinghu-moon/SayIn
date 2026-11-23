package seeyuer.sayin.ui

/** Single policy for whether VAD silence may finish the current recording. */
internal object RecordingEndpointPolicy {
    fun finishOnSilence(holdToSpeak: Boolean, continuousDictation: Boolean): Boolean =
        !holdToSpeak && !continuousDictation
}
