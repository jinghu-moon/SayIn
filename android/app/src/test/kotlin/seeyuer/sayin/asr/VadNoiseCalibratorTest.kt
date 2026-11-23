package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VadNoiseCalibratorTest {
    private val frame = 512

    @Test
    fun quietRoomSelectsLowerThreshold() {
        val calibrator = VadNoiseCalibrator()
        var value: Float? = null
        repeat(20) { value = calibrator.observe(0.05f, frame) }
        assertEquals(0.35f, value ?: calibrator.value, 0.001f)
    }

    @Test
    fun noisyRoomRaisesThreshold() {
        val calibrator = VadNoiseCalibrator()
        var value: Float? = null
        repeat(20) { value = calibrator.observe(0.40f, frame) }
        assertEquals(0.58f, value ?: calibrator.value, 0.001f)
    }

    @Test
    fun immediateSpeechKeepsDefaultThreshold() {
        val calibrator = VadNoiseCalibrator()
        var value: Float? = null
        repeat(20) { value = calibrator.observe(0.90f, frame) }
        assertEquals(0.45f, value ?: calibrator.value, 0.001f)
    }

    @Test
    fun thresholdIsReportedOnlyOnce() {
        val calibrator = VadNoiseCalibrator()
        var first: Float? = null
        while (first == null) first = calibrator.observe(0.05f, frame)
        assertEquals(0.35f, first, 0.001f)
        assertNull(calibrator.observe(0.90f, frame))
    }
}
