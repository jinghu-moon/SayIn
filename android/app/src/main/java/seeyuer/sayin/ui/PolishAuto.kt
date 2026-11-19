package seeyuer.sayin.ui

/** P2 auto polish is allowed only for a brand-new speech segment, never a mixed/续写 draft. */
internal fun shouldAutoPolish(recordingStartedEmpty: Boolean, typedSinceVoice: Boolean, bound: Boolean): Boolean =
    recordingStartedEmpty && !typedSinceVoice && bound
