package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeInstrumentedTest {
    @Test fun opensV2DataAndQueriesOriginalPairWithoutReplayingRequests() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val arguments = InstrumentationRegistry.getArguments()
        val port = checkNotNull(arguments.getString("upgradePort"))
        val withImage = arguments.getString("upgradeImage") == "true"
        val receiptCount = if (withImage) 4 else 3
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath("v2-upgrade-$name")
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("v2-upgrade-$name", mode)
        }
        check(context.getDatabasePath("outbox.db").isFile)
        val draftStore = SpeakingDraftStore(context, "-v2-upgrade")
        val draft = draftStore.open(checkNotNull(draftStore.readLegacy()))
        assertEquals("fixture draft", draft.text)

        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() }, draftStore = draftStore)
        try {
            suspend fun await(stage: String, predicate: (ClientState) -> Boolean): ClientState {
                try {
                    return withTimeout(15_000) {
                        repository.state.first {
                            check(it.storageHealthy && it.issue == null) { "Upgrade client failed at $stage: ${it.issue}" }
                            predicate(it)
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    val state = repository.state.value
                    throw AssertionError("Upgrade timed out at $stage: paired=${state.paired}, " +
                        "rows=${state.history.size}, decrypted=${state.historyText.size}, " +
                        "connection=${state.connection}", timeout)
                }
            }

            val before = await("load retained data") { it.paired && it.history.size == receiptCount && it.historyText.size == 3 }
            val identity = before.pairedTargets.single()
            assertEquals(identity.hostId, before.selectedHostId)
            assertTrue(before.history.all { it.hostId == identity.hostId && it.deviceId == identity.pairId })
            assertEquals(setOf("expired", "injected", "uncertain"), before.history.map { it.state }.toSet())
            val injected = before.history.single { it.kind == "text" && it.state == "injected" }
            if (withImage) {
                val image = before.history.single { it.kind == "image" }
                assertEquals("injected", image.state)
                val metadata = checkNotNull(before.historyImages[image.key])
                assertEquals(32, metadata.width)
                assertEquals(32, metadata.height)
                val bitmap = checkNotNull(repository.historyImage(image.key, 256))
                try { assertEquals(0xff2277aa.toInt(), bitmap.getPixel(16, 16)) }
                finally { bitmap.recycle() }
            }
            val uncertain = before.history.single { it.state == "uncertain" }
            assertEquals("中文 😀 {ENTER}", before.historyText[injected.key])
            assertEquals("Fixture Editor", before.historyApplications[injected.key]?.name)
            assertTrue(uncertain.historyHidden)

            // Replace the ephemeral loopback route only. Authenticating on the
            // upgraded server proves that the original certificate and token work.
            repository.updateAddress("127.0.0.1", port)
            await("update route") { it.port.toString() == port }
            repository.foreground(true)
            val online = await("authenticate original pair") { it.connection == Connection.ONLINE && !it.recovering }
            assertEquals("outcome_review_required", online.pauseReason)
            assertFalse(online.ready)
            assertEquals(identity.copy(port = port.toInt()), online.pairedTargets.single())

            before.history.forEach { repository.action(it.key, "query") }
            val after = await("query original receipts") { state -> state.history.size == receiptCount && state.history.all { it.requestRev == "0" } }
            assertEquals(before.history.associate { it.id to it.state }, after.history.associate { it.id to it.state })
            assertEquals(before.history.associate { it.id to it.order }, after.history.associate { it.id to it.order })
            assertEquals(before.historyText, after.historyText)
            assertEquals(before.historyImages, after.historyImages)
            assertNull(after.history.single { it.id == injected.id }.executionStage)
            assertEquals("global", after.history.single { it.id == uncertain.id }.uncertainScope)
            assertTrue(after.history.single { it.id == uncertain.id }.historyHidden)
            assertTrue(after.paused)
            assertEquals(draft, repository.loadSpeakingDraft())
            assertNull(draftStore.readLegacy())
        } finally {
            repository.close()
        }
    }
}
