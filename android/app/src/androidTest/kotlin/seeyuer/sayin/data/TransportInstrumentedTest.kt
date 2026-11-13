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
        val namespace = "transport-test-${UUID.randomUUID()}-"
        // This test never reads or replaces the user's paired computer or Outbox.
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        var repository = VoiceLinkRepository(context)
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
            waitFor { it.ready }
            val draftId = UUID.randomUUID().toString()
            repository.send("中文 😀 {ENTER}", draftId)
            val delivered = waitFor { state -> state.history.any { it.state == "injected" } }
            assertTrue(delivered.paired)
            assertEquals(draftId, delivered.savedDraft)
            assertEquals(1, delivered.history.size)
            assertNull(delivered.history.single().payload)
            val id = delivered.history.single().id
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
            assertEquals("receiver_focused", expired.reason)
            assertNull(expired.payload)
            repository.foreground(false)
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.ready }
            repository.close()
            delay(250)
            repository = VoiceLinkRepository(context)
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.ready }
            // Duplicate callbacks for the same saved draft must not allocate another request.
            repository.send("中文 😀 {ENTER}", draftId)
            delay(250)
            val restored = repository.state.value.history
            assertEquals(2, restored.size)
            assertEquals(id, restored.single { it.state == "injected" }.id)
            assertEquals(heldId, restored.single { it.state == "expired" }.id)
            assertTrue(repository.state.value.ready)
        } finally {
            repository.close()
            // Namespaced fixture files only. Keystore keys are never deleted.
            delay(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.getSharedPreferences(namespace + "paired_host", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
