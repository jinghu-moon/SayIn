package seeyuer.sayin.data

import android.content.Context
import android.os.SystemClock
import android.net.Network
import kotlinx.coroutines.withTimeoutOrNull
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import seeyuer.sayin.pairing.NativePairing
import java.util.UUID

internal enum class Connection { LOADING, OFFLINE, CONNECTING, PAIRING, APPROVAL, ONLINE }
internal enum class ClientIssue { INVALID_PAIRING, CONNECTION, STORAGE, INVALID_TEXT, QUOTA, NOT_READY, PROTOCOL, TLS_REQUIRED, LOCAL_NETWORK_PERMISSION, DISCOVERY, CANDIDATE_CONNECTION }
internal data class ClientState(
    val connection: Connection = Connection.LOADING,
    val paired: Boolean = false,
    val address: String = "",
    val port: Int = 7788,
    val discovering: Boolean = false,
    val connectionEpoch: Long = 0,
    val paused: Boolean = true,
    val pauseReason: String? = null,
    val target: String? = null,
    val active: Boolean = false,
    val revision: String? = null,
    val issue: ClientIssue? = null,
    val storageHealthy: Boolean = true,
    val recovering: Boolean = false,
    val savedDraft: String? = null,
    val rejectedDraft: String? = null,
    val history: List<OutboxItem> = emptyList(),
    val historyText: Map<String, String> = emptyMap(),
) {
    val ready: Boolean get() = storageHealthy && !recovering && connection == Connection.ONLINE && active && !paused && target != null
    val busy: Boolean get() = connection in setOf(Connection.LOADING, Connection.CONNECTING, Connection.PAIRING, Connection.APPROVAL)
}

