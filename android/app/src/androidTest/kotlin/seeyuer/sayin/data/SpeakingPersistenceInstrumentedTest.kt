package seeyuer.sayin.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class SpeakingPersistenceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedSnapshotRoundTripRejectsTampering() {
        val suffix = "-test-${UUID.randomUUID()}"
        val file = File(context.noBackupFilesDir, "speaking$suffix.bin")
        try {
            val store = SpeakingDraftStore(context, suffix)
            assertNull(store.load())
            val id = UUID.randomUUID().toString()
            val value = SavedSpeakingDraft(id, "Private 测试 original", 9, 3, id, "Private 测试 original", "uncertain")
            store.save(value)
            assertEquals(value, SpeakingDraftStore(context, suffix).load())
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(value.text))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.load() }
        } finally {
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sayin.speaking.v1$suffix") }
        }
    }

    @Test fun v1LedgerPreservesRowsAndTerminalStatusKeepsTranscript() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val oldId = UUID.randomUUID().toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS outbox (id TEXT NOT NULL, hostId TEXT NOT NULL, deviceId TEXT NOT NULL, target TEXT NOT NULL, payload BLOB, byteCount INTEGER NOT NULL, state TEXT NOT NULL, `order` TEXT, reason TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO outbox VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(oldId, "host", "device", "target", null, 0, "injected", "1", null, 1L))
                db.version = 1
            }
            val database = Room.databaseBuilder(context, OutboxDatabase::class.java, name)
                .addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY, OutboxDatabase.ADD_V3_RECEIPT_FIELDS).build()
            try {
                val dao = database.outbox()
                val old = dao.find(oldId, "host", "device")!!
                assertEquals("injected", old.state); assertNull(old.transcript)
                val id = UUID.randomUUID().toString()
                val sealed = byteArrayOf(41, 7, 12, 19)
                dao.enqueue(OutboxItem(id, "host", "device", "target", byteArrayOf(1, 2), 2, "saved", null, null, 2L, sealed))
                dao.status(id, "host", "device", "uncertain", "2", null, true)
                val terminal = dao.find(id, "host", "device")!!
                assertNull(terminal.payload); assertEquals(0, terminal.byteCount); assertArrayEquals(sealed, terminal.transcript)
                assertEquals("uncertain", terminal.state)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
