package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransportInstrumentedTest {
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
            repository.pairCode("127.0.0.1", args.getString("fixturePort") ?: "17888", code!!)
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
            val delivered = waitFor { state -> state.history.any { it.state == "injected" } }
            assertTrue(delivered.paired)
            assertEquals(draftId, delivered.savedDraft)
            assertEquals(1, delivered.history.size)
            assertNull(delivered.history.single().payload)
            val id = delivered.history.single().id
            assertNotNull(delivered.history.single().transcript)
            waitFor { it.historyText[id] == "中文 😀 {ENTER}" && it.historyApplications[id]?.name == "Fixture Editor" }
            assertNotNull(repository.state.value.historyApplications[id]?.icon)
            assertNotNull(repository.state.value.history.single().application)
            // Querying a terminal receipt must retain its original identity and
            // encrypted display text, without submitting another input attempt.
            repeat(2) {
                repository.action(id, "query")
                delay(200)
                val queried = waitFor { it.ready && !it.recovering }
                assertEquals(id, queried.history.single().id)
                assertNull(queried.history.single().payload)
                assertEquals("中文 😀 {ENTER}", queried.historyText[id])
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
            repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.ready }
            // Duplicate callbacks for the same saved draft must not allocate another request.
            repository.send("中文 😀 {ENTER}", draftId)
            delay(250)
            val restored = repository.state.value.history
            assertEquals("Fixture Editor", repository.state.value.historyApplications[id]?.name)
            assertNotNull(repository.state.value.historyApplications[id]?.icon)
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
            repository.action(uncertainId, "delete")
            waitFor { it.history.single { row -> row.id == uncertainId }.historyHidden }
            repository.action(uncertainId, "query")
            delay(250)
            assertTrue(repository.state.value.history.single { it.id == uncertainId }.historyHidden)
            assertEquals("outcome_review_required", repository.state.value.pauseReason)
            assertFalse(repository.state.value.ready)
            if (keepUpgradeFixture) {
                val store = SpeakingDraftStore(context, "-v2-upgrade")
                val body = "fixture draft"
                val draft = SavedSpeakingDraft(UUID.randomUUID().toString(), body, 0, body.length)
                store.save(draft)
                assertEquals(draft, store.load())
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
