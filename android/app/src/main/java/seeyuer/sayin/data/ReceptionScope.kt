package seeyuer.sayin.data

/** Captured before enqueue; an operation cannot move to a later reception scope. */
internal data class ReceptionScope(val address: String, val target: String, val revision: String?, val connectionEpoch: Long = 0, val port: Int = 7788)

internal val ClientState.receptionScope: ReceptionScope?
    get() = if (ready) ReceptionScope(address, checkNotNull(target), revision, connectionEpoch, port) else null
