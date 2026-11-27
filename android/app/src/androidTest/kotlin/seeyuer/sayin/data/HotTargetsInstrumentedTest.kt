package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class HotTargetsInstrumentedTest {
    @Test fun previousTargetReconcilesWhileSelectedTargetCanSendAndThirdTargetCools() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.containsKey("thirdFixtureCode"))
        val target = instrumentation.targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "hot-targets-${UUID.randomUUID()}-"
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
                    check(it.issue == null) { "Client failed: ${it.issue}" }
                    predicate(it)
                }
            }
            await { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            suspend fun pair(prefix: String): String {
                val address = checkNotNull(arguments.getString("${prefix}Address"))
                val port = checkNotNull(arguments.getString("${prefix}Port"))
                repository.pairCode(address, port, checkNotNull(arguments.getString("${prefix}Code")))
                return await { state -> state.pairedTargets.any { it.address == address && it.port.toString() == port } }
                    .pairedTargets.single { it.address == address && it.port.toString() == port }.hostId
            }
            val a = pair("fixture")
            val b = pair("secondFixture")
            val c = pair("thirdFixture")
            assertEquals(3, setOf(a, b, c).size)
            await { it.ready && it.selectedHostId == a }
            repository.connectHost(b)
            val both = await { it.secondaryHostId == b && it.secondaryConnection == Connection.ONLINE }
            assertEquals(a, both.selectedHostId)
            val systemName = checkNotNull(both.pairedTargets.single { it.hostId == b }.systemName)
            assertTrue(systemName.isNotBlank())
            repository.renameHost(b, "Work laptop")
            await { it.pairedTargets.any { target -> target.hostId == b && target.alias == "Work laptop" } }
            val renamed = PairedHost(checkNotNull(ProtectedStore(context).loadHost(b)))
            assertEquals("Work laptop", renamed.alias)
            assertEquals(systemName, renamed.systemName)

            suspend fun select(host: String) {
                repository.selectHost(host)
                await { it.selectedHostId == host && it.ready }
            }
            suspend fun hold(host: String): OutboxKey {
                val id = UUID.randomUUID().toString()
                repository.send("fixture: hold without input", id)
                val held = await { state -> state.history.any { it.id == id && it.hostId == host && it.state == "held" } }
                return held.history.single { it.id == id && it.hostId == host }.key
            }
            suspend fun cancelled(key: OutboxKey): ClientState =
                await { state -> state.history.any { it.key == key && it.state == "cancelled" } }

            select(a)
            val firstHeld = hold(a)
            select(b)
            val text = "fixture text on selected computer"
            repository.send(text, firstHeld.id)
            val delivered = await { state ->
                state.history.any { it.id == firstHeld.id && it.hostId == b && it.state == "injected" }
            }
            val deliveredKey = delivered.history.single { it.id == firstHeld.id && it.hostId == b }.key
            assertEquals(text, delivered.historyText[deliveredKey])
            assertEquals("fixture: hold without input", delivered.historyText[firstHeld])
            repository.action(firstHeld, "cancel")
            val afterFirstCancel = cancelled(firstHeld)
            assertEquals(b, afterFirstCancel.selectedHostId)
            assertTrue(afterFirstCancel.ready)
            assertEquals("injected", afterFirstCancel.history.single { it.key == deliveredKey }.state)
            assertEquals(firstHeld.id, afterFirstCancel.savedDraft)

            select(a)
            val cold = hold(a)
            select(b)
            val retained = hold(b)
            select(c)
            repository.action(cold, "cancel")
            repository.action(retained, "cancel")
            val afterSecondCancel = cancelled(retained)
            assertEquals(c, afterSecondCancel.selectedHostId)
            assertEquals("held", afterSecondCancel.history.single { it.key == cold }.state)
            select(a)
            repository.action(cold, "cancel")
            assertEquals(a, cancelled(cold).selectedHostId)
            repository.forget(b)
            await { it.ready && it.selectedHostId == a && it.pairedTargets.none { target -> target.hostId == b } }
            repository.forget(a)
            val remaining = await { it.selectedHostId == null && it.pairedTargets.singleOrNull()?.hostId == c }
            assertEquals(c, remaining.pairedTargets.single().hostId)
        } finally {
            repository.close()
            delay(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
