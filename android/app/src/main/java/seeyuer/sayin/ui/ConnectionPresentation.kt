package seeyuer.sayin.ui

import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ClientIssue

/** Transport connectivity never grants permission to resume desktop input. */
internal enum class ConnectionPresentation {
    Loading, Storage, Permission, First, Pairing, Approval, Connecting, Offline, Recovering, Review, Inactive, Unavailable, Paused, Ready,
}

internal val ClientState.connectionPresentation: ConnectionPresentation
    get() = when {
        !storageHealthy -> ConnectionPresentation.Storage
        connection == Connection.LOADING -> ConnectionPresentation.Loading
        issue == ClientIssue.LOCAL_NETWORK_PERMISSION -> ConnectionPresentation.Permission
        connection == Connection.APPROVAL -> ConnectionPresentation.Approval
        connection == Connection.PAIRING -> ConnectionPresentation.Pairing
        connection == Connection.CONNECTING -> ConnectionPresentation.Connecting
        connection != Connection.ONLINE -> if (paired) ConnectionPresentation.Offline else ConnectionPresentation.First
        recovering -> ConnectionPresentation.Recovering
        pauseReason == "outcome_review_required" -> ConnectionPresentation.Review
        !active -> ConnectionPresentation.Inactive
        target == null -> ConnectionPresentation.Unavailable
        paused -> ConnectionPresentation.Paused
        else -> ConnectionPresentation.Ready
    }

/** Retry is an explicit shortcut during backoff, never a second concurrent connection task. */
internal val ClientState.showConnectionRetry: Boolean
    get() = paired && !pairingRecoveryPending && !replacingPair && storageHealthy && connection == Connection.OFFLINE && !discovering &&
        issue != ClientIssue.LOCAL_NETWORK_PERMISSION
