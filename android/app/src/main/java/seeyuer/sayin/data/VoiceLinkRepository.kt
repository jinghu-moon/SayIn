package seeyuer.sayin.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.net.Network
import kotlinx.coroutines.withTimeoutOrNull
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
internal enum class ClientIssue { INVALID_PAIRING, PAIRING_BUSY, PAIR_ALREADY_EXISTS, PAIR_REPLACE_BLOCKED, CONNECTION, STORAGE, INVALID_TEXT, QUOTA, NOT_READY, PROTOCOL, VERSION_MISMATCH, TLS_REQUIRED, LOCAL_NETWORK_PERMISSION, DISCOVERY, CANDIDATE_CONNECTION }
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
    val foregroundApplication: ForegroundApplication? = null,
    val autoTextSupported: Boolean = false,
    val active: Boolean = false,
    val revision: String? = null,
    val issue: ClientIssue? = null,
    val storageHealthy: Boolean = true,
    val recovering: Boolean = false,
    val savedDraft: String? = null,
    val rejectedDraft: String? = null,
    val rejectedDraftScope: ReceptionScope? = null,
    val history: List<OutboxItem> = emptyList(),
    val historyText: Map<OutboxKey, String> = emptyMap(),
    val historyApplications: Map<OutboxKey, ForegroundApplication> = emptyMap(),
    val historyImages: Map<OutboxKey, ImageMetadata> = emptyMap(),
    val imageDraft: LocalImageDraft? = null,
    val imageBatch: ImageBatchProgress? = null,
    val imageSupported: Boolean = false,
    val imagePreparing: Boolean = false,
    val imageBusy: Boolean = false,
    val imageSubmitted: Boolean = false,
    val imageProgress: Int = 0,
    val imageRequest: OutboxKey? = null,
    val imageIssue: ImageIssue? = null,
    val pairedTargets: List<PairedTarget> = emptyList(),
    val selectedHostId: String? = null,
    val pairingRecoveryPending: Boolean = false,
    val replacingPair: Boolean = false,
) {
    val ready: Boolean get() = storageHealthy && !recovering && !replacingPair && connection == Connection.ONLINE && active && !paused && target != null
    val busy: Boolean get() = connection in setOf(Connection.LOADING, Connection.CONNECTING, Connection.PAIRING, Connection.APPROVAL)
}

