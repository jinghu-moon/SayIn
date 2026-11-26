package seeyuer.sayin.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxInstrumentedTest {
    @Test fun identicalRequestIdsInTwoPairsKeepIndependentReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val first = OutboxItem(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "first-target", byteArrayOf(1), 1, "saved", null, null, 1)
            val second = first.copy(deviceId = UUID.randomUUID().toString(),
                target = "second-target", payload = byteArrayOf(2))
            dao.enqueue(first)
            dao.enqueue(second)
            dao.status(first.id, first.hostId, first.deviceId, "injected", "1", null, true)
            assertEquals("injected", dao.find(first.id, first.hostId, first.deviceId)!!.state)
            assertArrayEquals(byteArrayOf(2), dao.pending(second.hostId, second.deviceId).single().payload)
            dao.hide(first.key)
            assertNull(dao.visible(first.key))
            assertEquals("saved", dao.visible(second.key)?.state)
            dao.status(second.id, second.hostId, second.deviceId, "cancelled", "2", null, true)
            assertEquals("cancelled", dao.visible(second.key)?.state)
            assertEquals("injected", dao.find(first.id, first.hostId, first.deviceId)?.state)
        } finally { database.close() }
    }

    @Test fun migratedReceiptDetailsPreserveLegacyDataAndRequireMatchingScopeAndOutcome() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            for (order in listOf(null, "1")) {
                val original = OutboxItem(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "scope", null, 0, "expired", order, "original_reason", 1, byteArrayOf(3), historyHidden = true)
                dao.enqueue(original)
                suspend fun update(host: String = original.hostId, state: String = original.state, revision: String = "0") {
                    dao.migratedReceiptDetails(original.id, host, original.deviceId, state, order,
                        null, revision, null, null, false)
                }
                update(host = UUID.randomUUID().toString())
                update(state = "injected")
                assertNull(dao.find(original.id, original.hostId, original.deviceId)!!.requestRev)
                update()
                val migrated = dao.find(original.id, original.hostId, original.deviceId)!!
                assertEquals("0", migrated.requestRev)
                assertEquals(original.copy(transcript = null), migrated.copy(requestRev = null, transcript = null))
                assertArrayEquals(original.transcript, migrated.transcript)
                update(revision = "1")
                assertEquals("0", dao.find(original.id, original.hostId, original.deviceId)!!.requestRev)
            }
        } finally { database.close() }
    }

    @Test fun hidingHistoryKeepsPendingLedgerAndImmutableExecutionDisplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val item = OutboxItem(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "scope", byteArrayOf(1, 2), 2, "held", "1", "paused", 1, byteArrayOf(3))
            dao.enqueue(item)
            dao.hide(item.key)
            assertNull(dao.visible(item.key))
            val pending = dao.pending(item.hostId, item.deviceId).single()
            assertEquals(item.id, pending.id)
            assertEquals("held", pending.state)
            assertArrayEquals(item.payload, pending.payload)
            assertEquals(1, dao.pendingCount())
            dao.application(item.id, "other-host", item.deviceId, byteArrayOf(8))
            assertNull(dao.find(item.id, item.hostId, item.deviceId)!!.application)
            dao.application(item.id, item.hostId, item.deviceId, byteArrayOf(4))
            dao.application(item.id, item.hostId, item.deviceId, byteArrayOf(5))
            dao.status(item.id, item.hostId, item.deviceId, "uncertain", "1", "check", true)
            val terminal = dao.find(item.id, item.hostId, item.deviceId)!!
            assertTrue(terminal.historyHidden)
            assertEquals("uncertain", terminal.state)
            assertArrayEquals(byteArrayOf(4), terminal.application)
            assertArrayEquals(byteArrayOf(3), terminal.transcript)
            assertNull(terminal.payload)
        } finally { database.close() }
    }
    @Test fun quotaAndAuthorizationScopeAndTerminalCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val host = UUID.randomUUID().toString()
            val device = UUID.randomUUID().toString()
            val id = UUID.randomUUID().toString()
            dao.enqueue(OutboxItem(id, host, device, "target", byteArrayOf(1, 2), 2, "saved", null, null, 1))
            assertNull(dao.find(id, host, UUID.randomUUID().toString()))
            assertEquals(1, dao.pending(host, device).size)
            assertEquals(0, dao.pending(host, UUID.randomUUID().toString()).size)
            dao.status(id, host, device, "injected", "1", null, true)
            assertNull(dao.find(id, host, device)!!.payload)
            assertEquals(0, dao.pendingBytes())
            repeat(16) { index ->
                dao.enqueue(OutboxItem(UUID.randomUUID().toString(), host, device, "target", byteArrayOf(1), 4096, "saved", null, null, index.toLong()))
            }
            assertEquals(16, dao.pendingCount())
            assertEquals(65536, dao.pendingBytes())
            try {
                dao.enqueue(OutboxItem(UUID.randomUUID().toString(), host, device, "target", byteArrayOf(1), 1, "saved", null, null, 20))
                fail("Over-quota request must not be inserted")
            } catch (_: IllegalStateException) { /* expected transaction refusal */ }
            assertEquals(16, dao.pendingCount())
        } finally { database.close() }
    }

    @Test fun bulkHideAndAgeCleanupOnlyChangeHistoryPresentation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val host = UUID.randomUUID().toString()
            val device = UUID.randomUUID().toString()
            val old = OutboxItem(UUID.randomUUID().toString(), host, device, "scope", byteArrayOf(1), 1, "held", "1", "paused", 1_000, byteArrayOf(2))
            val middle = OutboxItem(UUID.randomUUID().toString(), host, device, "scope", byteArrayOf(1), 1, "accepted", "2", null, 2_000, byteArrayOf(2))
            val recent = OutboxItem(UUID.randomUUID().toString(), host, device, "scope", byteArrayOf(1), 1, "held", "3", "paused", 3_000, byteArrayOf(2))
            listOf(old, middle, recent).forEach { dao.enqueue(it) }
            dao.hideAll(listOf(old.key, middle.key))
            assertNull(dao.visible(old.key))
            assertNull(dao.visible(middle.key))
            assertEquals(recent.id, dao.visible(recent.key)!!.id)
            assertEquals(3, dao.pending(host, device).size)
            assertEquals(1, dao.hideBefore(3_500))
            assertNull(dao.visible(recent.key))
            assertEquals(3, dao.pending(host, device).size)
            assertEquals(3, dao.pendingCount())
        } finally { database.close() }
    }

    @Test fun pendingQuotasAreIsolatedByPairAndThenBoundedPerHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val host = UUID.randomUUID().toString()
            val firstPair = UUID.randomUUID().toString()
            val secondPair = UUID.randomUUID().toString()
            repeat(16) { index ->
                dao.enqueue(OutboxItem(UUID.randomUUID().toString(), host, firstPair, "target", byteArrayOf(1), 4_096,
                    "saved", null, null, index.toLong()))
            }
            repeat(16) { index ->
                dao.enqueue(OutboxItem(UUID.randomUUID().toString(), host, secondPair, "target", byteArrayOf(1), 4_096,
                    "saved", null, null, (100 + index).toLong()))
            }
            assertEquals(16, dao.pendingCountFor(host, firstPair))
            assertEquals(16, dao.pendingCountFor(host, secondPair))
            assertEquals(32, dao.pendingCountForHost(host))
            assertEquals(131_072, dao.pendingBytesForHost(host))
            try {
                dao.enqueue(OutboxItem(UUID.randomUUID().toString(), host, secondPair, "target", byteArrayOf(1), 1,
                    "saved", null, null, 200))
                fail("Host quota must reject the 33rd pending request")
            } catch (_: IllegalStateException) { }
            assertEquals(32, dao.pendingCountForHost(host))
        } finally { database.close() }
    }

    @Test fun imageQuotasAllowOnePerPairAndTwoPerHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val host = UUID.randomUUID().toString()
            val pairA = UUID.randomUUID().toString()
            val pairB = UUID.randomUUID().toString()
            fun image(pair: String, createdAt: Long) = OutboxItem(UUID.randomUUID().toString(), host, pair, "target", byteArrayOf(9), 0,
                "uploading", null, null, createdAt, kind = "image", imageResource = UUID.randomUUID().toString(),
                imageReference = byteArrayOf(1), imageByteCount = 1_000_000)
            dao.enqueue(image(pairA, 1))
            dao.enqueue(image(pairB, 2))
            dao.insert(OutboxItem(UUID.randomUUID().toString(), host, pairB, "target", byteArrayOf(7), 1, "saved", null, null, 0,
                kind = "text", imageByteCount = 9))
            assertEquals(1, dao.pendingImagesFor(host, pairA))
            assertEquals(2, dao.pendingImagesForHost(host))
            assertEquals(2_000_000, dao.pendingImageBytesForHost(host))
            try {
                dao.enqueue(image(pairA, 3))
                fail("A pair must have at most one pending image")
            } catch (_: IllegalStateException) { }
            val pairC = UUID.randomUUID().toString()
            try {
                dao.enqueue(image(pairC, 4))
                fail("A host must have at most two pending images")
            } catch (_: IllegalStateException) { }
            val otherHostImage = image(UUID.randomUUID().toString(), 5).copy(hostId = UUID.randomUUID().toString())
            dao.enqueue(otherHostImage)
            assertEquals(1, dao.pendingImagesForHost(otherHostImage.hostId))
        } finally { database.close() }
    }
}
