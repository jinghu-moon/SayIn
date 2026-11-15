package seeyuer.sayin.data

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A phrase keeps the user's exact body; only its identity and list position are metadata. */
internal data class StoredPhrase(val id: String, val text: String)

/** Revision is the persisted optimistic-concurrency token for reorder and clear-all. */
internal data class PhraseLibraryState(
    val revision: Long = 0,
    val phrases: List<StoredPhrase> = emptyList(),
)

internal enum class PhraseWriteResult { SAVED, EMPTY, INVALID, DUPLICATE, TOO_LONG, FULL, CONFLICT, UNAVAILABLE }

/** Synchronous, atomic persistence; [save] must either replace the whole library or throw. */
internal interface PhraseStorage {
    fun load(): PhraseLibraryState?
    fun save(state: PhraseLibraryState)
}

/**
 * Limits and validation shared by the editor, the library and the clipboard adapter.
 *
 * Phrase bodies keep newlines and tabs. The limits are the same budget the protocol applies after
 * CRLF/CR/LF/Tab are normalized to spaces for sending, so every stored phrase can be inserted into
 * an empty draft without a later silent refusal. A body may legitimately contain CR, LF and Tab;
 * those are preserved exactly and are normalized only when the protocol validates a send.
 *
 * The 16384-byte limit is a defensive bound: with at most 4000 code points the maximum normalized
 * UTF-8 size is 16000 bytes (4 bytes per code point), so the code-point cap always binds first.
 * The byte rule is still enforced here and in [ClipboardText] so a future limit change cannot
 * silently admit oversized text.
 *
 * Invalid Unicode and C0/DEL control characters (other than CR, LF, Tab) are [PhraseWriteResult.INVALID];
 * a blank body is [PhraseWriteResult.EMPTY]. The body is never truncated, stripped or repaired.
 */
internal object PhraseRules {
    const val LIMIT_ITEMS = 100
    const val LIMIT_CODE_POINTS = 4_000
    const val LIMIT_UTF8_BYTES = 16_384
    const val LIMIT_ENCODED_BYTES = 2 * 1024 * 1024

    fun validate(text: String): PhraseWriteResult {
        if (text.isBlank()) return PhraseWriteResult.EMPTY
        return try {
            normalizedText(text)
            PhraseWriteResult.SAVED
        } catch (error: IllegalArgumentException) {
            when (rejectionReason(text)) {
                ClipboardInvalidReason.TOO_MANY_CODE_POINTS,
                ClipboardInvalidReason.TOO_MANY_BYTES,
                -> PhraseWriteResult.TOO_LONG
                ClipboardInvalidReason.UNSUPPORTED_CHARACTERS,
                ClipboardInvalidReason.MALFORMED_UNICODE,
                -> PhraseWriteResult.INVALID
            }
        }
    }

    /**
     * Names why the protocol validator rejects [text], or null when it accepts the text. Acceptance
     * itself always comes from [normalizedText], so this cannot drift into a second validator; the
     * checks below only classify the already-known rejection, matching the same limits and
     * Unicode/control rules.
     */
    fun invalidReason(text: String): ClipboardInvalidReason? = try {
        normalizedText(text)
        null
    } catch (error: IllegalArgumentException) {
        rejectionReason(text)
    }

    /** CRLF/CR are one logical line break for duplicate detection; all other characters stay exact. */
    fun duplicateKey(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun rejectionReason(text: String): ClipboardInvalidReason {
        if (!wellFormedUnicode(text)) return ClipboardInvalidReason.MALFORMED_UNICODE
        val normalized = normalizeForSend(text)
        if (normalized.any { it.code < 32 || it.code == 127 }) {
            return ClipboardInvalidReason.UNSUPPORTED_CHARACTERS
        }
        // Check bytes before code points so the byte budget has a reachable, user-facing reason;
        // when both are exceeded either rejection is valid, and 3-byte scripts make this the
        // budget an oversized body actually hits.
        if (normalized.toByteArray(Charsets.UTF_8).size > LIMIT_UTF8_BYTES) {
            return ClipboardInvalidReason.TOO_MANY_BYTES
        }
        if (normalized.codePointCount(0, normalized.length) > LIMIT_CODE_POINTS) {
            return ClipboardInvalidReason.TOO_MANY_CODE_POINTS
        }
        // normalizedText only has the checks above plus the blank rejection handled by callers.
        return ClipboardInvalidReason.UNSUPPORTED_CHARACTERS
    }

    private fun wellFormedUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val unit = value[index++]
            if (unit.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            } else if (unit.isLowSurrogate()) {
                return false
            }
        }
        return true
    }

    private fun normalizeForSend(text: String): String =
        text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
}

