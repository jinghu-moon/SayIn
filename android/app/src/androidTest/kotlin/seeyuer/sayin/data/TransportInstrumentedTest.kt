package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransportInstrumentedTest {
    @Test fun unknownReceiptRequiresExplicitRetryAfterReconnect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("fixtureMissingReceipt") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "missing-receipt-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val sessions = java.util.concurrent.atomic.AtomicReference<String>()
        fun repository() = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() },
            sessionObserved = { _, session -> sessions.set(session) })
        var client = repository()
        try {
            suspend fun await(predicate: (ClientState) -> Boolean): ClientState = withTimeout(15_000) {
                client.state.first { check(it.storageHealthy); predicate(it) }
            }
            await { it.connection == Connection.OFFLINE }
            client.foreground(true)
            client.pairCode(checkNotNull(args.getString("fixtureAddress")),
                checkNotNull(args.getString("fixturePort")), checkNotNull(args.getString("fixtureCode")))
            val scope = checkNotNull(await { it.ready }.receptionScope)
            val host = checkNotNull(scope.hostId)
            val pair = checkNotNull(scope.pairId)
            val id = UUID.randomUUID().toString()
            val key = OutboxKey(host, pair, id)
            val body = "Saved offline fixture text"
            val store = ProtectedStore(context)
            val operation = JSONObject().put("kind", "text").put("src", "manual").put("text", body)
            client.close()
            val database = androidx.room.Room.databaseBuilder(context, OutboxDatabase::class.java, "outbox.db").build()
            try {
                database.outbox().insert(OutboxItem(id, host, pair, scope.target,
                    store.encrypt(host, pair, operation.toString().toByteArray()), body.toByteArray().size,
                    "saved", null, null, System.currentTimeMillis(), store.encrypt(host, pair, body.toByteArray())))
            } finally {
                database.close()
            }

            client = repository()
            await { it.connection == Connection.OFFLINE }
            client.foreground(true)
            suspend fun review(): ClientState = await { state ->
                state.ready && state.history.any { it.key == key && it.state == "review" }
            }
            fun checkUnknown(state: ClientState) {
                val row = state.history.single()
                assertEquals(key, row.key)
                assertNull(row.order)
                assertNull(row.requestRev)
                assertNull(row.executionStage)
                assertNotNull(row.payload)
                assertEquals(body, state.historyText[key])
            }
            checkUnknown(review())
            val before = checkNotNull(sessions.get())
            client.foreground(false)
            await { it.connection == Connection.OFFLINE }
            client.foreground(true)
            val reconnected = review()
            assertNotEquals(before, sessions.get())
            checkUnknown(reconnected)

            client.action(key, "retry")
            val delivered = await { state -> state.history.any { it.key == key && it.state == "injected" } }
            assertEquals(key, delivered.history.single().key)
            assertNull(delivered.history.single().payload)
            assertEquals(body, delivered.historyText[key])
        } finally {
            client.close()
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }

    @Test fun nativeCodePairingPinnedTlsEncryptedOutboxAndReconnect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val code = args.getString("fixtureCode")
        assumeTrue("Run with the loopback Android test fixture", code != null)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val keepUpgradeFixture = args.getString("keepUpgradeFixture") == "true"
        check(!keepUpgradeFixture || target.packageName == "seeyuer.sayin.imagetest")
        val namespace = if (keepUpgradeFixture) "v2-upgrade-" else "transport-test-${UUID.randomUUID()}-"
        check(!keepUpgradeFixture || !target.getDatabasePath(namespace + "outbox.db").exists())
        // This test never reads or replaces the user's paired computer or Outbox.
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        var repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
        var verified = false
        try {
            suspend fun waitFor(predicate: (ClientState) -> Boolean): ClientState = withTimeout(15000) {
                while (!predicate(repository.state.value)) {
                    val state = repository.state.value
                    check(state.issue == null) { "Client failed: ${state.issue}" }
                    delay(20)
                }
                repository.state.value
            }
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            val qr = args.getString("fixtureQr")
            if (qr == null) repository.pairCode("127.0.0.1", args.getString("fixturePort") ?: "17888", code!!)
            else repository.pairQr(decode(qr).toString(Charsets.US_ASCII))
            assertTrue(waitFor { it.ready }.autoTextSupported)
            val validScope = checkNotNull(repository.state.value.receptionScope)
            repository.send("stale scope must not enqueue", UUID.randomUUID().toString(), validScope.copy(target = "stale-target"))
            withTimeout(5000) {
                while (repository.state.value.issue != ClientIssue.NOT_READY) delay(20)
            }
            assertTrue(repository.state.value.history.isEmpty())
            assertNull(repository.state.value.savedDraft)
            val draftId = UUID.randomUUID().toString()
            repository.send("中文 😀 {ENTER}", draftId, validScope)
            withTimeout(5000) {
                while (repository.state.value.savedDraft != draftId) {
                    check(repository.state.value.issue in listOf(null, ClientIssue.NOT_READY))
                    delay(20)
                }
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath("outbox.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT ciphertext FROM speaking_draft WHERE id=1", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertFalse(row.getBlob(0).toString(Charsets.UTF_8).contains("{ENTER}"))
                }
            }
            val persistedDraft = checkNotNull(repository.loadSpeakingDraft())
            assertEquals(draftId, persistedDraft.requestId)
            assertEquals(validScope.hostId, persistedDraft.requestHostId)
            assertEquals(validScope.pairId, persistedDraft.requestPairId)
            assertEquals("中文 😀 {ENTER}", persistedDraft.requestText)
            val delivered = waitFor { state -> state.history.any { it.state == "injected" } }
            assertTrue(delivered.paired)
            assertEquals(draftId, delivered.savedDraft)
            assertEquals(1, delivered.history.size)
            assertNull(delivered.history.single().payload)
            val deliveredKey = delivered.history.single().key
            val id = deliveredKey.id
            assertNotNull(delivered.history.single().transcript)
            waitFor { it.historyText[deliveredKey] == "中文 😀 {ENTER}" && it.historyApplications[deliveredKey]?.name == "Fixture Editor" }
            assertNotNull(repository.state.value.historyApplications[deliveredKey]?.icon)
            assertNotNull(repository.state.value.history.single().application)
            // Querying a terminal receipt must retain its original identity and
            // encrypted display text, without submitting another input attempt.
            repeat(2) {
                repository.action(deliveredKey, "query")
                delay(200)
                val queried = waitFor { it.ready && !it.recovering }
                assertEquals(id, queried.history.single().id)
                assertNull(queried.history.single().payload)
                assertEquals("中文 😀 {ENTER}", queried.historyText[deliveredKey])
            }
            val heldId = UUID.randomUUID().toString()
            repository.send("fixture: hold without input", heldId)
            val held = waitFor { state -> state.history.any { it.id == heldId && it.state == "held" } }
            assertEquals("receiver_focused", held.history.single { it.id == heldId }.reason)
            assertNotNull(held.history.single { it.id == heldId }.payload)
            // Do not query or reconnect: expiry must arrive as a server push.
            withTimeout(35000) {
                while (repository.state.value.history.none { it.id == heldId && it.state == "expired" }) {
                    check(repository.state.value.issue == null)
                    delay(20)
                }
            }
            val expired = repository.state.value.history.single { it.id == heldId }
            assertEquals("hold_expired", expired.reason)
            assertNull(expired.payload)
            repository.foreground(false)
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.ready }
            repository.close()
            delay(250)
            val pairingStore = ProtectedStore(context)
            val established = PairedHost(checkNotNull(pairingStore.load()))
            pairingStore.forget(established.hostId, established.pairId)
            val rejected = JSONObject(established.json.toString())
                .put("pair_id", UUID.randomUUID().toString())
            pairingStore.saveCandidate(rejected)
            repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
            assertEquals(heldId, repository.loadSpeakingDraft()?.requestId)
            waitFor { it.connection == Connection.OFFLINE && it.pairingRecoveryPending }
            repository.foreground(true)
            withTimeout(15000) {
                while (repository.state.value.connection != Connection.OFFLINE ||
                    repository.state.value.pairingRecoveryPending) delay(20)
            }
            assertEquals(ClientIssue.INVALID_PAIRING, repository.state.value.issue)
            assertFalse(repository.state.value.paired)
            assertNull(pairingStore.loadCandidate())
            repository.close()
            delay(250)

            // Recreate the disk state after the desktop committed approval but
            // the phone never received paired. A normal pinned hello must
            // promote exactly this credential and reconnect to its old Outbox.
            pairingStore.saveCandidate(established.json)
            repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
            waitFor { it.connection == Connection.OFFLINE && it.pairingRecoveryPending }
            assertFalse(repository.state.value.paired)
            repository.foreground(true)
            waitFor { it.ready }
            assertNull(pairingStore.loadCandidate())
            assertEquals(established.pairId, pairingStore.load()!!.getString("pair_id"))
            // Duplicate callbacks for the same saved draft must not allocate another request.
            repository.send("中文 😀 {ENTER}", draftId)
            delay(250)
            val restored = repository.state.value.history
            assertEquals("Fixture Editor", repository.state.value.historyApplications[deliveredKey]?.name)
            assertNotNull(repository.state.value.historyApplications[deliveredKey]?.icon)
            assertEquals(2, restored.size)
            assertEquals(id, restored.single { it.state == "injected" }.id)
            assertEquals(heldId, restored.single { it.state == "expired" }.id)
            assertTrue(repository.state.value.ready)
            // Changing only a route preserves identity and queries the same encrypted Outbox.
            val original = PairedHost(checkNotNull(ProtectedStore(context).load()))
            repository.updateAddress("127.0.0.1", "1")
            withTimeout(5000) { while (repository.state.value.issue != ClientIssue.DISCOVERY) delay(20) }
            val failedEpoch = repository.state.value.connectionEpoch
            // No manual Connect: the foreground reconnect loop must make another attempt.
            withTimeout(6000) { while (repository.state.value.connectionEpoch <= failedEpoch) delay(20) }
            repository.updateAddress("127.0.0.1", args.getString("fixturePort") ?: "17888")
            delay(250)
            waitFor { it.ready }
            val updated = PairedHost(checkNotNull(ProtectedStore(context).load()))
            assertEquals(original.hostId, updated.hostId)
            assertEquals(original.deviceId, updated.deviceId)
            assertEquals(original.token, updated.token)
            assertArrayEquals(original.certificate.encoded, updated.certificate.encoded)
            assertEquals(2, repository.state.value.history.size)
            // The real desktop protocol must keep uncertainty across phone reconnects.
            repository.send("fixture: uncertain", UUID.randomUUID().toString())
            waitFor { it.paused && it.pauseReason == "outcome_review_required" }
            assertFalse(repository.state.value.ready)
            repository.foreground(false)
            withTimeout(5000) { while (repository.state.value.connection != Connection.OFFLINE) delay(20) }
            repository.foreground(true)
            waitFor { it.connection == Connection.ONLINE && !it.recovering }
            assertEquals("outcome_review_required", repository.state.value.pauseReason)
            assertFalse(repository.state.value.ready)
            assertEquals(3, repository.state.value.history.size)
            val uncertainId = repository.state.value.history.single { it.state == "uncertain" }.id
            val uncertainKey = repository.state.value.history.single { it.id == uncertainId }.key
            repository.action(uncertainKey, "delete")
            waitFor { it.history.single { row -> row.id == uncertainId }.historyHidden }
            repository.action(uncertainKey, "query")
            delay(250)
            assertTrue(repository.state.value.history.single { it.id == uncertainId }.historyHidden)
            assertEquals("outcome_review_required", repository.state.value.pauseReason)
            assertFalse(repository.state.value.ready)
            if (keepUpgradeFixture) {
                val store = SpeakingDraftStore(context, "-v2-upgrade")
                val body = "fixture draft"
                val draft = SavedSpeakingDraft(UUID.randomUUID().toString(), body, 0, body.length)
                val ciphertext = store.seal(draft, createKey = true)
                java.io.File(context.noBackupFilesDir, "speaking-v2-upgrade.bin").writeBytes(ciphertext)
                assertEquals(draft, store.open(checkNotNull(store.readLegacy())))
            }
            verified = true
        } finally {
            repository.close()
            // Namespaced fixture files only. Keystore keys are never deleted.
            delay(200)
            if (!keepUpgradeFixture || !verified) {
                target.deleteDatabase(namespace + "outbox.db")
                target.getSharedPreferences(namespace + "paired_host", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}

internal fun VoiceLinkRepository.send(
    text: String,
    id: String,
    scope: ReceptionScope? = state.value.receptionScope,
) = send(SavedSpeakingDraft(id, text, 0, text.length,
    requestId = id, requestText = text, requestState = "saved",
    requestHostId = scope?.hostId, requestPairId = scope?.pairId), scope)
