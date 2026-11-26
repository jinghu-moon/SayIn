package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ControlPerformanceInstrumentedTest {
    private data class ReceiptTiming(val key: OutboxKey, val status: String, val nanos: Long)

    @Test fun measureWarmTextAndColdTargetSwitches() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("controlPerformance") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val count = checkNotNull(args.getString("controlSamples")).toInt()
        require(count in 1..100)
        val namespace = "control-perf-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val receipts = Channel<ReceiptTiming>(16)
        val sessions = ConcurrentHashMap<String, String>()
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() },
            receiptTiming = { key, status, nanos -> receipts.trySend(ReceiptTiming(key, status, nanos)) },
            sessionObserved = { host, session -> sessions[host] = session })
        val warm = JSONArray()
        val cold = JSONArray()
        val evidence = File(target.getExternalFilesDir(null), "control-performance-result.json")
        suspend fun ready(host: String? = null): ClientState = repository.state.first {
            check(it.storageHealthy)
            it.ready && (host == null || it.selectedHostId == host)
        }
        try {
            withTimeout(15000) { repository.state.first { it.connection == Connection.OFFLINE } }
            repository.foreground(true)
            suspend fun pair(prefix: String): String {
                val address = checkNotNull(args.getString("${prefix}Address"))
                check(address != "127.0.0.1")
                val port = checkNotNull(args.getString("${prefix}Port"))
                repository.pairCode(address, port, checkNotNull(args.getString("${prefix}Code")))
                return checkNotNull(withTimeout(15000) {
                    repository.state.first { it.ready && it.port.toString() == port }
                }.selectedHostId)
            }
            val first = pair("fixture")
            val second = pair("secondFixture")
            val third = pair("thirdFixture")
            check(setOf(first, second, third).size == 3)
            repository.selectHost(second)
            withTimeout(15000) { ready(second) }
            val warmSession = checkNotNull(sessions[second])
            val texts = listOf("SayIn short text", "中文输入测试。", "日本語と한국어", "Emoji 😀 {ENTER}", "One\nTwo")
            repeat(count) { iteration ->
                val state = withTimeout(15000) { ready(second) }
                val scope = checkNotNull(state.receptionScope)
                val id = UUID.randomUUID().toString()
                val text = texts[iteration % texts.size]
                val key = OutboxKey(checkNotNull(scope.hostId), checkNotNull(scope.pairId), id)
                val sample = JSONObject().put("iteration", iteration + 1).put("id", id)
                warm.put(sample)
                val start = SystemClock.elapsedRealtimeNanos()
                repository.send(text, id, scope)
                val outcome = withTimeoutOrNull(5000) {
                    var terminal: ReceiptTiming
                    do {
                        terminal = receipts.receive()
                        assertEquals(key, terminal.key)
                        if (!sample.has("first_ms")) {
                            sample.put("first_state", terminal.status).put("first_ms", (terminal.nanos - start) / 1e6)
                        }
                    } while (terminal.status == "accepted" || terminal.status == "executing")
                    terminal
                }
                if (outcome == null) {
                    sample.put("terminal_timeout", true)
                    error("Missing terminal for text sample ${iteration + 1}")
                }
                sample.put("terminal_state", outcome.status).put("terminal_ms", (outcome.nanos - start) / 1e6)
                assertEquals("injected", outcome.status)
                assertEquals(warmSession, sessions[second])
            }
            val hosts = listOf(first, second, third)
            repeat(count) { iteration ->
                val next = hosts[iteration % hosts.size]
                val previousSession = sessions[next]
                val sample = JSONObject().put("iteration", iteration + 1)
                cold.put(sample)
                val start = SystemClock.elapsedRealtimeNanos()
                repository.selectHost(next)
                val online = withTimeoutOrNull(15000) {
                    repository.state.first {
                        it.storageHealthy && it.ready && it.selectedHostId == next &&
                            sessions[next] != previousSession
                    }
                }
                if (online == null) {
                    sample.put("timeout", true)
                    error("Target switch did not connect for sample ${iteration + 1}")
                }
                sample.put("ready_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                val connectedSession = checkNotNull(sessions[next])
                check(connectedSession != previousSession) { "Switch reused a warm connection" }
            }
        } finally {
            evidence.writeText(JSONObject().put("warm_text", warm).put("cold_switch", cold).toString(2))
            repository.close()
            delay(250)
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
