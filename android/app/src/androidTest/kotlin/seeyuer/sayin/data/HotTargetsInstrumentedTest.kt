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
            suspend fun pair(portKey: String, codeKey: String): String {
                val port = checkNotNull(arguments.getString(portKey))
                repository.pairCode("127.0.0.1", port, checkNotNull(arguments.getString(codeKey)))
                return checkNotNull(await { it.ready && it.port.toString() == port }.selectedHostId)
            }
            val a = pair("fixturePort", "fixtureCode")
            val b = pair("secondFixturePort", "secondFixtureCode")
            val c = pair("thirdFixturePort", "thirdFixtureCode")
            assertEquals(3, setOf(a, b, c).size)

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
        } finally {
            repository.close()
            delay(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
