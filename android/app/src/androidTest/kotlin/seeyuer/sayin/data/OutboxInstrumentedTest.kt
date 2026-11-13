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
}