/** Single bounded mailbox owns socket generation, protocol state and DB writes. */
internal class VoiceLinkRepository(context: Context, private val discovery: HostDiscovery = NsdHostDiscovery(context.applicationContext),
    private val localPermission: () -> Boolean = { context.applicationContext.localNetworkPermitted() },
    private val draftStore: SpeakingDraftStore = SpeakingDraftStore(context.applicationContext),
) {
    private val app = context.applicationContext
    private val networkMonitor = LanNetwork(app) { enqueue(Event.NetworkChanged) }
    private var network: Network? = null
    private var networkKey = ""
    private var permissionJob: Job? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val events = Channel<Event>(64)
    private val mutable = MutableStateFlow(ClientState())
    val state = mutable.asStateFlow()
    private lateinit var store: ProtectedStore
    private val database = Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "outbox.db")
        .addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY, OutboxDatabase.ADD_V3_RECEIPT_FIELDS,
                OutboxDatabase.SCOPE_REQUEST_KEYS)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        }).build()
    private val dao = database.outbox()
    private class ControlPeer(var generation: Long, var host: PairedHost? = null) {
        var discoveryJob: Job? = null
        var discoveredRound = false
        val candidates = ArrayDeque<HostCandidate>()
        var connectingHost: PairedHost? = null
        @Volatile var client: HostClient? = null
        @Volatile var socket: WebSocket? = null
        var session: String? = null
        var heartbeat: Job? = null
        var deadline: Job? = null
        var reconnectJob: Job? = null
        var lastInbound = 0L
        var welcome: JSONObject? = null
        var receivedState: ReceivedState? = null
        val queries = mutableMapOf<String, Set<String>>()
        val submitting = mutableSetOf<String>()
    }
    private data class ReceivedState(val revision: String, val target: String?, val paused: Boolean, val reason: String?)
    private var generation = 0L
    @Volatile private var currentPeer = ControlPeer(generation)
    @Volatile private var previousPeer: ControlPeer? = null
    private var host: PairedHost?
        get() = currentPeer.host
        set(value) { currentPeer.host = value }
    private val session: String? get() = currentPeer.session
    private fun peerFor(generation: Long): ControlPeer? =
        currentPeer.takeIf { it.generation == generation } ?: previousPeer?.takeIf { it.generation == generation }
    private var invitation: PairingInvitation? = null
    private var replacementPredecessor: PairedHost? = null
    private var pairingCandidate: PairedHost? = null
    private val checkingPairingCandidate: Boolean
        get() = invitation == null && pairingCandidate != null && currentPeer.connectingHost === pairingCandidate
    private var pairingCertificate: java.security.cert.X509Certificate? = null
    private var foreground = false
    private var foregroundHint = ForegroundApplicationHint()
    private var foregroundDisplay: ForegroundApplication? = null
    private var foregroundObservation = 0L
    private var foregroundExpiry: Job? = null
    private var pingNonce: String? = null
    private var pingSentAt = 0L
    private val reconnectPolicy = PairReconnectPolicy()
    private var imageInput: ImageInput? = null

    init {
        scope.launch {
            try {
                // Open the ledger before connecting. Corruption/key loss fails closed.
                dao.pendingCount()
                store = ProtectedStore(context.applicationContext)
                val allHosts = store.loadAll().map(::PairedHost)
                host = store.load()?.let(::PairedHost) ?: allHosts.firstOrNull()
                pairingCandidate = store.loadCandidate()?.let(::PairedHost)
                imageInput = ImageInput(app, store, dao, scope, mutable, { host }, { currentPeer.client }, { session },
                    { event -> events.trySend(Event.Image(event)).isSuccess }, ::submit, { enqueue(Event.StorageFailure) })
                imageInput?.open()
                mutable.update { it.copy(connection = Connection.OFFLINE, paired = host != null,
                    address = host?.address.orEmpty(), port = host?.port ?: 7788,
                    pairedTargets = allHosts.map { it.target() }, selectedHostId = host?.hostId,
                    pairingRecoveryPending = pairingCandidate != null) }
                scope.launch {
                    try { dao.observe().collect { history ->
                        val text = history.mapNotNull { item -> item.transcript?.let { encrypted ->
                            runCatching { item.key to store.decrypt(item.hostId, item.deviceId, encrypted).toString(Charsets.UTF_8) }.getOrNull()
                        } }.toMap()
                        val applications = history.mapNotNull { item -> item.application?.let { encrypted ->
                            runCatching {
                                val bytes = store.decrypt(item.hostId, item.deviceId, encrypted)
                                try { HistoryApplication.restore(bytes, item)?.display()?.let { item.key to it } }
                                finally { bytes.fill(0) }
                            }.getOrNull()
                        } }.toMap()
                        val images = history.mapNotNull { item -> item.imageReference?.let { encrypted ->
                            runCatching {
                                val bytes = store.decrypt(item.hostId, item.deviceId, encrypted)
                                try { ImageReference.parse(strictJson(bytes.toString(Charsets.UTF_8))).metadata.let { item.key to it } }
                                finally { bytes.fill(0) }
                            }.getOrNull()
                        } }.toMap()
                        mutable.update { it.copy(history = history, historyText = text, historyApplications = applications, historyImages = images) }
                    } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { enqueue(Event.StorageFailure) }
                }
            } catch (_: Exception) { storageFailed() }
            for (event in events) {
                try { handle(event) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    when (event) {
                        is Event.PairQr, is Event.PairCode -> fail(ClientIssue.INVALID_PAIRING)
                        is Event.Open -> peerFor(event.generation)?.let { fail(ClientIssue.PROTOCOL, it) }
                        is Event.Message -> peerFor(event.generation)?.let { fail(ClientIssue.PROTOCOL, it) }
                        is Event.Tick -> peerFor(event.generation)?.let { fail(ClientIssue.PROTOCOL, it) }
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
        data class SelectHost(val hostId: String) : Event
        data object PrepareReplacement : Event
        data class Open(val generation: Long, val socket: WebSocket, val response: Response) : Event
        data class Message(val generation: Long, val value: String) : Event
        data class Closed(val generation: Long) : Event
        data class ForegroundExpired(val generation: Long, val observed: Long) : Event
        data class Tick(val generation: Long) : Event
        data class Timeout(val generation: Long) : Event
        data class LoadSpeakingDraft(val reply: CompletableDeferred<SavedSpeakingDraft?>) : Event
        data class SaveSpeakingDraft(val draft: SavedSpeakingDraft) : Event
        data class Send(val draft: SavedSpeakingDraft, val expectedScope: ReceptionScope?, val destination: AutoSendDestination?) : Event
        data class Action(val key: OutboxKey, val action: String) : Event
        data class Control(val name: String) : Event
        data class HideHistory(val keys: List<OutboxKey>) : Event
        data class CleanupHistory(val before: Long) : Event
        data object CancelPairing : Event
        data object Forget : Event
        data class Image(val value: ImageEvent) : Event
        data object StorageFailure : Event
    }

    private fun enqueue(event: Event) {
        if (!events.trySend(event).isSuccess) {
            // A lost mailbox event invalidates both receipt streams.
            currentPeer.socket?.cancel()
            previousPeer?.socket?.cancel()
            mutable.update { it.copy(connection = Connection.OFFLINE, foregroundApplication = null, issue = ClientIssue.CONNECTION) }
        }
    }
    fun foreground(active: Boolean) = enqueue(Event.Foreground(active))
    fun connect() = enqueue(Event.Connect)
    fun pairQr(value: String) = enqueue(Event.PairQr(value))
    fun pairCode(address: String, port: String, code: String) = enqueue(Event.PairCode(address, port, code))
    fun selectHost(hostId: String) = enqueue(Event.SelectHost(hostId))
    fun prepareReplacement() = enqueue(Event.PrepareReplacement)
    suspend fun loadSpeakingDraft(): SavedSpeakingDraft? {
        val reply = CompletableDeferred<SavedSpeakingDraft?>(job)
        events.send(Event.LoadSpeakingDraft(reply))
        return reply.await()
    }
    fun saveSpeakingDraft(draft: SavedSpeakingDraft) = enqueue(Event.SaveSpeakingDraft(draft))
    fun send(draft: SavedSpeakingDraft, expectedScope: ReceptionScope? = mutable.value.receptionScope, destination: AutoSendDestination? = null) =
        enqueue(Event.Send(draft, expectedScope, destination))
    fun selectImages(images: List<Uri>) = enqueue(Event.Image(ImageEvent.Select(images.toList())))
    fun sendImage(resource: String, expectedScope: ReceptionScope? = mutable.value.receptionScope) = enqueue(Event.Image(ImageEvent.Send(resource, expectedScope)))
    fun cancelImage() = enqueue(Event.Image(ImageEvent.Cancel))
    fun clearImage() = enqueue(Event.Image(ImageEvent.Clear))
    fun action(key: OutboxKey, action: String) = enqueue(Event.Action(key, action))
    /** Presentation-only bulk hide. The transaction ledger and pending requests are untouched. */
    fun hideHistory(keys: List<OutboxKey>) = enqueue(Event.HideHistory(keys))
    /** Presentation-only cleanup older than [before]. Receipts and request identity are untouched. */
    fun cleanupHistory(before: Long) = enqueue(Event.CleanupHistory(before))
    fun pauseReception() = enqueue(Event.Control("pause"))
    fun resumeReception() = enqueue(Event.Control("resume"))
    fun updateAddress(address: String, port: String) = enqueue(Event.UpdateAddress(address, port))
    fun cancelPairing() = enqueue(Event.CancelPairing)
    fun forget() = enqueue(Event.Forget)
    fun close() {
        imageInput?.close()
        networkMonitor.stop()
        events.close()
        scope.cancel()
        currentPeer.socket?.cancel()
        currentPeer.client?.close()
        previousPeer?.socket?.cancel()
        previousPeer?.client?.close()
    }

    private suspend fun handle(event: Event) {
        if (!mutable.value.storageHealthy) {
            if (event is Event.Image) imageInput?.abandon(event.value)
            return
        }
        when (event) {
            is Event.Foreground -> {
                val wasForeground = foreground
                foreground = event.active
                if (!foreground) {
                    permissionJob?.cancel(); permissionJob = null
                    networkMonitor.stop()
                    closePreviousPeer()
                    disconnect()
                }
                else if (!localPermission()) { watchPermission(); fail(ClientIssue.LOCAL_NETWORK_PERMISSION) }
                else {
                    // Granting permission restores the existing flow, including the unpaired page.
                    if (mutable.value.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) mutable.update { it.copy(issue = null) }
                    watchPermission()
                    networkMonitor.start()
                    if (!wasForeground) { network = networkMonitor.current(); networkKey = networkMonitor.key(network) }
                    if (session == null && currentPeer.client == null && currentPeer.discoveryJob == null) {
                        if (pairingCandidate != null) recoverPairing()
                        else if (host != null && replacementPredecessor == null) connectHost()
                    }
                }
            }
            Event.Connect -> if (foreground && !mutable.value.busy) {
                if (pairingCandidate != null) recoverPairing()
                else if (currentPeer.client == null) host?.let {
                    reconnectPolicy.reset(it.hostId, it.pairId)
                    connectHost()
                }
            }
            is Event.Retry -> peerFor(event.generation)?.let { peer ->
                if (foreground && peer.host != null && peer.client == null) connectHost(peer)
            }
            Event.NetworkChanged -> if (foreground) {
                if (!localPermission()) {
                    networkMonitor.stop()
                    closePreviousPeer()
                    fail(ClientIssue.LOCAL_NETWORK_PERMISSION)
                    return
                }
                val current = networkMonitor.current()
                val key = networkMonitor.key(current)
                if (key != networkKey || mutable.value.issue == ClientIssue.LOCAL_NETWORK_PERMISSION) {
                    networkMonitor.start()
                    network = current; networkKey = key
                    previousPeer?.let(::connectHost)
                    if (pairingCandidate != null) recoverPairing()
                    else if (host != null && replacementPredecessor == null) connectHost()
                }
            }
            is Event.Discovered -> if (foreground) peerFor(event.generation)?.let { peer ->
                peer.discoveryJob = null
                if (peer === currentPeer) mutable.update { it.copy(discovering = false) }
                if (event.denied || !localPermission()) fail(ClientIssue.LOCAL_NETWORK_PERMISSION, peer)
                else {
                    val saved = checkNotNull(peer.host)
                    peer.candidates.addAll(event.candidates.distinct().take(4).filter { it.address != saved.address || it.port != saved.port })
                    if (!tryCandidate(peer)) scheduleRetry(ClientIssue.DISCOVERY, peer)
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
                reconnectPolicy.reset(updated.hostId, updated.pairId)
                mutable.update { it.copy(address = updated.address, port = updated.port, issue = null,
                    pairedTargets = it.pairedTargets.map { target -> if (target.hostId == updated.hostId) updated.target() else target }) }
                if (foreground) connectHost()
            }
            is Event.PairQr -> {
                if (pairingCandidate != null) { recoverPairing(); return }
                if (dao.pendingCount() != 0) { mutable.update { it.copy(issue = ClientIssue.PAIR_REPLACE_BLOCKED) }; return }
                beginPairing(PairingInvitation.fromQr(event.value))
            }
            is Event.PairCode -> {
                if (pairingCandidate != null) { recoverPairing(); return }
                if (dao.pendingCount() != 0) { mutable.update { it.copy(issue = ClientIssue.PAIR_REPLACE_BLOCKED) }; return }
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
            is Event.SelectHost -> {
                val selected = try { store.loadHost(event.hostId)?.let(::PairedHost) } catch (_: Exception) { storageFailed(); return }
                if (selected == null) {
                    mutable.update { it.copy(issue = ClientIssue.INVALID_PAIRING) }
                    return
                }
                if (host?.hostId == selected.hostId && host?.pairId == selected.pairId) return
                clearReplacement()
                try { store.select(selected.hostId, selected.pairId) } catch (_: Exception) { storageFailed(); return }
                selectPeer(selected)
                imageInput?.hostChanged()
                mutable.update { it.copy(paired = true, address = selected.address, port = selected.port,
                    selectedHostId = selected.hostId, issue = null) }
                if (foreground) {
                    if (session != null) {
                        presentState(checkNotNull(currentPeer.receivedState))
                        currentPeer.welcome?.let { imageInput?.welcome(it) }
                        mutable.update { it.copy(connection = Connection.ONLINE,
                            autoTextSupported = supportsAutoText(checkNotNull(currentPeer.welcome)),
                            recovering = currentPeer.queries.isNotEmpty()) }
                    } else if (currentPeer.client == null && currentPeer.discoveryJob == null) connectHost()
                    else mutable.update { it.copy(connection = Connection.CONNECTING,
                        discovering = currentPeer.discoveryJob != null) }
                }
            }
            Event.PrepareReplacement -> {
                val saved = host ?: return
                if (!foreground || pairingCandidate != null || mutable.value.busy) return
                if (dao.pendingCount() != 0 || dao.hasUnresolvedReceipts(saved.hostId, saved.pairId)) {
                    mutable.update { it.copy(issue = ClientIssue.PAIR_REPLACE_BLOCKED) }
                    return
                }
                disconnect()
                replacementPredecessor = saved
                mutable.update { it.copy(replacingPair = true, issue = null) }
            }
            is Event.Open -> peerFor(event.generation)?.let { peer ->
                if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION, peer); return }
                peer.socket = event.socket
                val name = DeviceDisplayName.current()
                val pending = invitation.takeIf { peer === currentPeer }
                if (pending != null) {
                    val leaf = event.response.handshake?.peerCertificates?.singleOrNull() as? java.security.cert.X509Certificate
                        ?: error("Missing pinned peer certificate")
                    pending.validateCertificate(leaf)
                    pairingCertificate = leaf
                    val request = JSONObject().put("v", 3).put("t", "pair").put("name", name)
                        .put("secret", pending.secret)
                    replacementPredecessor?.let {
                        request.put("replace_pair_id", it.pairId).put("replace_token", it.token)
                    }
                    sendFrame(request)
                } else {
                    val saved = checkNotNull(peer.connectingHost)
                    sendFrame(JSONObject().put("v", 3).put("t", "hello").put("pair_id", saved.pairId).put("token", saved.token).put("name", name), peer)
                }
            }
            is Event.Message -> peerFor(event.generation)?.let { receive(strictJson(event.value), it) }
            is Event.Closed -> peerFor(event.generation)?.let(::pairingOrConnectionLost)
            is Event.Timeout -> peerFor(event.generation)?.let(::pairingOrConnectionLost)
            is Event.ForegroundExpired -> if (event.generation == currentPeer.generation && event.observed == foregroundObservation) {
                mutable.update { it.copy(foregroundApplication = null) }
            }
            is Event.Tick -> peerFor(event.generation)?.let { peer ->
                if (peer.session == null) return
                if (SystemClock.elapsedRealtime() - peer.lastInbound > 25000) fail(ClientIssue.CONNECTION, peer)
                else {
                    val nonce = UUID.randomUUID().toString()
                    if (peer === currentPeer) {
                        pingNonce = nonce
                        pingSentAt = SystemClock.elapsedRealtime()
                    }
                    sendFrame(base("ping", peer).put("nonce", nonce), peer)
                }
            }
            is Event.LoadSpeakingDraft -> try {
                check(mutable.value.storageHealthy)
                event.reply.complete(restoreSpeakingDraft())
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                storageFailed()
                event.reply.completeExceptionally(failure)
            }
            is Event.SaveSpeakingDraft -> if (mutable.value.storageHealthy) {
                try { saveEditableSpeakingDraft(event.draft) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { storageFailed() }
            }
            is Event.Send -> saveAndSend(event)
            is Event.Action -> historyAction(event.key, event.action)
            is Event.Control -> control(event.name)
            is Event.HideHistory -> dao.hideAll(event.keys.distinct().take(100))
            is Event.CleanupHistory -> dao.hideBefore(event.before)
            Event.CancelPairing -> {
                clearReplacement()
                disconnect()
                if (foreground && host != null) connectHost()
            }
            Event.Forget -> {
                // Outstanding rows remain host-scoped. Forgetting cannot reassign them.
                disconnect()
                clearReplacement()
                try { store.forget() } catch (_: Exception) { storageFailed(); return }
                host?.let { reconnectPolicy.reset(it.hostId, it.pairId) }
                host = store.load()?.let(::PairedHost)
                if (previousPeer?.host?.identity() == host?.identity()) closePreviousPeer()
                imageInput?.hostChanged()
                mutable.update { it.copy(paired = host != null, address = host?.address.orEmpty(),
                    port = host?.port ?: 7788, selectedHostId = host?.hostId,
                    pairedTargets = store.loadAll().map { PairedHost(it).target() }, issue = null) }
                if (foreground && host != null) connectHost()
            }
            is Event.Image -> imageInput?.handle(event.value)
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
        val predecessor = replacementPredecessor
        if (predecessor != null) {
            require(value.hostId == predecessor.hostId && value.tlsName == predecessor.tlsName &&
                java.security.MessageDigest.isEqual(value.certificateDigest, predecessor.certificateDigest))
        } else if (mutable.value.pairedTargets.any { it.hostId == value.hostId }) {
            if (mutable.value.connection == Connection.PAIRING) {
                disconnect()
                if (foreground) connectHost()
            }
            mutable.update { it.copy(issue = ClientIssue.PAIR_ALREADY_EXISTS) }
            return
        }
        disconnect()
        invitation = value
        open(value, pairing = true)
    }
    private fun clearReplacement() {
        replacementPredecessor = null
        mutable.update { it.copy(replacingPair = false) }
    }
    private fun PairedHost.identity() = hostId to pairId

    private suspend fun selectPeer(selected: PairedHost) {
        val former = currentPeer
        val saved = former.host
        val previous = previousPeer
        val chosen = previous?.takeIf { it.host?.identity() == selected.identity() }
            ?: ControlPeer(++generation, selected)
        val unresolved = invitation == null && !checkingPairingCandidate && saved != null &&
            (former.queries.isNotEmpty() || dao.hasUnresolvedReceipts(saved.hostId, saved.pairId))
        val keepFormer = hotPairIds(selected.identity(), saved?.identity(), unresolved).size == 2
        if (previous !== chosen) previous?.let(::disconnect)
        if (keepFormer) clearSelectedPeerState() else disconnect(former)
        currentPeer = chosen
        previousPeer = former.takeIf { keepFormer }
        if (foreground && keepFormer && former.client == null && former.discoveryJob == null &&
            former.reconnectJob == null) connectHost(former)
    }

    private fun closePreviousPeer() {
        val previous = previousPeer ?: return
        previousPeer = null
        disconnect(previous)
    }

    private fun connectHost(peer: ControlPeer = currentPeer) {
        peer.candidates.clear()
        peer.discoveredRound = false
        disconnect(peer)
        open(checkNotNull(peer.host), pairing = false, peer)
    }
    private fun recoverPairing() {
        val saved = pairingCandidate ?: return
        disconnect()
        currentPeer.discoveredRound = false
        currentPeer.candidates.clear()
        open(saved, pairing = false)
    }
    private fun pairingOrConnectionLost(peer: ControlPeer = currentPeer) {
        if (peer === currentPeer && foreground && invitation != null && pairingCandidate != null) recoverPairing()
        else fail(ClientIssue.CONNECTION, peer)
    }
    private fun discoverHost(peer: ControlPeer) {
        val saved = peer.host ?: return
        peer.discoveredRound = true
        if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION, peer); return }
        val owner = peer.generation
        val selectedNetwork = network
        if (peer === currentPeer) mutable.update { it.copy(discovering = true, issue = null) }
        peer.discoveryJob = scope.launch {
            try {
                val result = withTimeoutOrNull(12000) { discovery.discover(saved.hostId, selectedNetwork) }.orEmpty()
                enqueue(Event.Discovered(owner, result, false))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: SecurityException) { enqueue(Event.Discovered(owner, emptyList(), true)) }
            catch (_: Exception) { enqueue(Event.Discovered(owner, emptyList(), false)) }
        }
    }
    private fun tryCandidate(peer: ControlPeer): Boolean {
        val saved = peer.host ?: return false
        while (peer.candidates.isNotEmpty()) {
            val route = peer.candidates.removeFirst()
            val candidate = runCatching { saved.withAddress(route.address, route.port.toString()) }.getOrNull() ?: continue
            open(candidate, pairing = false, peer)
            return true
        }
        return false
    }
    private fun open(endpoint: HostEndpoint, pairing: Boolean, peer: ControlPeer = currentPeer) {
        if (!localPermission()) { fail(ClientIssue.LOCAL_NETWORK_PERMISSION, peer); return }
        val owner = peer.generation
        peer.connectingHost = endpoint as? PairedHost
        try {
            val client = HostClient(endpoint, network?.takeUnless { numericAddress(endpoint.address).isLoopbackAddress })
            peer.client = client
            if (peer === currentPeer) mutable.update {
                it.copy(connection = if (pairing) Connection.PAIRING else Connection.CONNECTING, issue = null,
                    address = if (pairing || checkingPairingCandidate) endpoint.address else host?.address.orEmpty(),
                    port = if (pairing || checkingPairingCandidate) endpoint.port else host?.port ?: 7788)
            }
            peer.socket = client.open(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { enqueue(Event.Open(owner, webSocket, response)) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 65536) webSocket.cancel() else enqueue(Event.Message(owner, text))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); enqueue(Event.Closed(owner)) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { enqueue(Event.Closed(owner)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { enqueue(Event.Closed(owner)) }
            })
            peer.deadline = scope.launch {
                delay(if (pairing) 125000 else if (peer.discoveredRound) 6000 else 12000)
                enqueue(Event.Timeout(owner))
            }
        } catch (failure: IllegalStateException) {
            fail(if (failure.message == "TLS13_REQUIRED") ClientIssue.TLS_REQUIRED else ClientIssue.CONNECTION, peer)
        }
    }
    private fun clearSelectedPeerState() {
        imageInput?.invalidate()
        foregroundHint = ForegroundApplicationHint(); foregroundDisplay = null
        pingNonce = null; pingSentAt = 0L
        foregroundExpiry?.cancel(); foregroundExpiry = null
        invitation = null
        pairingCertificate = null
        val epoch = ++generation
        mutable.update { it.copy(connection = Connection.OFFLINE, discovering = false, connectionEpoch = epoch,
            paused = true, pauseReason = null, active = false, target = null, foregroundApplication = null,
            autoTextSupported = false, revision = null, recovering = false) }
    }
    private fun disconnect(peer: ControlPeer = currentPeer) {
        if (peer === currentPeer) clearSelectedPeerState()
        peer.generation = ++generation
        peer.discoveryJob?.cancel(); peer.discoveryJob = null
        peer.connectingHost = null
        peer.session = null
        peer.welcome = null
        peer.receivedState = null
        peer.queries.clear()
        peer.submitting.clear()
        peer.heartbeat?.cancel(); peer.heartbeat = null
        peer.deadline?.cancel(); peer.deadline = null
        peer.reconnectJob?.cancel(); peer.reconnectJob = null
        peer.socket?.cancel(); peer.socket = null
        peer.client?.close(); peer.client = null
    }
    private fun fail(issue: ClientIssue, peer: ControlPeer = currentPeer) {
        val selected = peer === currentPeer
        val candidateFailed = peer.discoveredRound && peer.connectingHost != null && peer.session == null
        disconnect(peer)
        if (selected) mutable.update { it.copy(issue = issue) }
        if (!localPermission()) {
            if (selected) mutable.update { it.copy(issue = ClientIssue.LOCAL_NETWORK_PERMISSION) }
            return
        }
        if (foreground && peer.host != null && mutable.value.storageHealthy &&
            (issue == ClientIssue.CONNECTION || candidateFailed && issue == ClientIssue.PROTOCOL)) {
            if (!peer.discoveredRound) discoverHost(peer)
            else if (!tryCandidate(peer)) scheduleRetry(if (candidateFailed) ClientIssue.CANDIDATE_CONNECTION else ClientIssue.DISCOVERY, peer)
        }
    }
    private fun scheduleRetry(issue: ClientIssue, peer: ControlPeer) {
        val saved = peer.host ?: return
        if (peer === currentPeer) mutable.update { it.copy(issue = issue) }
        val owner = peer.generation
        val wait = reconnectPolicy.nextDelay(saved.hostId, saved.pairId)
        peer.reconnectJob = scope.launch { delay(wait); enqueue(Event.Retry(owner)) }
    }
    private fun storageFailed() {
        permissionJob?.cancel(); permissionJob = null
        networkMonitor.stop()
        closePreviousPeer()
        disconnect(); mutable.update { it.copy(storageHealthy = false, issue = ClientIssue.STORAGE) }
    }
    private fun base(kind: String, peer: ControlPeer = currentPeer) =
        JSONObject().put("v", 3).put("t", kind).put("session_id", checkNotNull(peer.session))
    private fun sendFrame(value: JSONObject, peer: ControlPeer = currentPeer) {
        val current = checkNotNull(peer.socket)
        val text = value.toString()
        check(text.toByteArray().size <= 32768 && current.queueSize() + text.toByteArray().size <= 65536)
        check(current.send(text))
    }
    private suspend fun activatePairingCandidate(saved: PairedHost): Boolean {
        val (activated, targets) = try {
            val identity = PairedHost(store.activateCandidate(saved.hostId, saved.pairId))
            identity to store.loadAll().map { PairedHost(it).target() }
        } catch (_: Exception) {
            storageFailed()
            return false
        }
        pairingCandidate = null
        replacementPredecessor = null
        host = activated
        imageInput?.hostChanged()
        mutable.update { it.copy(paired = true, selectedHostId = saved.hostId,
            pairedTargets = targets, pairingRecoveryPending = false, replacingPair = false) }
        return true
    }
    private suspend fun receive(value: JSONObject, peer: ControlPeer) {
        val selected = peer === currentPeer
        val kind = value.getString("t")
        require(value.getInt("v") == 3)
        peer.lastInbound = SystemClock.elapsedRealtime()
        when (kind) {
            "pair_candidate" -> {
                require(selected && peer.session == null && pairingCandidate == null && !value.has("session_id"))
                val saved = checkNotNull(invitation).candidate(value, checkNotNull(pairingCertificate))
                replacementPredecessor?.let { saved.json.put("replaces_pair_id", it.pairId) }
                try { store.saveCandidate(saved.json) } catch (_: Exception) { storageFailed(); return }
                pairingCandidate = saved
                mutable.update { it.copy(connection = Connection.APPROVAL, pairingRecoveryPending = true) }
                try {
                    sendFrame(JSONObject().put("v", 3).put("t", "pair_saved").put("pair_id", saved.pairId))
                } catch (_: IllegalStateException) {
                    pairingOrConnectionLost()
                }
            }
            "paired" -> {
                require(selected && invitation != null && peer.session == null && !value.has("session_id") && !value.has("token"))
                val saved = checkNotNull(pairingCandidate)
                require(value.getString("host_id") == saved.hostId && value.getString("pair_id") == saved.pairId)
                if (!activatePairingCandidate(saved)) return
                if (foreground) connectHost() else disconnect()
            }
            "welcome" -> {
                require(peer.session == null && (!selected || invitation == null))
                val authenticated = checkNotNull(peer.connectingHost)
                require(value.getString("host_id") == authenticated.hostId && value.getString("pair_id") == authenticated.pairId)
                val sessionId = validId(value.getString("session_id"))
                val recoveringPairing = selected && checkingPairingCandidate
                require(recoveringPairing || authenticated.identity() == peer.host?.identity())
                peer.session = sessionId
                val autoTextSupported = supportsAutoText(value)
                applyState(value, peer)
                if (selected) imageInput?.welcome(value)
                if (recoveringPairing && !activatePairingCandidate(authenticated)) return
                // Full pinned TLS + authenticated host/device welcome precede route commit.
                val previous = checkNotNull(peer.host)
                if (authenticated.address != previous.address || authenticated.port != previous.port) {
                    try { store.save(authenticated.json, select = false) } catch (_: Exception) { storageFailed(); return }
                    peer.host = authenticated
                }
                peer.candidates.clear()
                peer.welcome = value
                reconnectPolicy.reset(authenticated.hostId, authenticated.pairId)
                peer.deadline?.cancel(); peer.deadline = null
                val saved = checkNotNull(peer.host)
                val pending = dao.pending(saved.hostId, saved.deviceId)
                mutable.update {
                    val updated = it.copy(pairedTargets = it.pairedTargets.map { target ->
                        if (target.hostId == saved.hostId && target.pairId == saved.pairId) saved.target() else target
                    })
                    if (selected) updated.copy(connection = Connection.ONLINE, address = saved.address,
                        port = saved.port, issue = null, recovering = true, autoTextSupported = autoTextSupported)
                    else updated
                }
                val id = UUID.randomUUID().toString()
                peer.queries[id] = pending.map { it.id }.toSet()
                sendFrame(base("query", peer).put("ref", id).put("ids", JSONArray(pending.map { it.id })), peer)
                val owner = peer.generation
                peer.heartbeat = scope.launch { while (true) { delay(8000); enqueue(Event.Tick(owner)) } }
            }
            else -> {
                if (kind == "err" && peer.session == null) {
                    require(!value.has("session_id"))
                    val code = value.getString("code")
                    val saved = pairingCandidate.takeIf { selected }
                    if (code in setOf("UNAUTHORIZED", "PAIR_REPLACE_BLOCKED", "TOO_LATE") &&
                        saved != null && (checkingPairingCandidate || invitation != null)) {
                        try { store.removeCandidate(saved.hostId, saved.pairId) } catch (_: Exception) { storageFailed(); return }
                        pairingCandidate = null
                        mutable.update { it.copy(pairingRecoveryPending = false) }
                    }
                    fail(when {
                        code == "PAIRING_BUSY" -> ClientIssue.PAIRING_BUSY
                        code == "PAIR_REPLACE_BLOCKED" -> ClientIssue.PAIR_REPLACE_BLOCKED
                        code == "VERSION_MISMATCH" -> ClientIssue.VERSION_MISMATCH
                        selected && (invitation != null || checkingPairingCandidate || saved != null) -> ClientIssue.INVALID_PAIRING
                        else -> ClientIssue.CONNECTION
                    }, peer)
                    return
                }
                require(value.getString("session_id") == peer.session && peer.session != null)
                when (kind) {
                    "state", "controlled" -> applyState(value, peer)
                    "status" -> {
                        saveStatus(value, peer)
                        retireIdlePrevious(peer)
                    }
                    "queried" -> {
                        val refs = peer.queries.remove(value.getString("ref")) ?: error("Unexpected query")
                        // Each referenced status must have been durably processed first.
                        require(refs.isEmpty())
                        if (selected) mutable.update { it.copy(recovering = peer.queries.isNotEmpty()) }
                        retireIdlePrevious(peer)
                    }
                    "foreground" -> if (selected) applyForeground(value)
                    "pong" -> if (selected) confirmForeground(value)
                    "err" -> {
                        // Keep payload/id intact; query on reconnect before any retry.
                        if (selected) {
                            mutable.update { it.copy(issue = ClientIssue.PROTOCOL) }
                            imageInput?.interrupt()
                        }
                        val rejected = (value.opt("ref") as? String)?.let { runCatching { validId(it) }.getOrNull() }
                            ?: (value.opt("id") as? String)?.let { runCatching { validId(it) }.getOrNull() }
                        if (rejected != null && peer.submitting.remove(rejected)) {
                            queryReceipt(rejected, peer)
                        }
                    }
                    else -> error("Unexpected message")
                }
            }
        }
    }
    private fun supportsAutoText(value: JSONObject): Boolean {
        val capabilities = value.getJSONArray("capabilities")
        return (0 until capabilities.length()).any { capabilities.getString(it) == "auto_text" }
    }

    private suspend fun retireIdlePrevious(peer: ControlPeer) {
        if (peer !== previousPeer || peer.queries.isNotEmpty()) return
        val saved = checkNotNull(peer.host)
        if (!dao.hasUnresolvedReceipts(saved.hostId, saved.pairId)) closePreviousPeer()
    }

    private fun applyState(value: JSONObject, peer: ControlPeer) {
        val revision = value.getString("state_rev").also { require(it.matches(Regex("[0-9]{1,20}"))) }
        val target = if (value.isNull("target")) null else value.getString("target").also { require(it.length in 1..128) }
        val pauseReason = value.optString("pause_reason").takeIf { !value.isNull("pause_reason") && it.isNotEmpty() }
            ?.also { require(it.length <= 128) }
        val received = ReceivedState(revision, target, value.getBoolean("paused"), pauseReason)
        peer.receivedState = received
        if (peer === currentPeer) presentState(received)
    }

    private fun presentState(value: ReceivedState) {
        mutable.update { it.copy(paused = value.paused, pauseReason = value.reason, target = value.target, revision = value.revision,
            active = true,
            foregroundApplication = it.foregroundApplication.takeIf { _ -> !value.paused }) }
        imageInput?.scopeChanged()
    }
    // Display metadata is never fed into receptionScope, persistence or request identity.
    // Malformed metadata clears only this hint, leaving authenticated control intact.
    private fun applyForeground(value: JSONObject) {
        val next = foregroundHint.receive(value)
        if (next == foregroundHint) return
        foregroundHint = next
        foregroundDisplay = ForegroundApplication.from(next)
        mutable.update { it.copy(foregroundApplication = foregroundDisplay.takeIf { _ -> it.connection == Connection.ONLINE && it.active && !it.paused }) }
        refreshForeground()
    }
    private fun confirmForeground(value: JSONObject) {
        // A buffered/late pong cannot renew a stale foreground observation.
        if (pingNonce == null || value.optString("nonce") != pingNonce || SystemClock.elapsedRealtime() - pingSentAt >= 8000) return
        pingNonce = null
        if (foregroundHint.confirmed(value)) {
            mutable.update { it.copy(foregroundApplication = foregroundDisplay.takeIf { _ -> it.connection == Connection.ONLINE && it.active && !it.paused }) }
            refreshForeground()
        }
        else mutable.update { it.copy(foregroundApplication = null) }
    }
    private fun refreshForeground() {
        foregroundObservation++
        val observed = foregroundObservation
        val owner = currentPeer.generation
        foregroundExpiry?.cancel()
        foregroundExpiry = scope.launch { delay(20_000); enqueue(Event.ForegroundExpired(owner, observed)) }
    }
    private suspend fun saveStatus(value: JSONObject, peer: ControlPeer) {
        val id = validId(value.getString("id"))
        val status = value.getString("st")
        val wasQueried = peer.queries.values.any { id in it }
        if (status == "unknown") require(wasQueried && id !in peer.submitting)
        else peer.submitting.remove(id)
        peer.queries.replaceAll { _, refs -> refs - id }
        val saved = checkNotNull(peer.host)
        val item = dao.find(id, saved.hostId, saved.deviceId) ?: return
        require(status in setOf("unknown", "accepted", "held", "executing", "injected", "cancelled", "expired", "failed", "uncertain", "uncertain_acknowledged"))
        val terminal = status in setOf("injected", "cancelled", "expired", "failed", "uncertain_acknowledged")
        val order = value.takeUnless { it.isNull("accept_order") }?.get("accept_order")?.toString()?.also { receiptCounter(it) }
        if (status !in setOf("unknown", "cancelled")) require(order != null)
        val requestRev = if (status == "unknown") null else value.get("request_rev").toString().also { receiptCounter(it) }
        if (requestRev != null && receiptCounter(requestRev) < (item.requestRev?.let(::receiptCounter) ?: 0L)) return
        val execution = value.optJSONObject("execution")
        val executionStage = execution?.optString("stage")?.takeIf { it.isNotEmpty() }
        val executionMethod = execution?.optString("method")?.takeIf { it.isNotEmpty() }
        val confirmed = execution?.optBoolean("confirmed", false) ?: false
        val uncertainScope = value.optString("uncertain_scope").takeIf { it.isNotEmpty() }
        // Missing/invalid display metadata must never prevent receipt reconciliation.
        // Fill once, for this execution/order only; never relabel a terminal result.
        if (item.application == null && status in setOf("executing", "injected", "failed", "uncertain") &&
            (item.payload != null || item.state == status && item.order == order)) {
            val display = value.optJSONObject("application")?.let(HistoryApplication::parse)
            if (display != null) try {
                dao.application(id, saved.hostId, saved.deviceId, store.encrypt(saved.hostId, saved.pairId, display.protectedJson(item)))
            } catch (_: Exception) { storageFailed(); return }
        }
        // Terminal rows are immutable locally; a stale response cannot resurrect text.
        val canUpdate = item.payload != null || item.state == "uncertain" && status == "uncertain_acknowledged"
        if (canUpdate && !(status == "unknown" && item.order != null)) {
            try {
                dao.status(id, saved.hostId, saved.deviceId, if (status == "unknown") "review" else status, order,
                    value.optString("reason").takeIf { it.isNotEmpty() }, terminal, executionMethod, requestRev,
                    executionStage, uncertainScope, confirmed)
                imageInput?.cleanup(dao.trimTerminal())
            }
            catch (_: Exception) { storageFailed(); return }
        } else if (requestRev != null && item.requestRev == null && item.state == status && item.order == order) {
            // A migrated v2 receipt can acquire authenticated v3 metadata while
            // retaining its original outcome, payload, reason and history flags.
            try {
                dao.migratedReceiptDetails(id, saved.hostId, saved.deviceId, status, order,
                    executionMethod, requestRev, executionStage, uncertainScope, confirmed)
            } catch (_: Exception) { storageFailed(); return }
        }
        if (peer === currentPeer && item.kind == "image" && (status != "unknown" || item.order == null)) {
            imageInput?.status(item.copy(method = executionMethod), if (status == "unknown") "review" else status, terminal)
        }
    }
    private fun receiptCounter(value: String): Long {
        require(value.matches(Regex("[0-9]{1,20}")))
        return requireNotNull(value.toLongOrNull())
    }
    private suspend fun restoreSpeakingDraft(): SavedSpeakingDraft? {
        dao.speakingDraft()?.let { return draftStore.open(it) }
        val legacy = draftStore.readLegacy() ?: return null
        val restored = draftStore.open(legacy)
        dao.saveSpeakingDraft(SpeakingDraftEntity(legacy))
        draftStore.deleteLegacy()
        return restored
    }

    private suspend fun saveEditableSpeakingDraft(draft: SavedSpeakingDraft) {
        // The UI freezes before the mailbox validates a send. Only the
        // transaction may create its durable, pending association.
        if (draft.requestState == "saved") {
            val id = draft.requestId ?: return
            val hostId = draft.requestHostId ?: return
            val pairId = draft.requestPairId ?: return
            if (dao.find(id, hostId, pairId) == null) return
        }
        dao.saveSpeakingDraft(sealSpeakingDraft(draft))
    }

    private suspend fun sealSpeakingDraft(draft: SavedSpeakingDraft): SpeakingDraftEntity =
        SpeakingDraftEntity(draftStore.seal(draft, createKey = !dao.hasSpeakingDraft()))

    private suspend fun saveAndSend(event: Event.Send) {
        val draft = event.draft
        if (event.expectedScope == null || mutable.value.receptionScope != event.expectedScope ||
            event.destination != null && mutable.value.autoSendDestination != event.destination) {
            mutable.update { it.copy(issue = ClientIssue.NOT_READY, rejectedDraft = draft.id, rejectedDraftScope = event.expectedScope) }; return
        }
        val saved = checkNotNull(host)
        check(draft.requestId == draft.id && draft.requestState == "saved" &&
            draft.requestHostId == saved.hostId && draft.requestPairId == saved.pairId)
        val normalized = try { normalizedText(checkNotNull(draft.requestText)) } catch (_: Exception) {
            mutable.update { it.copy(issue = ClientIssue.INVALID_TEXT, rejectedDraft = draft.id, rejectedDraftScope = event.expectedScope) }; return
        }
        // The editor's saved UUID is the durable operation identity. A process
        // death after enqueue but before clearing the editor cannot allocate a
        // second request for the same restored draft.
        val id = validId(draft.id)
        if (dao.find(id, saved.hostId, saved.deviceId) != null) {
            mutable.update { it.copy(savedDraft = draft.id, rejectedDraft = null, rejectedDraftScope = null, issue = null) }
            return
        }
        val target = checkNotNull(mutable.value.target)
        val operation = JSONObject().put("text", normalized)
        if (event.destination == null) operation.put("kind", "text").put("src", "manual")
        else operation.put("kind", "auto_text").put("application", event.destination.application)
        val payload = try { store.encrypt(saved.hostId, saved.pairId, operation.toString().toByteArray()) } catch (_: Exception) { storageFailed(); return }
        val transcript = try { store.encrypt(saved.hostId, saved.pairId, normalized.toByteArray(Charsets.UTF_8)) } catch (_: Exception) { storageFailed(); return }
        val item = OutboxItem(id, saved.hostId, saved.deviceId, target, payload, normalized.toByteArray().size, "saved", null, null, System.currentTimeMillis(), transcript)
        if (dao.pendingCountFor(saved.hostId, saved.deviceId) >= 16 ||
            dao.pendingBytesFor(saved.hostId, saved.deviceId) + item.byteCount > 65536 ||
            dao.pendingCountForHost(saved.hostId) >= 32 ||
            dao.pendingBytesForHost(saved.hostId) + item.byteCount > 131072) {
            mutable.update { it.copy(issue = ClientIssue.QUOTA, rejectedDraft = draft.id, rejectedDraftScope = event.expectedScope) }
            return
        }
        try {
            val garbage = dao.enqueueSpeaking(item, sealSpeakingDraft(draft))
            imageInput?.cleanup(garbage)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { storageFailed(); return }
        mutable.update { it.copy(savedDraft = draft.id, rejectedDraft = null, rejectedDraftScope = null, issue = null) }
        submit(item)
    }
    private suspend fun submit(item: OutboxItem) {
        check(mutable.value.ready && item.target == mutable.value.target && item.hostId == host?.hostId && item.deviceId == host?.deviceId)
        val operation = try { strictJson(store.decrypt(item.hostId, item.deviceId, checkNotNull(item.payload)).toString(Charsets.UTF_8)) }
        catch (_: Exception) { storageFailed(); return }
        if (operation.optString("kind") == "auto_text" && !mutable.value.autoTextSupported) return
        if (item.id in currentPeer.submitting || currentPeer.queries.values.any { item.id in it }) return
        sendFrame(base("submit").put("id", item.id).put("target", item.target).put("op", operation))
        currentPeer.submitting.add(item.id)
    }
    private fun queryReceipt(id: String, peer: ControlPeer = currentPeer) {
        if (peer.session == null || peer.welcome == null || peer.queries.size >= 16 ||
            id in peer.submitting || peer.queries.values.any { id in it }) return
        val queryId = UUID.randomUUID().toString()
        peer.queries[queryId] = setOf(id)
        sendFrame(base("query", peer).put("ref", queryId).put("ids", JSONArray(listOf(id))), peer)
    }
    private suspend fun historyAction(key: OutboxKey, action: String) {
        if (action == "delete") { dao.hide(key); return }
        val peer = sequenceOf(currentPeer, previousPeer).filterNotNull().firstOrNull {
            it.host?.let { saved -> saved.hostId == key.hostId && saved.pairId == key.pairId } == true
        } ?: return
        val id = key.id
        val item = dao.find(id, key.hostId, key.pairId) ?: return
        if (action == "query") {
            queryReceipt(id, peer)
            return
        }
        if (item.payload == null) return
        // Image references belong to their original session and intent. Queries
        // and explicit discard remain available; retry/release never reupload.
        if (item.kind == "image" && action in setOf("retry", "release")) return
        when (action) {
            "retry" -> if (peer === currentPeer && item.state == "review" &&
                mutable.value.ready && item.target == mutable.value.target) submit(item)
            "discard" -> if (item.state == "review") dao.status(id, key.hostId, key.pairId, "cancelled", item.order, "local_discard", true)
            "release", "cancel" -> if (peer.session != null &&
                (action == "cancel" || peer === currentPeer) && item.state in setOf("accepted", "held")) {
                val state = checkNotNull(peer.receivedState)
                val reference = UUID.randomUUID().toString()
                sendFrame(base("control", peer).put("ref", reference).put("name", action)
                    .put("refs", JSONArray(listOf(id))).put("target", state.target)
                    .put("expected_rev", state.revision).put("expected_request_rev", item.requestRev ?: "0"), peer)
            }
        }
    }

    /** Phone-initiated pause/resume for the active device. No refs are implied or retried. */
    private fun control(name: String) {
        if (name != "pause" && name != "resume") return
        val current = mutable.value
        if (session == null || current.connection != Connection.ONLINE || !current.active) return
        if (current.paused == (name == "pause")) return
        sendFrame(base("control").put("id", UUID.randomUUID().toString()).put("name", name)
            .put("expected_rev", current.revision))
    }

    suspend fun draftImage(resourceId: String, edge: Int): android.graphics.Bitmap? =
        imageInput?.preview(resourceId, edge)

    suspend fun historyImage(key: OutboxKey, edge: Int): android.graphics.Bitmap? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!::store.isInitialized || edge !in setOf(256, 1600)) return@withContext null
        runCatching { dao.visible(key)?.let { HistoryImages.read(app, store, it, edge) } }.getOrNull()
    }
}
