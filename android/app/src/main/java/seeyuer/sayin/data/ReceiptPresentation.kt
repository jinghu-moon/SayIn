package seeyuer.sayin.data

import seeyuer.sayin.R

internal enum class ReceiptInputType { TEXT, IMAGE, AUTO_TEXT, UNKNOWN }

internal data class ReceiptPresentation(
    val titleRes: Int,
    val helpRes: Int?,
    val scopeRes: Int?,
    val inputType: ReceiptInputType,
    val clearDraft: Boolean,
)

private fun OutboxItem.receiptInputType(): ReceiptInputType = when {
    kind == "image" -> ReceiptInputType.IMAGE
    payload == null && transcript == null -> ReceiptInputType.UNKNOWN
    application != null || method == "auto_text" -> ReceiptInputType.AUTO_TEXT
    kind == "text" -> ReceiptInputType.TEXT
    else -> ReceiptInputType.UNKNOWN
}

/** Maps protocol state and execution metadata to local resources only. */
internal fun receiptPresentation(item: OutboxItem): ReceiptPresentation {
    val type = item.receiptInputType()
    val help = when {
        item.state == "failed" && item.executionStage == "file_saved" -> R.string.receipt_file_saved_help
        item.state in setOf("injected", "failed", "uncertain", "uncertain_acknowledged") && item.executionStage == null ->
            R.string.receipt_execution_unrecorded
        item.state == "accepted" -> R.string.receipt_accepted_help
        item.state == "held" -> R.string.receipt_held_help
        item.state == "expired" -> R.string.receipt_expired_help
        else -> null
    }
    val scope = when (item.uncertainScope) {
        "target" -> R.string.receipt_uncertain_target
        "global" -> R.string.receipt_uncertain_global
        else -> null
    }
    return ReceiptPresentation(receiptTitle(item.state, item.executionStage), help, scope, type, item.state == "injected")
}

internal fun receiptTitle(state: String, executionStage: String? = null): Int = when (state) {
    "accepted" -> R.string.receipt_accepted
    "held" -> R.string.receipt_held
    "executing" -> R.string.receipt_executing
    "injected" -> R.string.receipt_injected
    "cancelled" -> R.string.receipt_cancelled
    "expired" -> R.string.receipt_expired
    "failed" -> if (executionStage == "file_saved") R.string.receipt_file_saved else R.string.receipt_failed
    "uncertain" -> R.string.receipt_uncertain
    "uncertain_acknowledged" -> R.string.receipt_uncertain_acknowledged
    "review" -> R.string.receipt_review
    else -> R.string.receipt_saved
}