/** Single bounded mailbox owns socket generation, protocol state and DB writes. */
internal class VoiceLinkRepository(context: Context, private val discovery: HostDiscovery = NsdHostDiscovery(context.applicationContext),
    private val localPermission: () -> Boolean = { context.applicationContext.localNetworkPermitted() },
) {
    private val app = context.applicationContext
    private val networkMonitor = LanNetwork(app) { enqueue(Event.NetworkChanged) }
    private var network: Network? = null
    private var networkKey = ""
    private var permissionJob: Job? = null
    private var discoveryJob: Job? = null
    private var discoveredRound = false
    private val candidates = ArrayDeque<HostCandidate>()
    private var connectingHost: PairedHost? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val events = Channel<Event>(64)
    private val mutable = MutableStateFlow(ClientState())
    val state = mutable.asStateFlow()
    private lateinit var store: ProtectedStore
    private val database = Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "outbox.db")
        .addMigrations(OutboxDatabase.KEEP_LEDGER)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        }).build()
    private val dao = database.outbox()
    private var host: PairedHost? = null
    private var invitation: PairingInvitation? = null
    private var client: HostClient? = null
    @Volatile private var socket: WebSocket? = null
    private var generation = 0L
    private var session: String? = null
    private var foreground = false
    private var heartbeat: Job? = null
    private var deadline: Job? = null
    private var lastInbound = 0L
    private val queries = mutableMapOf<String, Set<String>>()
    private var lastDraftId: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    init {
        scope.launch {
            try {
                // Open the ledger before connecting. Corruption/key loss fails closed.
                dao.pendingCount()
                store = ProtectedStore(context.applicationContext)
                host = store.load()?.let(::PairedHost)
                mutable.update { it.copy(connection = Connection.OFFLINE, paired = host != null, address = host?.address.orEmpty(), port = host?.port ?: 7788) }
                scope.launch {
                    try { dao.observe().collect { history ->
                        val text = history.mapNotNull { item -> item.transcript?.let { encrypted ->
                            runCatching { item.id to store.decrypt(encrypted).toString(Charsets.UTF_8) }.getOrNull()
                        } }.toMap()
                        mutable.update { it.copy(history = history, historyText = text) }
                    } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { enqueue(Event.StorageFailure) }
                }
            } catch (_: Exception) { storageFailed() }
            for (event in events) {
                try { handle(event) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    when (event) {
                        is Event.PairQr, is Event.PairCode -> fail(ClientIssue.INVALID_PAIRING)
                        else -> fail(ClientIssue.PROTOCOL)
                    }
                }
            }
        }
        job.invokeOnCompletion { networkMonitor.stop(); database.close() }
    }

    private sealed interface Event {
        data class Foreground(val active: Boolean) : Event
        data object Connect : Event
        data class Retry(val generation: Long) : Event
        data object NetworkChanged : Event
        data class Discovered(val generation: Long, val candidates: List<HostCandidate>, val denied: Boolean) : Event
        data class UpdateAddress(val address: String, val port: String) : Event
        data class PairQr(val value: String) : Event
        data class PairCode(val address: String, val port: String, val code: String) : Event
        data class Open(val generation: Long, val socket: WebSocket) : Event
        data class Message(val generation: Long, val value: String) : Event
        data class Closed(val generation: Long) : Event
        data class Tick(val generation: Long) : Event
        data class Timeout(val generation: Long) : Event
        data class Send(val text: String, val draftId: String, val expectedScope: ReceptionScope?) : Event
        data class Action(val id: String, val action: String) : Event
        data object CancelPairing : Event
        data object Forget : Event
        data object StorageFailure : Event
    }

    private fun enqueue(event: Event) {
        if (!events.trySend(event).isSuccess) {
            socket?.cancel() // Overflow never drops an acknowledgement silently.
            mutable.update { it.copy(connection = Connection.OFFLINE, issue = ClientIssue.CONNECTION) }
        }
    }
    fun foreground(active: Boolean) = enqueue(Event.Foreground(active))
    fun connect() = enqueue(Event.Connect)
    fun pairQr(value: String) = enqueue(Event.PairQr(value))
    fun pairCode(address: String, port: String, code: String) = enqueue(Event.PairCode(address, port, code))
    fun send(text: String, draftId: String, expectedScope: ReceptionScope? = mutable.value.receptionScope) =
        enqueue(Event.Send(text, draftId, expectedScope))
    fun action(id: String, action: String) = enqueue(Event.Action(id, action))
    fun updateAddress(address: String, port: String) = enqueue(Event.UpdateAddress(address, port))
    fun cancelPairing() = enqueue(Event.CancelPairing)
    fun forget() = enqueue(Event.Forget)
    fun close() { permissionJob?.cancel(); networkMonitor.stop(); discoveryJob?.cancel(); socket?.cancel(); events.close(); scope.cancel(); client?.close() }

    private suspend fun handle(event: Event) {
        if (!mutable.value.storageHealthy) return
        when (event) {
            is Event.Foreground -> {
                val wasForeground = foreground
                foreground = event.active
                if (!foreground) { permissionJob?.cancel(); permissionJob = null; networkMonitor.stop(); candidates.clear(); disconnect() }
                else if (!localPermission()) { watchPermission(); fail(ClientIssue.LOCAL_NETWORK_PERMISSION) }
                else {
                    watchPermission()
                    networkMonitor.start()
                    if (!wasForeground) { network = networkMonitor.current(); networkKey = networkMonitor.key(network) }
                    if (host != null && session == null && client == null && discoveryJob == null) connectHost()
                }
            }
            Event.Connect -> if (foreground && host != null && client == null) { reconnectAttempt = 0; connectHost() }
            is Event.Retry -> if (event.generation == generation && foreground && host != null && client == null) connectHost()
            Event.NetworkChanged -> if (foreground) {
                if (!localPermission()) {
                    networkMonitor.stop(); fail(ClientIssue.LOCAL_NETWORK_PERMISSION); return
                }
                val current = networkMonitor.current()
                val key = networkMonitor.key(current)
                if (key != networkKey || mutable.value.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) {
                    networkMonitor.start()
                    network = current; networkKey = key
                    if (host != null) connectHost()
                }
            }
            is Event.Discovered -> if (event.generation == generation && foreground) {
                discoveryJob = null
                mutable.update { it.copy(discovering = false) }
                if (event.denied || !localPermission()) fail(ClientIssue.LOCAL_NETWORK_PERMISSION)
                else {
                    candidates.addAll(event.candidates.distinct().take(4).filter { it.address != host?.address || it.port != host?.port })
                    if (!tryCandidate()) scheduleRetry(ClientIssue.DISCOVERY)
                }
            }
            is Event.UpdateAddress -> {
                val saved = host ?: return
                val updated = try { saved.withAddress(event.address, event.port) }
                    catch (_: Exception) { mutable.update { it.copy(issue = ClientIssue.INVALID_PAIRING) }; return }
                // Change routing only. Never replace host/device identity, token or pinned certificate.
                try { store.save(updated.json) } catch (_: Exception) { storageFailed(); return }
                disconnect()
                host = updated
                reconnectAttempt = 0
                mutable.update { it.copy(address = updated.address, port = updated.port, issue = null) }
                if (foreground) connectHost()
            }
            is Event.PairQr -> {
                check(host == null)
                beginPairing(PairingInvitation.fromQr(event.value))
            }
            is Event.PairCode -> {
                check(host == null)
                if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION); return }
                numericAddress(event.address)
                val port = event.port.toInt().also { require(it in 1..65535) }
                require(event.code.length == 8 && event.code.all { it in '0'..'9' })
                disconnect()
                mutable.update { it.copy(connection = Connection.PAIRING, issue = null) }
                val result = strictJson(NativePairing.pairCode(event.address, port, event.code))
                require(result.getBoolean("ok"))
                val received = PairingInvitation(result.getJSONObject("invitation"))
                require(received.address == event.address && received.port == port)
                beginPairing(received)
            }
            is Event.Open -> if (event.generation == generation) {
                if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION); return }
                socket = event.socket
                val name = DeviceDisplayName.current()
                val pending = invitation
                if (pending != null) {
                    sendFrame(JSONObject().put("v", 2).put("t", "pair").put("name", name)
                        .put("window_id", pending.windowId).put("secret", pending.secret))
                    mutable.update { it.copy(connection = Connection.APPROVAL) }
                } else {
                    val saved = checkNotNull(host)
                    sendFrame(JSONObject().put("v", 2).put("t", "hello").put("device_id", saved.deviceId).put("token", saved.token).put("name", name))
                }
            }
            is Event.Message -> if (event.generation == generation) receive(strictJson(event.value))
            is Event.Closed -> if (event.generation == generation) fail(ClientIssue.CONNECTION)
            is Event.Timeout -> if (event.generation == generation) fail(ClientIssue.CONNECTION)
            is Event.Tick -> if (event.generation == generation && session != null) {
                if (SystemClock.elapsedRealtime() - lastInbound > 25000) fail(ClientIssue.CONNECTION)
                else sendFrame(base("ping").put("nonce", UUID.randomUUID().toString()))
            }
            is Event.Send -> saveAndSend(event)
            is Event.Action -> historyAction(event.id, event.action)
            Event.CancelPairing -> disconnect()
            Event.Forget -> {
                // Outstanding rows remain host-scoped. Forgetting cannot reassign them.
                disconnect()
                try { store.forget() } catch (_: Exception) { storageFailed(); return }
                host = null
                mutable.update { it.copy(paired = false, address = "", issue = null) }
            }
            Event.StorageFailure -> storageFailed()
        }
    }

    private fun watchPermission() {
        if (permissionJob != null) return
        val initial = localPermission()
        permissionJob = scope.launch {
            var permitted = initial
            while (true) {
                delay(1000)
                val current = localPermission()
                if (current != permitted) { permitted = current; enqueue(Event.NetworkChanged) }
            }
        }
    }
    private fun beginPairing(value: PairingInvitation) {
        disconnect()
        invitation = value
        open(value, pairing = true)
    }
    private fun connectHost() {
        candidates.clear(); discoveredRound = false
        disconnect(); open(checkNotNull(host), pairing = false)
    }
    private fun discoverHost() {
        val saved = host ?: return
        discoveredRound = true
        if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION); return }
        val owner = generation
        val selectedNetwork = network
        mutable.update { it.copy(discovering = true, issue = null) }
        discoveryJob = scope.launch {
            try {
                val result = withTimeoutOrNull(12000) { discovery.discover(saved.hostId, selectedNetwork) }.orEmpty()
                enqueue(Event.Discovered(owner, result, false))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: SecurityException) { enqueue(Event.Discovered(owner, emptyList(), true)) }
            catch (_: Exception) { enqueue(Event.Discovered(owner, emptyList(), false)) }
        }
    }
    private fun tryCandidate(): Boolean {
        val saved = host ?: return false
        while (candidates.isNotEmpty()) {
            val route = candidates.removeFirst()
            val candidate = runCatching { saved.withAddress(route.address, route.port.toString()) }.getOrNull() ?: continue
            open(candidate, pairing = false)
            return true
        }
        return false
    }
    private fun open(endpoint: HostEndpoint, pairing: Boolean) {
        if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION); return }
        val owner = generation
        connectingHost = endpoint as? PairedHost
        try {
            client = HostClient(endpoint, network?.takeUnless { numericAddress(endpoint.address).isLoopbackAddress })
            mutable.update { it.copy(connection = if (pairing) Connection.PAIRING else Connection.CONNECTING, issue = null, address = if (pairing) endpoint.address else host?.address.orEmpty(), port = if (pairing) endpoint.port else host?.port ?: 7788) }
            socket = client!!.open(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { enqueue(Event.Open(owner, webSocket)) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 65536) webSocket.cancel() else enqueue(Event.Message(owner, text))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); enqueue(Event.Closed(owner)) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { enqueue(Event.Closed(owner)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { enqueue(Event.Closed(owner)) }
            })
            deadline = scope.launch { delay(if (pairing) 125000 else if (discoveredRound) 6000 else 12000); enqueue(Event.Timeout(owner)) }
        } catch (failure: IllegalStateException) {
            fail(if (failure.message == "TLS13_REQUIRED") ClientIssue.TLS_REQUIRED else ClientIssue.CONNECTION)
        }
    }
    private fun disconnect() {
        generation++
        discoveryJob?.cancel(); discoveryJob = null
        connectingHost = null
        session = null
        queries.clear()
        invitation = null
        heartbeat?.cancel(); heartbeat = null
        deadline?.cancel(); deadline = null
        reconnectJob?.cancel(); reconnectJob = null
        socket?.cancel(); socket = null
        client?.close(); client = null
        mutable.update { it.copy(connection = Connection.OFFLINE, discovering = false, connectionEpoch = generation, paused = true, active = false, target = null, revision = null, recovering = false) }
    }
    private fun fail(issue: ClientIssue) {
        val candidateFailed = discoveredRound && connectingHost != null && session == null
        disconnect(); mutable.update { it.copy(issue = issue) }
        if (!localPermission()) {
            mutable.update { it.copy(issue = ClientIssue.LOCAL_NETWORK_PERMISSION) }; return
        }
        if (foreground && host != null && mutable.value.storageHealthy &&
            (issue == ClientIssue.CONNECTION || candidateFailed && issue == ClientIssue.PROTOCOL)) {
            if (!discoveredRound) discoverHost()
            else if (!tryCandidate()) scheduleRetry(if (candidateFailed) ClientIssue.CANDIDATE_CONNECTION else ClientIssue.DISCOVERY)
        }
    }
    private fun scheduleRetry(issue: ClientIssue) {
        mutable.update { it.copy(issue = issue) }
        val owner = generation
        val wait = listOf(1000L, 2000L, 5000L, 10000L)[reconnectAttempt.coerceAtMost(3)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(3)
        reconnectJob = scope.launch { delay(wait); enqueue(Event.Retry(owner)) }
    }
    private fun storageFailed() {
        permissionJob?.cancel(); permissionJob = null
        networkMonitor.stop(); candidates.clear()
        disconnect(); mutable.update { it.copy(storageHealthy = false, issue = ClientIssue.STORAGE) }
    }
    private fun base(kind: String) = JSONObject().put("v", 2).put("t", kind).put("session_id", checkNotNull(session))
    private fun sendFrame(value: JSONObject) {
        val current = checkNotNull(socket)
        val text = value.toString()
        check(text.toByteArray().size <= 32768 && current.queueSize() + text.toByteArray().size <= 65536)
        check(current.send(text))
    }
    private suspend fun receive(value: JSONObject) {
        require(value.getInt("v") == 2)
        lastInbound = SystemClock.elapsedRealtime()
        when (val kind = value.getString("t")) {
            "paired" -> {
                val saved = checkNotNull(invitation).paired(value)
                // Only announce success after durable encrypted credential storage.
                try { store.save(saved.json) } catch (_: Exception) { storageFailed(); return }
                host = saved
                mutable.update { it.copy(paired = true) }
                if (foreground) connectHost() else disconnect()
            }
            "welcome" -> {
                require(session == null && invitation == null)
                require(value.getString("host_id") == host?.hostId && value.getString("device_id") == host?.deviceId)
                val sessionId = validId(value.getString("session_id"))
                session = sessionId
                applyState(value) // Validate the entire authenticated welcome before persisting routing.
                // Full pinned TLS + authenticated host/device welcome precede route commit.
                val authenticated = checkNotNull(connectingHost)
                val previous = checkNotNull(host)
                if (authenticated.address != previous.address || authenticated.port != previous.port) {
                    try { store.save(authenticated.json) } catch (_: Exception) { storageFailed(); return }
                    host = authenticated
                }
                candidates.clear()
                reconnectAttempt = 0
                deadline?.cancel(); deadline = null
                val saved = checkNotNull(host)
                val pending = dao.pending(saved.hostId, saved.deviceId)
                mutable.update { it.copy(connection = Connection.ONLINE, address = saved.address, port = saved.port, issue = null, recovering = pending.isNotEmpty()) }
                if (pending.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    queries[id] = pending.map { it.id }.toSet()
                    sendFrame(base("query").put("id", id).put("refs", JSONArray(pending.map { it.id })))
                }
                val owner = generation
                heartbeat = scope.launch { while (true) { delay(8000); enqueue(Event.Tick(owner)) } }
            }
            else -> {
                require(value.getString("session_id") == session && session != null)
                when (kind) {
                    "state", "controlled" -> applyState(value)
                    "status" -> saveStatus(value)
                    "queried" -> {
                        val refs = queries.remove(value.getString("ref")) ?: error("Unexpected query")
                        // Each referenced status must have been durably processed first.
                        require(refs.isEmpty())
                        mutable.update { it.copy(recovering = queries.isNotEmpty()) }
                    }
                    "pong" -> Unit
                    "err" -> {
                        // Keep payload/id intact; query on reconnect before any retry.
                        mutable.update { it.copy(issue = ClientIssue.PROTOCOL) }
                    }
                    else -> error("Unexpected message")
                }
            }
        }
    }
    private fun applyState(value: JSONObject) {
        val revision = value.getString("state_rev").also { require(it.matches(Regex("[0-9]{1,20}"))) }
        val target = if (value.isNull("target")) null else value.getString("target").also { require(it.length in 1..128) }
        val pauseReason = value.optString("pause_reason").takeIf { !value.isNull("pause_reason") && it.isNotEmpty() }
            ?.also { require(it.length <= 128) }
        mutable.update { it.copy(paused = value.getBoolean("paused"), pauseReason = pauseReason, target = target, revision = revision,
            active = value.optString("active_device_id") == host?.deviceId) }
    }
    private suspend fun saveStatus(value: JSONObject) {
        val id = validId(value.getString("id"))
        val saved = checkNotNull(host)
        val item = dao.find(id, saved.hostId, saved.deviceId) ?: return
        val status = value.getString("st")
        require(status in setOf("unknown", "accepted", "held", "executing", "injected", "cancelled", "expired", "failed", "uncertain"))
        val terminal = status in setOf("injected", "cancelled", "expired", "failed", "uncertain")
        val order = if (value.isNull("order")) null else value.getString("order").also { require(it.matches(Regex("[0-9]{1,20}"))) }
        if (status != "unknown") require(order != null)
        // Terminal rows are immutable locally; a stale response cannot resurrect text.
        if (item.payload != null) {
            try { dao.status(id, saved.hostId, saved.deviceId, if (status == "unknown") "review" else status, order,
                value.optString("reason").takeIf { it.isNotEmpty() }, terminal); dao.trimTerminal() }
            catch (_: Exception) { storageFailed(); return }
        }
        queries.replaceAll { _, refs -> refs - id }
    }
    private suspend fun saveAndSend(event: Event.Send) {
        if (event.draftId == lastDraftId) return
        if (event.expectedScope == null || mutable.value.receptionScope != event.expectedScope) {
            mutable.update { it.copy(issue = ClientIssue.NOT_READY, rejectedDraft = event.draftId) }; return
        }
        val normalized = try { normalizedText(event.text) } catch (_: Exception) {
            mutable.update { it.copy(issue = ClientIssue.INVALID_TEXT, rejectedDraft = event.draftId) }; return
        }
        val saved = checkNotNull(host)
        // The editor's saved UUID is the durable operation identity. A process
        // death after enqueue but before clearing the editor cannot allocate a
        // second request for the same restored draft.
        val id = validId(event.draftId)
        if (dao.find(id, saved.hostId, saved.deviceId) != null) {
            lastDraftId = event.draftId
            mutable.update { it.copy(savedDraft = event.draftId, issue = null) }
            return
        }
        val target = checkNotNull(mutable.value.target)
        val operation = JSONObject().put("kind", "text").put("text", normalized).put("src", "manual")
        val payload = try { store.encrypt(operation.toString().toByteArray()) } catch (_: Exception) { storageFailed(); return }
        val transcript = try { store.encrypt(normalized.toByteArray(Charsets.UTF_8)) } catch (_: Exception) { storageFailed(); return }
        val item = OutboxItem(id, saved.hostId, saved.deviceId, target, payload, normalized.toByteArray().size, "saved", null, null, System.currentTimeMillis(), transcript)
        if (dao.pendingCount() >= 16 || dao.pendingBytes() + item.byteCount > 65536) { mutable.update { it.copy(issue = ClientIssue.QUOTA, rejectedDraft = event.draftId) }; return }
        try { dao.enqueue(item) } catch (_: Exception) { storageFailed(); return }
        lastDraftId = event.draftId
        mutable.update { it.copy(savedDraft = event.draftId, issue = null) }
        submit(item)
    }
    private fun submit(item: OutboxItem) {
        check(mutable.value.ready && item.target == mutable.value.target && item.hostId == host?.hostId && item.deviceId == host?.deviceId)
        val operation = try { strictJson(store.decrypt(checkNotNull(item.payload)).toString(Charsets.UTF_8)) }
        catch (_: Exception) { storageFailed(); return }
        sendFrame(base("submit").put("id", item.id).put("target", item.target).put("op", operation))
    }
    private suspend fun historyAction(id: String, action: String) {
        val saved = host ?: return
        val item = dao.find(id, saved.hostId, saved.deviceId) ?: return
        if (action == "query") {
            if (session == null || mutable.value.connection != Connection.ONLINE || queries.size >= 16 || queries.values.any { id in it }) return
            val queryId = UUID.randomUUID().toString()
            queries[queryId] = setOf(id)
            sendFrame(base("query").put("id", queryId).put("refs", JSONArray(listOf(id))))
            return
        }
        if (item.payload == null) return
        when (action) {
            "retry" -> if (item.state == "review" && mutable.value.ready && item.target == mutable.value.target) submit(item)
            "discard" -> if (item.state == "review") dao.status(id, saved.hostId, saved.deviceId, "cancelled", item.order, "local_discard", true)
            "release", "cancel" -> if (session != null && item.state in setOf("accepted", "held")) {
                sendFrame(base("control").put("id", UUID.randomUUID().toString()).put("name", action)
                    .put("refs", JSONArray(listOf(id))).put("target", mutable.value.target).put("expected_rev", mutable.value.revision))
            }
        }
    }
}
