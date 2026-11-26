package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.util.UUID

class PairReplacementInstrumentedTest {
    @Test fun replacementBlocksPendingOutboxAndKeepsOriginalHistory() = runBlocking {
        val registry = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val controlPort = arguments.getString("replacementControlPort")?.toInt()
        assumeTrue(controlPort != null)
        val port = checkNotNull(arguments.getString("fixturePort"))
        val target = registry.targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "replace-pair-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
        try {
            suspend fun await(predicate: (ClientState) -> Boolean): ClientState = withTimeout(15000) {
                repository.state.first {
                    check(it.storageHealthy)
                    predicate(it)
                }
            }
            await { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            repository.pairCode("127.0.0.1", port, checkNotNull(arguments.getString("fixtureCode")))
            val original = await { it.ready }.pairedTargets.single()
            val replacementCode = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", checkNotNull(controlPort)).use { socket ->
                    socket.soTimeout = 10_000
                    val bytes = ByteArray(8)
                    DataInputStream(socket.getInputStream()).readFully(bytes)
                    bytes.toString(Charsets.US_ASCII)
                }
            }
            val held = UUID.randomUUID().toString()
            repository.send("fixture: hold without input", held)
            await { state -> state.history.any { it.id == held && it.state == "held" } }
            repository.prepareReplacement()
            assertFalse(await { it.issue == ClientIssue.PAIR_REPLACE_BLOCKED }.replacingPair)
            repository.action(OutboxKey(original.hostId, original.pairId, held), "cancel")
            await { state -> state.history.any { it.id == held && it.state == "cancelled" } }

            val id = UUID.randomUUID().toString()
            val key = OutboxKey(original.hostId, original.pairId, id)
            val text = "Pair replacement history"
            repository.send(text, id)
            await { it.history.any { row -> row.id == id && row.state == "injected" } && it.historyText[key] == text }
            repository.prepareReplacement()
            await { it.replacingPair }
            repository.pairCode("127.0.0.1", port, replacementCode)
            val replaced = await { current -> current.connection == Connection.ONLINE && !current.recovering &&
                current.pairedTargets.singleOrNull()?.pairId != original.pairId }
            val newPair = replaced.pairedTargets.single()
            assertEquals(original.hostId, newPair.hostId)
            assertNotEquals(original.pairId, newPair.pairId)
            assertEquals(text, replaced.historyText[key])
            assertEquals(original.pairId, replaced.history.single { it.id == id }.deviceId)
            assertNull(ProtectedStore(context).load(original.hostId, original.pairId))
        } finally {
            repository.close()
            delay(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
