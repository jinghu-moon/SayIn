package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
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

    @Test fun legacyEncryptedDraftImportsIntoRoomAndTamperingFailsClosed() = runBlocking {
        val suffix = "-test-${UUID.randomUUID()}"
        val target = context
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath("$suffix-$name")
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("$suffix-$name", mode)
        }
        val file = File(target.noBackupFilesDir, "speaking$suffix.bin")
        val store = SpeakingDraftStore(isolated, suffix)
        val repository = VoiceLinkRepository(isolated, HostDiscovery { _, _ -> emptyList() }, draftStore = store)
        try {
            val id = UUID.randomUUID().toString()
            val value = SavedSpeakingDraft(id, "Private 测试 original", 9, 3, id, "Private 测试 original", "uncertain")
            val ciphertext = store.seal(value, createKey = true)
            file.writeBytes(ciphertext)
            assertEquals(value, repository.loadSpeakingDraft())
            assertFalse(file.exists())
            assertEquals(value, repository.loadSpeakingDraft())
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains(value.text))

            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            SQLiteDatabase.openDatabase(isolated.getDatabasePath("outbox.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE speaking_draft SET ciphertext=? WHERE id=1", arrayOf(ciphertext))
            }
            assertTrue(runCatching { repository.loadSpeakingDraft() }.isFailure)
            assertFalse(repository.state.value.storageHealthy)
        } finally {
            repository.close()
            target.deleteDatabase("$suffix-outbox.db")
            file.delete()
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
                .addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY, OutboxDatabase.ADD_V3_RECEIPT_FIELDS, OutboxDatabase.SCOPE_REQUEST_KEYS).build()
            try {
                val dao = database.outbox()
                val old = dao.find(oldId, "host", "device")!!
                assertEquals("injected", old.state); assertNull(old.transcript)
                val id = oldId
                val newPair = "second-device"
                val sealed = byteArrayOf(41, 7, 12, 19)
                val item = OutboxItem(id, "host", newPair, "target", byteArrayOf(1, 2), 2, "saved", null, null, 2L, sealed)
                val previousDraft = byteArrayOf(7)
                dao.saveSpeakingDraft(SpeakingDraftEntity(previousDraft))
                database.openHelper.writableDatabase.execSQL("""
                    CREATE TRIGGER reject_speaking_draft BEFORE INSERT ON speaking_draft
                    BEGIN SELECT RAISE(ABORT, 'draft write failed'); END
                """.trimIndent())
                val failure = runCatching { dao.enqueueSpeaking(item, SpeakingDraftEntity(sealed)) }.exceptionOrNull()
                assertTrue(failure is SQLiteConstraintException)
                assertNull(dao.find(id, "host", newPair))
                assertArrayEquals(previousDraft, dao.speakingDraft())
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_speaking_draft")
                dao.enqueueSpeaking(item, SpeakingDraftEntity(sealed))
                assertArrayEquals(sealed, dao.speakingDraft())
                dao.status(id, "host", newPair, "uncertain", "2", null, true)
                val terminal = dao.find(id, "host", newPair)!!
                assertNull(terminal.payload); assertEquals(0, terminal.byteCount); assertArrayEquals(sealed, terminal.transcript)
                assertEquals("uncertain", terminal.state)
                assertEquals("injected", dao.find(oldId, "host", "device")?.state)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
