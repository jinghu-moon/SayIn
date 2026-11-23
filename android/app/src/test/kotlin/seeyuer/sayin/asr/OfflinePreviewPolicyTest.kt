package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePreviewPolicyTest {
    @Test
    fun qwen06UsesFastCadence() {
        val policy = OfflinePreviewPolicies.forModel("qwen-06")
        assertEquals(2_000L, policy?.intervalMs)
        assertEquals(20 * 16_000, policy?.maxSamples)
        assertEquals(0.20, policy?.rtfBudget ?: 0.0, 0.001)
    }

    @Test
    fun heavyModelsUseLongerCadence() {
        assertEquals(3_000L, OfflinePreviewPolicies.forModel("qwen-17")?.intervalMs)
        assertEquals(3_000L, OfflinePreviewPolicies.forModel("fun-int8")?.intervalMs)
        assertEquals(0.15, OfflinePreviewPolicies.forModel("qwen-17")?.rtfBudget ?: 0.0, 0.001)
    }

    @Test
    fun streamingOrUnknownModelsDoNotPreview() {
        assertNull(OfflinePreviewPolicies.forModel("x-asr"))
        assertNull(OfflinePreviewPolicies.forModel("r2t2"))
    }
}
