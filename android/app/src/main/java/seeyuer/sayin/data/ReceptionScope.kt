package seeyuer.sayin.data

/** Captured before enqueue; an operation cannot move to a later reception scope. */
internal data class ReceptionScope(val address: String, val target: String, val revision: String?, val connectionEpoch: Long = 0, val port: Int = 7788)

internal val ClientState.receptionScope: ReceptionScope?
    get() = if (ready) ReceptionScope(address, checkNotNull(target), revision, connectionEpoch, port) else null

internal data class AutoSendDestination(val scope: ReceptionScope, val application: String, val observation: Long)

internal val ClientState.autoSendDestination: AutoSendDestination?
    get() {
        if (!autoTextSupported) return null
        val scope = receptionScope ?: return null
        val application = foregroundApplication ?: return null
        return AutoSendDestination(scope, application.name, application.sequence)
    }
