package seeyuer.sayin.data

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.SpeakInputSource
import seeyuer.sayin.ui.SpeakPage
import seeyuer.sayin.ui.SpeakSendMode
import seeyuer.sayin.ui.SpeakSession
import seeyuer.sayin.ui.VoiceLinkViewModel
import seeyuer.sayin.ui.rememberTestPhraseLibrary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ControlPerformanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private data class Click(val id: String, val nanos: Long)
    private data class ReceiptTiming(
        val key: OutboxKey, val status: String, val received: Long, val processing: Long, val persisted: Long,
    )
    private data class TextTiming(val id: String, val stage: String, val nanos: Long)

    @Test fun measureWarmTextAndColdTargetSwitches() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("controlPerformance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val clickThroughUi = args.getString("controlClick") == "true"
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
        val textStages = Channel<TextTiming>(16)
        val sessions = ConcurrentHashMap<String, String>()
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() },
            receiptTrace = { key, status, received, processing, persisted ->
                check(receipts.trySend(ReceiptTiming(key, status, received, processing, persisted)).isSuccess)
            },
            sessionObserved = { host, session -> sessions[host] = session },
            textTiming = { id, stage, nanos -> check(textStages.trySend(TextTiming(id, stage, nanos)).isSuccess) })
        val clicks = Channel<Click>(1)
        var activity: ActivityScenario<ComponentActivity>? = null
        lateinit var model: VoiceLinkViewModel
        val owner = Any()
        val warm = JSONArray()
        val cold = JSONArray()
        val evidence = File(target.getExternalFilesDir(null), "control-performance-result.json")
        suspend fun ready(host: String? = null): ClientState = repository.state.first {
            check(it.storageHealthy)
            it.ready && (host == null || it.selectedHostId == host)
        }
        try {
            if (clickThroughUi) {
                // MIUI requires the isolated empty host to be in the foreground before ActivityScenario.
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${target.packageName}/androidx.activity.ComponentActivity",
                ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
                activity = ActivityScenario.launch(ComponentActivity::class.java)
                activity.onActivity { host ->
                    host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    val factory = viewModelFactory {
                        initializer {
                            val speaking = SpeakSession(context).also {
                                it.selectSource(SpeakInputSource.Keyboard)
                                it.changeSendMode(SpeakSendMode.Manual)
                            }
                            VoiceLinkViewModel(target.applicationContext as Application, repository, speaking)
                        }
                    }
                    model = ViewModelProvider(host, factory)[VoiceLinkViewModel::class.java]
                    model.foreground(owner, true)
                    host.setContent {
                        val state by repository.state.collectAsState()
                        VoiceLinkTheme {
                            SpeakPage(state, model.speaking, rememberTestPhraseLibrary(), onSend = {
                                val started = SystemClock.elapsedRealtimeNanos()
                                model.send()
                                val id = checkNotNull(model.speaking.submitted).id
                                check(clicks.trySend(Click(id, started)).isSuccess)
                            }, onConnect = { error("Click must use the ready receiver") })
                        }
                    }
                }
                compose.waitUntil(10_000) { !model.speaking.draftRestoring }
            }
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
            val texts = if (args.getString("nativeText") == "true") listOf("中文 😀 literal {ENTER}") else listOf("SayIn short text", "中文输入测试。", "日本語と한국어", "Emoji 😀 {ENTER}", "One\nTwo")
            repeat(count) { iteration ->
                val state = withTimeout(15000) { ready(second) }
                val scope = checkNotNull(state.receptionScope)
                val text = texts[iteration % texts.size]
                val (id, start) = if (clickThroughUi) {
                    compose.runOnIdle {
                        check(!model.speaking.requestLocked)
                        model.speaking.changeDraft(TextFieldValue(text))
                    }
                    val send = compose.onNodeWithTag("send-draft")
                    send.performTouchInput { click() }
                    withTimeout(5000) { clicks.receive() }
                } else {
                    val id = UUID.randomUUID().toString()
                    val start = SystemClock.elapsedRealtimeNanos()
                    repository.send(text, id, scope)
                    Click(id, start)
                }
                val key = OutboxKey(checkNotNull(scope.hostId), checkNotNull(scope.pairId), id)
                val sample = JSONObject().put("iteration", iteration + 1).put("id", id)
                warm.put(sample)
                val outcome = withTimeoutOrNull(5000) {
                    var terminal: ReceiptTiming
                    do {
                        terminal = receipts.receive()
                        assertEquals(key, terminal.key)
                        check(terminal.received in start..terminal.processing && terminal.processing <= terminal.persisted)
                        if (!sample.has("first_ms")) {
                            sample.put("first_state", terminal.status)
                                .put("first_ms", (terminal.persisted - start) / 1e6)
                                .put("first_received_ms", (terminal.received - start) / 1e6)
                                .put("first_mailbox_ms", (terminal.processing - terminal.received) / 1e6)
                                .put("first_persist_ms", (terminal.persisted - terminal.processing) / 1e6)
                        }
                    } while (terminal.status == "accepted" || terminal.status == "executing")
                    terminal
                }
                if (outcome == null) {
                    sample.put("terminal_timeout", true)
                    error("Missing terminal for text sample ${iteration + 1}")
                }
                var previous = start
                for ((stage, metric) in listOf(
                    "actor" to "send_mailbox_ms",
                    "validated" to "send_validation_ms",
                    "encrypted" to "send_encryption_ms",
                    "quota_checked" to "send_quota_ms",
                    "draft_sealed" to "send_seal_draft_ms",
                    "persisted" to "send_outbox_commit_ms",
                    "submitting" to "send_after_commit_ms",
                    "operation_ready" to "send_prepare_operation_ms",
                    "socket_enqueued" to "send_enqueue_socket_ms",
                )) {
                    val point = textStages.tryReceive().getOrThrow()
                    assertEquals(id, point.id)
                    assertEquals(stage, point.stage)
                    check(point.nanos in previous..outcome.processing)
                    sample.put(metric, (point.nanos - previous) / 1e6)
                    previous = point.nanos
                }
                check(textStages.tryReceive().isFailure)
                val enqueuedMs = (previous - start) / 1e6
                sample.put("send_to_socket_ms", enqueuedMs)
                    .put("socket_to_first_callback_ms", sample.getDouble("first_received_ms") - enqueuedMs)
                    .put("terminal_state", outcome.status)
                    .put("terminal_ms", (outcome.persisted - start) / 1e6)
                assertEquals("injected", outcome.status)
                assertEquals(warmSession, sessions[second])
                if (clickThroughUi) compose.waitUntil(5000) {
                    model.speaking.submitted?.let { it.id == id && it.state == "injected" } == true
                }
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
            evidence.writeText(JSONObject().put("click_through_ui", clickThroughUi)
                .put("warm_text", warm).put("cold_switch", cold).toString(2))
            activity?.close()
            if (!clickThroughUi || activity == null) repository.close()
            delay(250)
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
