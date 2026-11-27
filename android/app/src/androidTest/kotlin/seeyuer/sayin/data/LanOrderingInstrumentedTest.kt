package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.MobileHistoryPage
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File
import java.util.UUID

class TwoPhonesInstrumentedTest {
    @Test fun twoPhones() = runLanCase("two-phones") { repository, queried ->
        val args = InstrumentationRegistry.getArguments()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val role = checkNotNull(args.getString("phoneRole"))
        val id = checkNotNull(args.getString("sharedId"))
        val scope = checkNotNull(repository.state.value.receptionScope)
        val host = checkNotNull(scope.hostId)
        val pair = checkNotNull(scope.pairId)
        val key = OutboxKey(host, pair, id)
        val nativeText = "中文 😀 literal {ENTER}"
        val text = if (role == "first") "fixture: hold without input" else nativeText
        val outcome = if (role == "first") "held" else "injected"
        repository.send(text, id)
        suspend fun verify(expected: String, request: OutboxKey, body: String) {
            val state = repository.awaitLan { value ->
                value.history.any { it.key == request && it.state == expected } && value.historyText[request] == body
            }
            assertTrue(state.history.all { it.deviceId == pair })
        }
        verify(outcome, key, text)
        repository.action(key, "query")
        withTimeout(10_000) { queried.receive() }
        verify(outcome, key, text)
        android.util.Log.i("SayInLanTest", "$role verified $outcome and query")
        val signal = File(target.filesDir, checkNotNull(args.getString("phaseFile")))
        withTimeout(30_000) { while (!signal.exists()) delay(75) }
        android.util.Log.i("SayInLanTest", "$role received phase signal")
        if (role == "first") {
            verify("held", key, text)
            repository.action(key, "cancel")
            verify("cancelled", key, text)
        } else {
            val secondId = UUID.randomUUID().toString()
            repository.send(nativeText, secondId)
            verify("injected", OutboxKey(host, pair, secondId), nativeText)
        }
        JSONObject().put("role", role).put("pair", pair).put("shared_id", id)
            .put("query_completed", true).put("initial_state", outcome)
    }
}

class LanReceiptInstrumentedTest {
    @Test fun receiptBudget() = runLanCase("receipt-budget") { repository, queried ->
        val results = JSONArray()
        repeat(2) { index ->
            val id = UUID.randomUUID().toString()
            repository.send("receipt budget fixture", id)
            val state = repository.awaitLan { it.history.any { row -> row.id == id && row.state == "failed" } }
            val row = state.history.single { it.id == id }
            assertEquals("2", row.requestRev)
            assertEquals("prepared", row.executionStage)
            assertEquals("unicode", row.method)
            assertEquals((index + 1).toString(), row.order)
            val app = checkNotNull(state.historyApplications[row.key])
            assertEquals(96, app.name.codePointCount(0, app.name.length))
            if (index == 0) assertNotNull(app.icon)
            val saved = checkNotNull(row.reason)
            assertTrue(saved.all { it == 'x' })
            repository.action(row.key, "query")
            withTimeout(10_000) { queried.receive() }
            val current = repository.state.value
            assertTrue(current.ready)
            val queriedRow = current.history.single { it.id == id }
            assertEquals(row.requestRev, queriedRow.requestRev)
            assertEquals(row.state, queriedRow.state)
            assertEquals(saved, queriedRow.reason)
            results.put(JSONObject().put("id", id).put("reason_bytes", saved.length)
                .put("stored_icon", app.icon != null).put("query_completed", true))
        }
        JSONObject().put("receipts", results)
    }