/**
 * Versioned JSON codec. [decode] is strict so a corrupt, truncated or structurally invalid library
 * is reported instead of being mistaken for an empty user library.
 */
internal object PhraseCodec {
    private const val VERSION = 1

    fun encode(state: PhraseLibraryState): ByteArray {
        require(state.revision >= 0)
        require(state.phrases.size <= PhraseRules.LIMIT_ITEMS)
        val phrases = JSONArray()
        val ids = HashSet<String>(state.phrases.size)
        val texts = HashSet<String>(state.phrases.size)
        for (phrase in state.phrases) {
            require(PhraseRules.validate(phrase.text) == PhraseWriteResult.SAVED)
            require(ids.add(validId(phrase.id)))
            require(texts.add(PhraseRules.duplicateKey(phrase.text)))
            phrases.put(JSONObject().put("id", phrase.id).put("text", phrase.text))
        }
        val bytes = JSONObject()
            .put("version", VERSION)
            .put("revision", state.revision)
            .put("phrases", phrases)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= PhraseRules.LIMIT_ENCODED_BYTES) { "Phrase library exceeds its storage budget" }
        return bytes
    }

    fun decode(bytes: ByteArray): PhraseLibraryState {
        require(bytes.size <= PhraseRules.LIMIT_ENCODED_BYTES) { "Phrase library exceeds its storage budget" }
        val value = JSONObject(String(bytes, Charsets.UTF_8))
        require(value.getInt("version") == VERSION)
        require(value.has("revision") && value.has("phrases"))
        val revision = value.getLong("revision")
        require(revision >= 0)
        val encodedPhrases = value.getJSONArray("phrases")
        require(encodedPhrases.length() <= PhraseRules.LIMIT_ITEMS)
        val phrases = ArrayList<StoredPhrase>(encodedPhrases.length())
        val ids = HashSet<String>(encodedPhrases.length())
        val texts = HashSet<String>(encodedPhrases.length())
        for (index in 0 until encodedPhrases.length()) {
            val encoded = encodedPhrases.getJSONObject(index)
            val id = validId(encoded.getString("id"))
            val text = encoded.getString("text")
            require(PhraseRules.validate(text) == PhraseWriteResult.SAVED)
            require(ids.add(id)) { "Duplicate phrase identity" }
            require(texts.add(PhraseRules.duplicateKey(text))) { "Duplicate phrase body" }
            phrases += StoredPhrase(id, text)
        }
        return PhraseLibraryState(revision, phrases)
    }
}

/**
 * Single owner of the quick-phrase list.
 *
 * The persisted file is the source of truth: state is published only after [PhraseStorage.save]
 * succeeds, and every committed mutation advances the revision exactly once. Writes are serialized
 * so the full page and the quick card cannot overwrite each other. If the persisted library cannot
 * be loaded, [issue] becomes true and writes are refused instead of silently replacing user data.
 */
