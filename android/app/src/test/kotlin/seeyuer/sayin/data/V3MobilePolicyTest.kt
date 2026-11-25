package seeyuer.sayin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.sayin.R

class V3MobilePolicyTest {
    @Test fun reconnectScheduleIsBoundedAndDeterministic() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(5_000L, reconnectDelayMs(2))
        assertEquals(10_000L, reconnectDelayMs(3))
        assertEquals(10_000L, reconnectDelayMs(99))
        assertEquals(1_000L, reconnectDelayMs(-1))
    }

    @Test fun receiptMatrixCoversTenStatesAndFourInputKinds() {
        val states = listOf("saved", "accepted", "held", "executing", "injected", "cancelled", "expired", "failed", "uncertain", "uncertain_acknowledged")
        val items = listOf(
            OutboxItem("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003", "target", byteArrayOf(1), 1, "saved", null, null, 1L),
            OutboxItem("00000000-0000-4000-8000-000000000004", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003", "target", byteArrayOf(1), 1, "saved", null, null, 1L, application = byteArrayOf(1)),
            OutboxItem("00000000-0000-4000-8000-000000000005", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003", "target", byteArrayOf(1), 0, "saved", null, null, 1L, kind = "image"),
            OutboxItem("00000000-0000-4000-8000-000000000006", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003", "target", null, 0, "saved", null, null, 1L),
        )
        states.forEach { state ->
            items.forEach { item ->
                val result = receiptPresentation(item.copy(state = state))
                assertNotNull(result)
                assertTrue(result.titleRes != 0)
                assertEquals(state == "injected", result.clearDraft)
            }
        }
    }

    @Test fun uncertainScopeAndExecutionMetadataUseSpecificResources() {
        val base = OutboxItem("00000000-0000-4000-8000-000000000007", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003", "target", byteArrayOf(1), 1, "uncertain", null, null, 1L)
        assertEquals(R.string.receipt_uncertain_target, receiptPresentation(base.copy(uncertainScope = "target")).scopeRes)
        assertEquals(R.string.receipt_uncertain_global, receiptPresentation(base.copy(uncertainScope = "global")).scopeRes)
        assertEquals(R.string.receipt_file_saved, receiptPresentation(base.copy(state = "failed", executionStage = "file_saved")).titleRes)
        assertEquals(R.string.receipt_execution_unrecorded, receiptPresentation(base.copy(state = "failed", executionStage = null)).helpRes)
        assertNull(receiptPresentation(base.copy(uncertainScope = null)).scopeRes)
    }
}