    @Test fun pausedQueue() = runLanCase("paused-queue") { repository, _ ->
        val ids = List(3) { UUID.randomUUID().toString() }
        for ((index, id) in ids.withIndex()) {
            repository.send("paused queue request ${index + 1}", id)
            repository.awaitLan { state -> state.history.any { it.id == id && it.state == "accepted" } }
        }
        val held = repository.awaitLan {
            it.paused && it.pauseReason == "paused" && it.history.count { row -> row.id in ids && row.state == "held" } == 3
        }
        val orders = ids.map { id -> checkNotNull(held.history.single { it.id == id }.order) }
        assertEquals(listOf("1", "2", "3"), orders)
        assertTrue(held.history.filter { it.id in ids }.all { it.reason == "paused" })
        repository.awaitLan { it.ready }
        repository.action(held.history.single { it.id == ids[1] }.key, "release")
        repository.awaitLan { state ->
            state.history.any { it.id == ids[1] && it.state == "accepted" } &&
                state.history.any { it.id == ids[0] && it.state == "held" }
        }
        // Wait for the new control revision before releasing another request.
        repository.awaitLan { it.ready && it.revision != held.revision &&
            it.history.any { row -> row.id == ids[1] && row.state == "accepted" } }
        // Give the desktop verifier time to observe the blocked queue.
        kotlinx.coroutines.delay(1_500)
        val blocked = repository.state.value
        assertEquals("held", blocked.history.single { it.id == ids[0] }.state)
        assertEquals("accepted", blocked.history.single { it.id == ids[1] }.state)
        repository.action(held.history.single { it.id == ids[0] }.key, "release")
        val terminal = repository.awaitLan { state ->
            state.history.count { it.id in ids.take(2) && it.state == "injected" } == 2 &&
                state.history.any { it.id == ids.last() && it.state == "expired" }
        }
        assertEquals("hold_expired", terminal.history.single { it.id == ids.last() }.reason)
        assertEquals(orders, ids.map { id -> terminal.history.single { it.id == id }.order })
        JSONObject().put("ids", JSONArray(ids)).put("orders", JSONArray(orders))
    }
}

class LanOrderingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun queuedTextAfterNativeImageAndOlderReceiptAfterTerminal() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("lanOrdering") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val address = checkNotNull(args.getString("fixtureAddress"))
        check(address != "127.0.0.1")
        val namespace = "lan-ordering-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val queried = Channel<String>(4)
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() },
            queryCompleted = { check(queried.trySend(it).isSuccess) })
        fun persisted(id: String): List<String?> =
            SQLiteDatabase.openDatabase(context.getDatabasePath("outbox.db").path, null,
                SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery(
                    "SELECT state, requestRev, `order`, method, executionStage, confirmed, reason FROM outbox WHERE id=?",
                    arrayOf(id),
                ).use { row ->
                    check(row.moveToFirst() && row.count == 1)
                    (0 until row.columnCount).map { row.getString(it) }
                }
            }
        suspend fun waitFor(timeout: Long = 15_000, condition: (ClientState) -> Boolean) =
            withTimeout(timeout) { repository.state.first { check(it.storageHealthy) { "LAN repository storage failed (connection=${it.connection}, paired=${it.paired})" }; condition(it) } }
        try {
            compose.setContent {
                val state by repository.state.collectAsState()
                VoiceLinkTheme {
                    Scaffold { MobileHistoryPage(state, action = repository::action) }
                }
            }
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            repository.pairCode(address, checkNotNull(args.getString("fixturePort")),
                checkNotNull(args.getString("fixtureCode")))
            waitFor { it.ready && it.imageSupported }
            while (queried.tryReceive().isSuccess) { /* Discard pairing recovery queries. */ }
            val source = File(target.getExternalFilesDir(null), checkNotNull(args.getString("imageSample")))
            repository.selectImage(FileProvider.getUriForFile(target, "${target.packageName}.fixtures", source))
            val resource = checkNotNull(waitFor { !it.imagePreparing && it.imageDraft != null }.imageDraft).image.resourceId
            repository.sendImage(resource)
            val running = waitFor { state ->
                state.history.any { it.imageResource == resource && it.state == "executing" }
            }
            val image = running.history.single { it.imageResource == resource }
            val text = "中文 😀 literal {ENTER}"
            val id = UUID.randomUUID().toString()
            repository.send(text, id, running.receptionScope)
            val queued = waitFor { state ->
                state.history.any { it.id == id && it.state == "accepted" }
            }
            assertEquals("executing", queued.history.single { it.id == image.id }.state)
            val accepted = persisted(id)
            assertEquals("accepted", accepted[0])
            val completed = waitFor(70_000) { state ->
                state.history.count { it.state == "injected" } == 2
            }
            val imageReceipt = completed.history.single { it.id == image.id }
            val textReceipt = completed.history.single { it.id == id }
            assertEquals("clipboard_image", imageReceipt.method)
            assertEquals("paste_submitted", imageReceipt.executionStage)
            assertEquals("unicode", textReceipt.method)
            assertEquals("input_submitted", textReceipt.executionStage)
            assertTrue(checkNotNull(imageReceipt.order).toLong() < checkNotNull(textReceipt.order).toLong())
            val before = persisted(id)
            assertEquals("injected", before[0])
            assertTrue(checkNotNull(before[1]).toLong() > checkNotNull(accepted[1]).toLong())
            val submitted = target.getString(R.string.receipt_injected)
            val imageSubmitted = target.getString(R.string.history_image_submitted)
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithText(submitted).assertIsDisplayed()
            compose.onNodeWithText(imageSubmitted).assertIsDisplayed()

            repository.action(textReceipt.key, "query")
            withTimeout(10_000) { queried.receive() }
            assertEquals(before, persisted(id))
            assertEquals("injected", repository.state.value.history.single { it.id == id }.state)
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithText(submitted).assertIsDisplayed()
            compose.onNodeWithText(imageSubmitted).assertIsDisplayed()
            compose.onNodeWithText(target.getString(R.string.receipt_accepted)).assertDoesNotExist()

            File(target.getExternalFilesDir(null), "${source.nameWithoutExtension}-result.json").writeText(
                JSONObject().put("image_request", image.id).put("text_request", id)
                    .put("accepted_revision", accepted[1]).put("terminal_revision", before[1])
                    .put("terminal_state", before[0]).put("old_query_processed", true)
                    .put("room_unchanged", true).put("history_unchanged", true).toString(2),
            )
        } finally {
            repository.close()
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
            File(target.noBackupFilesDir, "images/${namespace}outbox").deleteRecursively()
        }
    }
}

