package seeyuer.sayin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.sayin.R

class V3ErrorCodeTest {
    @Test
    fun allCodesHaveLocalizedPromptsAndCorrectOrigins() {
        val expected = linkedMapOf(
            "UNAUTHORIZED" to R.string.v3_error_unauthorized,
            "STALE_SESSION" to R.string.v3_error_stale_session,
            "PAIRING_BUSY" to R.string.v3_error_pairing_busy,
            "PAIR_ALREADY_EXISTS" to R.string.v3_error_pair_already_exists,
            "PAIR_REPLACE_BLOCKED" to R.string.v3_error_pair_replace_blocked,
            "TARGET_MISMATCH" to R.string.v3_error_target_mismatch,
            "STATE_CONFLICT" to R.string.v3_error_state_conflict,
            "ID_CONFLICT" to R.string.v3_error_id_conflict,
            "QUEUE_FULL" to R.string.v3_error_queue_full,
            "IMAGE_UPLOAD_LIMIT" to R.string.image_upload_limit,
            "RATE_LIMITED" to R.string.v3_error_rate_limited,
            "STORAGE_UNAVAILABLE" to R.string.v3_error_storage_unavailable,
            "GLOBAL_PAUSED" to R.string.v3_error_global_paused,
            "BLOCKED_REVIEW" to R.string.v3_error_blocked_review,
            "TOO_LATE" to R.string.v3_error_too_late,
            "INVALID_REQUEST" to R.string.v3_error_invalid_request,
            "UNSUPPORTED" to R.string.v3_error_unsupported,
            "VERSION_MISMATCH" to R.string.v3_error_version_mismatch,
        )
        assertEquals(expected, V3ErrorCode.entries.associate { it.name to it.messageRes })
        for ((name, message) in expected) {
            assertEquals(
                if (name == "PAIR_ALREADY_EXISTS") null else message,
                V3ErrorCode.fromWire(name)?.messageRes,
            )
        }
    }

    @Test
    fun unlistedOrNoncanonicalCodesAreRejected() {
        for (name in listOf("PAIR_RETIRED", "LEGACY_RECEIPT_QUERY_ONLY", "unauthorized", "UNAUTHORIZED ")) {
            assertNull(V3ErrorCode.fromWire(name))
        }
    }
}