internal class PhraseLibrary(
    private val storage: PhraseStorage,
    private val scope: CoroutineScope,
) {
    /**
     * Storage I/O never runs on the caller's dispatcher. Phase 3 calls writes from a UI
     * `rememberCoroutineScope()`, so Keystore/AtomicFile work must be owned here instead of blocking
     * Main. One slot also keeps the initial load and every save serialized independently of the
     * caller-provided [scope]; the write mutex below adds the mutation-level ordering.
     */
    private val storageDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeLock = Mutex()
    private val mutableState = MutableStateFlow(PhraseLibraryState())
    val state: StateFlow<PhraseLibraryState> = mutableState.asStateFlow()
    private val mutableIssue = MutableStateFlow(false)
    val issue: StateFlow<Boolean> = mutableIssue.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    @Volatile
    private var loadJob: Job? = null

    /** Idempotent; the initial read runs once on [scope] but its storage access runs on the IO slot. */
    @Synchronized
    fun start() {
        if (loadJob != null) return
        loadJob = scope.launch { load() }
    }

    private suspend fun load() {
        try {
            mutableState.value = withContext(storageDispatcher) { storage.load() } ?: PhraseLibraryState()
            mutableIssue.value = false
        } catch (error: Exception) {
            mutableIssue.value = true
        } finally {
            mutableReady.value = true
        }
    }

    suspend fun add(text: String): PhraseWriteResult {
        val validation = PhraseRules.validate(text)
        if (validation != PhraseWriteResult.SAVED) return validation
        return submit { current ->
            when {
                current.phrases.size >= PhraseRules.LIMIT_ITEMS -> Decision.Rejected(PhraseWriteResult.FULL)
                current.phrases.any { PhraseRules.duplicateKey(it.text) == PhraseRules.duplicateKey(text) } ->
                    Decision.Rejected(PhraseWriteResult.DUPLICATE)
                else -> commitIfItFits(current, current.phrases + StoredPhrase(UUID.randomUUID().toString(), text))
            }
        }
    }

    suspend fun update(id: String, text: String): PhraseWriteResult {
        val validation = PhraseRules.validate(text)
        if (validation != PhraseWriteResult.SAVED) return validation
        return submit { current ->
            val index = current.phrases.indexOfFirst { it.id == id }
            when {
                index < 0 -> Decision.Rejected(PhraseWriteResult.CONFLICT)
                current.phrases.any {
                    it.id != id && PhraseRules.duplicateKey(it.text) == PhraseRules.duplicateKey(text)
                } -> Decision.Rejected(PhraseWriteResult.DUPLICATE)
                else -> commitIfItFits(
                    current,
                    current.phrases.toMutableList().apply { this[index] = StoredPhrase(id, text) },
                )
            }
        }
    }

    suspend fun delete(id: String): PhraseWriteResult = submit { current ->
        if (current.phrases.none { it.id == id }) Decision.Rejected(PhraseWriteResult.CONFLICT)
        else Decision.Committed(current.copy(phrases = current.phrases.filterNot { it.id == id }))
    }

    /** Re-inserts a phrase captured for the six-second delete undo, clamped to the current list. */
    suspend fun restore(phrase: StoredPhrase, index: Int): PhraseWriteResult {
        val validation = PhraseRules.validate(phrase.text)
        if (validation != PhraseWriteResult.SAVED) return validation
        val id = runCatching { validId(phrase.id) }.getOrNull()
            ?: return PhraseWriteResult.CONFLICT
        return submit { current ->
            when {
                current.phrases.size >= PhraseRules.LIMIT_ITEMS -> Decision.Rejected(PhraseWriteResult.FULL)
                current.phrases.any { it.id == id } -> Decision.Rejected(PhraseWriteResult.CONFLICT)
                current.phrases.any {
                    PhraseRules.duplicateKey(it.text) == PhraseRules.duplicateKey(phrase.text)
                } -> Decision.Rejected(PhraseWriteResult.DUPLICATE)
                else -> commitIfItFits(
                    current,
                    current.phrases.toMutableList().apply {
                        add(index.coerceIn(0, size), StoredPhrase(id, phrase.text))
                    },
                )
            }
        }
    }

    suspend fun reorder(ids: List<String>, revision: Long): PhraseWriteResult = submit { current ->
        val byId = current.phrases.associateBy { it.id }
        when {
            revision != current.revision -> Decision.Rejected(PhraseWriteResult.CONFLICT)
            ids.size != current.phrases.size || ids.toSet().size != ids.size ||
                ids.any { it !in byId } -> Decision.Rejected(PhraseWriteResult.CONFLICT)
            else -> Decision.Committed(current.copy(phrases = ids.map { byId.getValue(it) }))
        }
    }

    suspend fun clearAll(revision: Long): PhraseWriteResult = submit { current ->
        if (revision != current.revision) Decision.Rejected(PhraseWriteResult.CONFLICT)
        else Decision.Committed(current.copy(phrases = emptyList()))
    }

    private suspend fun submit(decide: (PhraseLibraryState) -> Decision): PhraseWriteResult {
        start()
        loadJob?.join()
        return writeLock.withLock {
            if (!mutableReady.value || mutableIssue.value) return@withLock PhraseWriteResult.UNAVAILABLE
            when (val decision = decide(mutableState.value)) {
                is Decision.Rejected -> decision.result
                is Decision.Committed -> {
                    val next = decision.state.copy(revision = mutableState.value.revision + 1)
                    try {
                        withContext(storageDispatcher) { storage.save(next) }
                    } catch (error: Exception) {
                        // Keep the old file and in-memory state; the editor can retry.
                        return@withLock PhraseWriteResult.UNAVAILABLE
                    }
                    mutableState.value = next
                    PhraseWriteResult.SAVED
                }
            }
        }
    }

    private fun commitIfItFits(
        current: PhraseLibraryState,
        phrases: List<StoredPhrase>,
    ): Decision {
        val next = current.copy(phrases = phrases)
        val fits = try {
            PhraseCodec.encode(next)
            true
        } catch (error: IllegalArgumentException) {
            false
        }
        return if (fits) Decision.Committed(next) else Decision.Rejected(PhraseWriteResult.TOO_LONG)
    }

    private sealed interface Decision {
        data class Committed(val state: PhraseLibraryState) : Decision
        data class Rejected(val result: PhraseWriteResult) : Decision
    }
}