private suspend fun VoiceLinkRepository.awaitLan(condition: (ClientState) -> Boolean) =
    withTimeout(18_000) { state.first { check(it.storageHealthy) { "LAN repository storage failed (connection=${it.connection}, paired=${it.paired})" }; condition(it) } }

private fun runLanCase(name: String, exercise: suspend (VoiceLinkRepository, Channel<String>) -> JSONObject) = runBlocking {
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("lanScenario") == name)
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    check(target.packageName == "seeyuer.sayin.imagetest")
    val address = checkNotNull(args.getString("fixtureAddress"))
    check(!address.startsWith("127."))
    val namespace = "$name-${UUID.randomUUID()}-"
    val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
        override fun getSharedPreferences(name: String, mode: Int) =
            target.getSharedPreferences(namespace + name, mode)
    }
    val queried = Channel<String>(8)
    val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() },
        draftStore = SpeakingDraftStore(context, namespace),
        textTiming = { _, stage, _ -> android.util.Log.i("SayInLanTest", "$name text $stage") },
        queryCompleted = { check(queried.trySend(it).isSuccess) })
    try {
        repository.awaitLan { it.connection == Connection.OFFLINE }
        repository.foreground(true)
        repository.pairCode(address, checkNotNull(args.getString("fixturePort")), checkNotNull(args.getString("fixtureCode")))
        try {
            repository.awaitLan { it.ready }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val state = repository.state.value
            throw AssertionError("LAN pairing did not become ready: connection=${state.connection}, issue=${state.issue}, paired=${state.paired}, active=${state.active}, paused=${state.paused}", error)
        }
        while (queried.tryReceive().isSuccess) { /* Discard pairing recovery queries. */ }
        val report = exercise(repository, queried)
        File(target.getExternalFilesDir(null), checkNotNull(args.getString("resultFile"))).writeText(report.toString(2))
    } finally {
        repository.close()
        target.deleteDatabase(namespace + "outbox.db")
        target.deleteSharedPreferences(namespace + "paired_host")
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("sayin.speaking.v1$namespace")
    }
}
