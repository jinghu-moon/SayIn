package seeyuer.sayin.ui

import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection

/** Transport connectivity never grants permission to resume desktop input. */
internal enum class ConnectionPresentation {
    Loading, Storage, First, Pairing, Approval, Connecting, Offline, Recovering, Review, Inactive, Unavailable, Paused, Ready,
}

internal val ClientState.connectionPresentation: ConnectionPresentation
    get() = when {
        !storageHealthy -> ConnectionPresentation.Storage
        connection == Connection.LOADING -> ConnectionPresentation.Loading
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
