package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePreviewPolicyTest {
    @Test
    fun qwen06UsesFastCadence() {
        val policy = OfflinePreviewPolicies.forModel("qwen-06")
        assertEquals(2 * 16_000, policy?.intervalSamples)
        assertEquals(0.20, policy?.rtfBudget ?: 0.0, 0.001)
        assertEquals(2, policy?.maxBudgetMisses)
    }

    @Test
    fun heavyModelsOnlyDecodeCompletedSegments() {
        assertNull(OfflinePreviewPolicies.forModel("qwen-17"))
        assertNull(OfflinePreviewPolicies.forModel("fun-int8"))
    }

    @Test
    fun streamingOrUnknownModelsDoNotPreview() {
        assertNull(OfflinePreviewPolicies.forModel("x-asr"))
        assertNull(OfflinePreviewPolicies.forModel("r2t2"))
    }
}
