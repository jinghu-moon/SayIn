package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run separately with verify_android_discovery.ps1. LAN mode uses real NSD,
 * never adb reverse. Every file/identity is test-namespaced.
 */
@RunWith(AndroidJUnit4::class)
class DiscoveryInstrumentedTest {
    @Test fun authenticatedRouteMigrationAndCancelledDiscovery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val address = checkNotNull(args.getString("discoveryAddress"))
        val oldPort = checkNotNull(args.getString("discoveryPort"))
        val newAddress = checkNotNull(args.getString("discoveryNewAddress"))
        val newPort = checkNotNull(args.getString("discoveryNewPort"))
        val revoked = args.getString("discoveryRevoked") == "true"
        val lan = args.getString("discoveryLan") == "true"
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "discovery-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val systemDiscovery = NsdHostDiscovery(context)
        val hints = if (lan) HostDiscovery { hostId, network ->
            systemDiscovery.discover(hostId, network).also { println("Real NSD candidates: $it") }
        } else HostDiscovery { _, _ ->
            // Equivalent to an attacker advertising the same name/host_id: TLS
            // must reject its endpoint before hello/token, then try the real one.
            listOf(HostCandidate("127.0.0.1", args.getString("discoveryWrongPort")!!.toInt()), HostCandidate(newAddress, newPort.toInt()))
        }
        var repository = VoiceLinkRepository(context, hints)
        suspend fun waitFor(timeout: Long = 20000, predicate: (ClientState) -> Boolean): ClientState = withTimeout(timeout) {
            try { while (!predicate(repository.state.value)) delay(25) }
            catch (failure: TimeoutCancellationException) {
                val state = repository.state.value
                throw AssertionError("Timed out: connection=${state.connection}, issue=${state.issue}, discovering=${state.discovering}, port=${state.port}", failure)
            }
            repository.state.value
        }
        try {
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            repository.pairCode(address, oldPort, checkNotNull(args.getString("discoveryCode")))
            waitFor { it.ready }
            val store = ProtectedStore(context)
            val original = PairedHost(checkNotNull(store.load()))
            if (!lan) {
                val failed = CompletableDeferred<Throwable>()
                val opened = java.util.concurrent.atomic.AtomicBoolean(false)
                val wrong = HostClient(original.withAddress("127.0.0.1", args.getString("discoveryWrongPort")!!))
                try {
                    wrong.open(object : okhttp3.WebSocketListener() {
                        override fun onOpen(socket: okhttp3.WebSocket, response: okhttp3.Response) { opened.set(true); socket.cancel() }
                        override fun onFailure(socket: okhttp3.WebSocket, failure: Throwable, response: okhttp3.Response?) { failed.complete(failure) }
                    })
                    val failure = withTimeout(5000) { failed.await() }
                    assertTrue("Wrong certificate must fail TLS, not merely fail routing", failure is javax.net.ssl.SSLHandshakeException)
                    assertFalse("No Open event means repository cannot send hello/token", opened.get())
                } finally { wrong.close() }
            }
            val originalScope = checkNotNull(repository.state.value.receptionScope)
            val delivered = UUID.randomUUID().toString()
            repository.send("discovery: exactly one fake input", delivered)
            waitFor { it.history.any { row -> row.id == delivered && row.state == "injected" } }
            delay(150)
            val held = UUID.randomUUID().toString()
            repository.send("fixture:hold", held)
            waitFor { it.history.any { row -> row.id == held && row.state == "held" } }
            waitFor(20000) { it.connection != Connection.ONLINE }
            // No candidate address may replace the saved endpoint yet.
            assertEquals(oldPort.toInt(), PairedHost(checkNotNull(store.load())).port)
            if (revoked) {
                delay(16000)
                assertNotEquals(Connection.ONLINE, repository.state.value.connection)
                assertEquals(oldPort.toInt(), PairedHost(checkNotNull(store.load())).port)
            } else {
                val recovered = waitFor(60000) { it.connection == Connection.ONLINE && !it.recovering && it.port == newPort.toInt() }
                val updated = PairedHost(checkNotNull(store.load()))
                assertEquals(newAddress, updated.address)
                assertEquals(original.hostId, updated.hostId)
                assertEquals(original.deviceId, updated.deviceId)
                assertEquals(original.token, updated.token)
                assertEquals(original.tlsName, updated.tlsName)
                assertArrayEquals(original.certificate.encoded, updated.certificate.encoded)
                assertTrue(recovered.paused)
                assertFalse(recovered.ready)
                assertEquals(held, recovered.history.single { it.id == held }.id)
                assertTrue(recovered.history.single { it.id == held }.state in setOf("held", "expired"))
                // An old auto-send ticket cannot enqueue on the replacement connection.
                repository.send("must not replay", UUID.randomUUID().toString(), originalScope)
                waitFor { it.issue == ClientIssue.NOT_READY }
                assertEquals(2, repository.state.value.history.size)
            }
            repository.close(); delay(200)
            // Deliberately deliver a non-cooperative stale discovery result after
            // foreground cancellation. It must not open or persist a candidate.
            val before = PairedHost(checkNotNull(store.load()))
            store.save(before.withAddress(address, "1").json)
            val started = CompletableDeferred<Unit>()
            val late = CompletableDeferred<List<HostCandidate>>()
            val permitted = java.util.concurrent.atomic.AtomicBoolean(false)
            repository = VoiceLinkRepository(context, HostDiscovery { _, _ ->
                started.complete(Unit)
                withContext(NonCancellable) { late.await() }
            }, localPermission = { permitted.get() })
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.issue == ClientIssue.LOCAL_NETWORK_PERMISSION }
            assertFalse(started.isCompleted)
            permitted.set(true)
            withTimeout(15000) { started.await() }
            permitted.set(false)
            waitFor { it.issue == ClientIssue.LOCAL_NETWORK_PERMISSION && !it.discovering }
            repository.foreground(false)
            waitFor { !it.discovering && it.connection == Connection.OFFLINE }
            late.complete(listOf(HostCandidate(newAddress, newPort.toInt())))
            delay(500)
            assertEquals(Connection.OFFLINE, repository.state.value.connection)
            assertEquals(1, PairedHost(checkNotNull(store.load())).port)
            assertEquals(2, repository.state.value.history.size)
        } finally {
            repository.close(); delay(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.getSharedPreferences(namespace + "paired_host", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
