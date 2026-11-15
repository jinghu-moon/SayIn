package seeyuer.sayin.data

import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseLibraryTest {
    private val firstId = "11111111-1111-4111-8111-111111111111"
    private val secondId = "22222222-2222-4222-8222-222222222222"

    private class FakeStorage(var stored: PhraseLibraryState? = null) : PhraseStorage {
        var loads = 0
        var saves = 0
        var loadError: Exception? = null
        var saveError: Exception? = null

        override fun load(): PhraseLibraryState? {
            loads++
            loadError?.let { throw it }
            return stored
        }

        override fun save(state: PhraseLibraryState) {
            saveError?.let { throw it }
            stored = state
            saves++
        }
    }

    private class ThreadRecordingStorage : PhraseStorage {
        val threads = mutableListOf<String>()
        var stored: PhraseLibraryState? = null

        override fun load(): PhraseLibraryState? {
            synchronized(threads) { threads += Thread.currentThread().name }
            return stored
        }

        override fun save(state: PhraseLibraryState) {
            synchronized(threads) { threads += Thread.currentThread().name }
            stored = state
        }
    }

    private fun withLibrary(storage: PhraseStorage, block: suspend (PhraseLibrary) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(PhraseLibrary(storage, scope).apply { start() })
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitReady(library: PhraseLibrary) {
        withTimeout(5_000) { library.ready.first { it } }
    }

    private fun validId() = UUID.randomUUID().toString()

    @Test fun validatesProtocolLimitsWithoutTruncatingOrRepairing() {
        assertEquals(PhraseWriteResult.SAVED, PhraseRules.validate("a".repeat(4000)))
        assertEquals(PhraseWriteResult.TOO_LONG, PhraseRules.validate("a".repeat(4001)))
        assertEquals(PhraseWriteResult.SAVED, PhraseRules.validate("😀".repeat(4000)))
        assertEquals(PhraseWriteResult.TOO_LONG, PhraseRules.validate("😀".repeat(4001)))
        assertEquals(PhraseWriteResult.TOO_LONG, PhraseRules.validate("a".repeat(16384)))
        assertEquals(PhraseWriteResult.TOO_LONG, PhraseRules.validate("中".repeat(5462)))
        assertEquals(PhraseWriteResult.SAVED, PhraseRules.validate("第一行\r\nsecond\tline"))
        assertEquals(PhraseWriteResult.SAVED, PhraseRules.validate("e\u0301"))
        for (blank in listOf("", " \r\n\t")) {
            assertEquals(blank, PhraseWriteResult.EMPTY, PhraseRules.validate(blank))
        }
        for (invalid in listOf("bad\u0000", "bad\u007f", "\ud800", "\udc00")) {
            assertEquals(invalid, PhraseWriteResult.INVALID, PhraseRules.validate(invalid))
        }
    }

    @Test fun addsAppendAndRevisionAdvancesOnlyForCommittedMutations() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        assertEquals(PhraseWriteResult.SAVED, library.add("first\r\nline"))
        val body = "x".repeat(4000)
        assertEquals(PhraseWriteResult.SAVED, library.add(body))
        assertEquals(listOf("first\r\nline", body), library.state.value.phrases.map { it.text })
        assertEquals(2L, library.state.value.revision)
        assertEquals(2, library.state.value.phrases.map { it.id }.distinct().size)
    }

    @Test fun publishesOnlyAfterSuccessfulSaveAndAllowsRetry() {
        val storage = FakeStorage()
        withLibrary(storage) { library ->
            awaitReady(library)
            storage.saveError = IOException("disk full")
            assertEquals(PhraseWriteResult.UNAVAILABLE, library.add("new"))
            assertEquals(PhraseLibraryState(), library.state.value)
            assertEquals(0, storage.saves)
            assertEquals(null, storage.stored)
            assertFalse(library.issue.value)
            storage.saveError = null
            assertEquals(PhraseWriteResult.SAVED, library.add("new"))
            assertEquals(listOf("new"), library.state.value.phrases.map { it.text })
            assertEquals(1L, library.state.value.revision)
            assertEquals(1, storage.saves)
        }
    }

    @Test fun rejectsDuplicatesWithLineEndingNormalizationButKeepsExactBodies() {
        val existing = StoredPhrase(firstId, "line1\nline2")
        val storage = FakeStorage(PhraseLibraryState(3, listOf(existing)))
        withLibrary(storage) { library ->
            awaitReady(library)
            assertEquals(PhraseWriteResult.DUPLICATE, library.add("line1\r\nline2"))
            assertEquals(PhraseWriteResult.DUPLICATE, library.add("line1\rline2"))
            assertEquals(PhraseWriteResult.SAVED, library.add("Line1\nline2"))
            assertEquals(PhraseWriteResult.SAVED, library.add("line1\nline2 "))
            assertEquals(
                listOf("line1\nline2", "Line1\nline2", "line1\nline2 "),
                library.state.value.phrases.map { it.text },
            )
            assertEquals(5L, library.state.value.revision)
        }
    }

    @Test fun enforcesHundredItemLimit() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        repeat(100) { index -> assertEquals(PhraseWriteResult.SAVED, library.add("p$index")) }
        assertEquals(100, library.state.value.phrases.size)
        assertEquals(100L, library.state.value.revision)
        assertEquals(PhraseWriteResult.FULL, library.add("overflow"))
        assertEquals(100, library.state.value.phrases.size)
        assertEquals(100L, library.state.value.revision)
    }

    @Test fun updateKeepsIdentityAndPositionAndRejectsMissingOrDuplicate() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        library.add("A")
        library.add("B")
        val (a, b) = library.state.value.phrases
        assertEquals(PhraseWriteResult.SAVED, library.update(b.id, "B2"))
        assertEquals(listOf("A", "B2"), library.state.value.phrases.map { it.text })
        assertEquals(b.id, library.state.value.phrases[1].id)
        assertEquals(3L, library.state.value.revision)
        assertEquals(PhraseWriteResult.DUPLICATE, library.update(a.id, "B2"))
        assertEquals(PhraseWriteResult.CONFLICT, library.update(validId(), "C"))
        assertEquals(PhraseWriteResult.EMPTY, library.update(a.id, "  "))
        assertEquals(listOf("A", "B2"), library.state.value.phrases.map { it.text })
        assertEquals(3L, library.state.value.revision)
    }

    @Test fun deleteAndRestoreRoundTripWithCapturedPosition() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        library.add("A")
        library.add("B")
        library.add("C")
        val deleted = library.state.value.phrases[1]
        assertEquals(PhraseWriteResult.SAVED, library.delete(deleted.id))
        assertEquals(listOf("A", "C"), library.state.value.phrases.map { it.text })
        assertEquals(4L, library.state.value.revision)
        assertEquals(PhraseWriteResult.SAVED, library.restore(deleted, 1))
        assertEquals(listOf("A", "B", "C"), library.state.value.phrases.map { it.text })
        assertEquals(deleted.id, library.state.value.phrases[1].id)
        assertEquals(5L, library.state.value.revision)
        assertEquals(PhraseWriteResult.CONFLICT, library.restore(deleted, 1))
        assertEquals(PhraseWriteResult.DUPLICATE, library.restore(StoredPhrase(validId(), "A"), 0))
        assertEquals(PhraseWriteResult.CONFLICT, library.delete(validId()))
    }

    @Test fun restoreClampsPosition() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        library.add("A")
        library.add("B")
        val deleted = library.state.value.phrases[0]
        library.delete(deleted.id)
        assertEquals(PhraseWriteResult.SAVED, library.restore(deleted, 99))
        assertEquals(listOf("B", "A"), library.state.value.phrases.map { it.text })
    }

    @Test fun reorderRequiresCurrentRevisionAndExactIdSet() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        library.add("A")
        library.add("B")
        library.add("C")
        val ids = library.state.value.phrases.map { it.id }
        assertEquals(PhraseWriteResult.CONFLICT, library.reorder(ids, 2))
        assertEquals(PhraseWriteResult.CONFLICT, library.reorder(ids.take(2), 3))
        assertEquals(PhraseWriteResult.CONFLICT, library.reorder(listOf(ids[0], ids[0], ids[2]), 3))
        assertEquals(PhraseWriteResult.CONFLICT, library.reorder(listOf(ids[0], ids[1], validId()), 3))
        assertEquals(PhraseWriteResult.SAVED, library.reorder(listOf(ids[2], ids[0], ids[1]), 3))
        assertEquals(listOf("C", "A", "B"), library.state.value.phrases.map { it.text })
        assertEquals(4L, library.state.value.revision)
        assertEquals(PhraseWriteResult.CONFLICT, library.reorder(ids, 3))
    }

    @Test fun clearAllRequiresCurrentRevision() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        library.add("A")
        library.add("B")
        assertEquals(PhraseWriteResult.CONFLICT, library.clearAll(1))
        assertEquals(2, library.state.value.phrases.size)
        assertEquals(PhraseWriteResult.SAVED, library.clearAll(2))
        assertEquals(emptyList<StoredPhrase>(), library.state.value.phrases)
        assertEquals(3L, library.state.value.revision)
        assertEquals(PhraseWriteResult.CONFLICT, library.clearAll(2))
    }

    @Test fun corruptLibraryIsReportedAndNeverOverwritten() {
        val sentinel = StoredPhrase(firstId, "keep")
        val storage = FakeStorage(PhraseLibraryState(9, listOf(sentinel))).apply {
            loadError = IOException("keystore unavailable")
        }
        withLibrary(storage) { library ->
            awaitReady(library)
            assertTrue(library.issue.value)
            assertEquals(PhraseLibraryState(), library.state.value)
            assertEquals(PhraseWriteResult.UNAVAILABLE, library.add("new"))
            assertEquals(PhraseWriteResult.UNAVAILABLE, library.clearAll(0))
            assertEquals(0, storage.saves)
            assertEquals(PhraseLibraryState(9, listOf(sentinel)), storage.stored)
        }
    }

    @Test fun startIsIdempotentAndMissingFileLoadsAnEmptyLibrary() {
        val storage = FakeStorage(null)
        withLibrary(storage) { library ->
            library.start()
            awaitReady(library)
            assertFalse(library.issue.value)
            assertEquals(PhraseLibraryState(), library.state.value)
            assertEquals(1, storage.loads)
        }
    }

    @Test fun serializesConcurrentWrites() = withLibrary(FakeStorage()) { library ->
        awaitReady(library)
        val results = coroutineScope {
            (1..32).map { index -> async(Dispatchers.Default) { library.add("p$index") } }.awaitAll()
        }
        assertTrue(results.all { it == PhraseWriteResult.SAVED })
        assertEquals(32, library.state.value.phrases.size)
        assertEquals(32L, library.state.value.revision)
        assertEquals(32, library.state.value.phrases.map { it.id }.distinct().size)
    }

    @Test fun storageWorkNeverRunsOnTheCallerDispatcher() {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "assist-test-main") }
        val caller = callerExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val storage = ThreadRecordingStorage()
        try {
            val library = PhraseLibrary(storage, scope)
            library.start()
            runBlocking {
                withTimeout(5_000) { library.ready.first { it } }
                assertEquals(PhraseWriteResult.SAVED, withContext(caller) { library.add("threaded") })
            }
            assertEquals(listOf("threaded"), library.state.value.phrases.map { it.text })
            assertTrue("storage must have run", storage.threads.isNotEmpty())
            assertTrue(
                "storage thread must not be the caller: ${storage.threads}",
                storage.threads.none { it == "assist-test-main" },
            )
        } finally {
            scope.cancel()
            caller.close()
        }
    }

    @Test fun codecRoundTripsBodiesAndRefusesCorruptStructures() {
        val state = PhraseLibraryState(
            7,
            listOf(StoredPhrase(firstId, "第一行\r\nsecond"), StoredPhrase(secondId, "😀\ttab")),
        )
        assertEquals(state, PhraseCodec.decode(PhraseCodec.encode(state)))

        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.decode(ByteArray(PhraseRules.LIMIT_ENCODED_BYTES + 1))
        }
        assertThrows(Exception::class.java) { PhraseCodec.decode("not json".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { PhraseCodec.encode(state.copy(revision = -1)) }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.encode(state.copy(phrases = state.phrases + StoredPhrase(validId(), "第一行\r\nsecond")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.encode(state.copy(phrases = listOf(StoredPhrase(firstId, " "))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.encode(state.copy(phrases = listOf(StoredPhrase(firstId, "bad\u0000"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.encode(
                state.copy(
                    phrases = (0..100).map {
                        StoredPhrase(it.toString().padStart(8, '0') + "-1234-4234-8234-123456789012", "p$it")
                    },
                ),
            )
        }

        fun encoded() = JSONObject(String(PhraseCodec.encode(state), Charsets.UTF_8))
        val wrongVersion = encoded().put("version", 2)
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.decode(wrongVersion.toString().toByteArray(Charsets.UTF_8))
        }
        val duplicate = encoded().also {
            it.getJSONArray("phrases").put(it.getJSONArray("phrases").getJSONObject(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.decode(duplicate.toString().toByteArray(Charsets.UTF_8))
        }
        val badText = encoded().also {
            it.getJSONArray("phrases").getJSONObject(0).put("text", "bad\u0000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.decode(badText.toString().toByteArray(Charsets.UTF_8))
        }
        val badId = encoded().also {
            it.getJSONArray("phrases").getJSONObject(0).put("id", "not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhraseCodec.decode(badId.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
