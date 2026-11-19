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
    @Test fun hidingHistoryKeepsPendingLedgerAndImmutableExecutionDisplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        try {
            val dao = database.outbox()
            val item = OutboxItem(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "scope", byteArrayOf(1, 2), 2, "held", "1", "paused", 1, byteArrayOf(3))
            dao.enqueue(item)
            dao.hide(item.id)
            assertNull(dao.visible(item.id))
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
            dao.hideAll(listOf(old.id, middle.id))
            assertNull(dao.visible(old.id))
            assertNull(dao.visible(middle.id))
            assertEquals(recent.id, dao.visible(recent.id)!!.id)
            assertEquals(3, dao.pending(host, device).size)
            assertEquals(1, dao.hideBefore(3_500))
            assertNull(dao.visible(recent.id))
            assertEquals(3, dao.pending(host, device).size)
            assertEquals(3, dao.pendingCount())
        } finally { database.close() }
    }
}
