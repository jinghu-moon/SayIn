package seeyuer.sayin.data

import androidx.annotation.StringRes
import seeyuer.sayin.R

internal enum class V3ErrorCode(@StringRes val messageRes: Int) {
    UNAUTHORIZED(R.string.v3_error_unauthorized),
    STALE_SESSION(R.string.v3_error_stale_session),
    PAIRING_BUSY(R.string.v3_error_pairing_busy),
    PAIR_ALREADY_EXISTS(R.string.v3_error_pair_already_exists),
    PAIR_REPLACE_BLOCKED(R.string.v3_error_pair_replace_blocked),
    TARGET_MISMATCH(R.string.v3_error_target_mismatch),
    STATE_CONFLICT(R.string.v3_error_state_conflict),
    ID_CONFLICT(R.string.v3_error_id_conflict),
    QUEUE_FULL(R.string.v3_error_queue_full),
    IMAGE_UPLOAD_LIMIT(R.string.image_upload_limit),
    RATE_LIMITED(R.string.v3_error_rate_limited),
    STORAGE_UNAVAILABLE(R.string.v3_error_storage_unavailable),
    GLOBAL_PAUSED(R.string.v3_error_global_paused),
    BLOCKED_REVIEW(R.string.v3_error_blocked_review),
    TOO_LATE(R.string.v3_error_too_late),
    INVALID_REQUEST(R.string.v3_error_invalid_request),
    UNSUPPORTED(R.string.v3_error_unsupported),
    VERSION_MISMATCH(R.string.v3_error_version_mismatch),
    ;

    companion object {
        private val wireCodes = entries.filter { it != PAIR_ALREADY_EXISTS }.associateBy { it.name }

        fun fromWire(code: String): V3ErrorCode? = wireCodes[code]
    }
}
