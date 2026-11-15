package seeyuer.sayin.translation

import android.content.Context
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

data class TranslationModel(
    val id: String, val fileName: String, val bytes: Long, val sha256: String,
    val architecture: String, val templateKind: Int, val templateRevision: String,
    val repository: String = "", val revision: String = "",
)

class TranslationCatalog private constructor(val models: List<TranslationModel>, val defaultId: String?) {
    val defaultModel get() = models.single { it.id == defaultId }
    companion object {
        fun read(context: Context): TranslationCatalog {
            val root = context.assets.open("translation-artifacts.json").bufferedReader().use { JSONObject(it.readText()) }
            val array = root.getJSONArray("models")
            return TranslationCatalog((0 until array.length()).map { index ->
                val m = array.getJSONObject(index)
                TranslationModel(m.getString("id"), m.getString("file"), m.getLong("bytes"), m.getString("sha256"),
                    m.getString("architecture"), m.getInt("templateKind"), m.getString("templateRevision"), m.getString("repository"), m.getString("revision"))
            }, root.optString("defaultModel").takeIf { it.isNotBlank() && it != "null" })
        }
    }
}

enum class TranslationEnd { Complete, Cancelled, Truncated, Error, InputTooLong, Timeout, ModelMissing, Integrity }
enum class TranslationStage { Verifying, Loading, Generating, Prefill, Decoding }
data class TranslationProgress(val stage: TranslationStage, val fraction: Float = 0f)
data class TranslationResult(
    val end: TranslationEnd, val text: String = "", val sourceTokens: Int = 0, val promptTokens: Int = 0,
    val generatedTokens: Int = 0, val prefillMicros: Long = 0, val firstMicros: Long = 0,
    val totalMicros: Long = 0, val verifyMillis: Long = 0, val loadMillis: Long = 0,
)

internal interface NativeEvents { fun progress(fraction: Float); fun piece(bytes: ByteArray); fun stage(value: Int) }
internal class NativeResult(
    val status: Int, val output: ByteArray, val sourceTokens: Int, val promptTokens: Int,
    val generatedTokens: Int, val prefillMicros: Long, val firstMicros: Long, val totalMicros: Long,
)
internal class NativeBindings {
    init { System.loadLibrary("sayin_translation") }
    external fun create(): Long
    external fun reset(handle: Long)
    external fun cancel(handle: Long)
    external fun load(handle: Long, path: ByteArray, kind: Int, threads: Int, events: NativeEvents): Int
    external fun run(handle: Long, source: ByteArray, limit: Int, timeoutMillis: Int, events: NativeEvents): NativeResult
    external fun destroy(handle: Long)
}

/** One background owner for file verification, native loading, decode and destruction.
 * Cancellation only sets an atomic flag; destruction is queued after the native call returns.
 * JNI receives standard UTF-8 byte arrays, never JNI modified UTF-8 text.
 */
interface Translator : Closeable {
    suspend fun translate(
        file: File, model: TranslationModel, source: String, threads: Int = 4,
        outputLimit: Int = 2048, timeoutMillis: Int = 120_000,
        onProgress: (TranslationProgress) -> Unit = {}, onPartial: (String) -> Unit = {},
    ): TranslationResult
    fun cancel()
    suspend fun release()
}

class SerialTranslator : Translator {
    private class Request { val cancelled = AtomicBoolean(false) }
    private val owner = Executors.newSingleThreadExecutor { task -> Thread(task, "SayIn-translation") }
    private val lock = Any()
    private var native: NativeBindings? = null
    private var handle = 0L
    private var loaded: Pair<String, TranslationModel>? = null
    private var active: Request? = null
    private var closed = false

    override suspend fun translate(
        file: File, model: TranslationModel, source: String, threads: Int,
        outputLimit: Int, timeoutMillis: Int,
        onProgress: (TranslationProgress) -> Unit, onPartial: (String) -> Unit,
    ): TranslationResult = suspendCancellableCoroutine { continuation ->
        val request = Request()
        continuation.invokeOnCancellation { cancel(request) }
        try { owner.execute {
            if (request.cancelled.get()) return@execute
            val result = try {
                synchronized(lock) {
                    check(!closed)
                    if (request.cancelled.get()) return@execute
                    active = request
                }
                // dlopen/backend initialization can be slow. Cancellation never waits for it on the UI thread.
                val api = native ?: NativeBindings().also { native = it }
                if (handle == 0L) {
                    val created = api.create().also { check(it != 0L) }
                    synchronized(lock) { handle = created }
                }
                synchronized(lock) { if (!request.cancelled.get()) api.reset(handle) }
                if (request.cancelled.get()) TranslationResult(TranslationEnd.Cancelled)
                else infer(request, file, model, source, threads, outputLimit, timeoutMillis, onProgress, onPartial)
            } catch (_: Exception) { TranslationResult(if (request.cancelled.get()) TranslationEnd.Cancelled else TranslationEnd.Error) }
              catch (_: LinkageError) { TranslationResult(TranslationEnd.Error) }
            finally { synchronized(lock) { if (active === request) active = null } }
            if (continuation.isActive) continuation.resume(result)
        } } catch (_: RejectedExecutionException) { if (continuation.isActive) continuation.resume(TranslationResult(TranslationEnd.Error)) }
    }

    private fun infer(
        request: Request, file: File, model: TranslationModel, source: String, threads: Int,
        limit: Int, timeout: Int, progress: (TranslationProgress) -> Unit, partial: (String) -> Unit,
    ): TranslationResult {
        val api = checkNotNull(native)
        val identity = file.canonicalPath to model
        var verification = 0L; var loading = 0L
        val output = ByteArrayOutputStream()
        val events = object : NativeEvents {
            override fun stage(value: Int) { if (!request.cancelled.get()) progress(TranslationProgress(if (value == 0) TranslationStage.Prefill else TranslationStage.Decoding)) }
            override fun progress(fraction: Float) { if (!request.cancelled.get()) progress(TranslationProgress(TranslationStage.Loading, fraction)) }
            override fun piece(bytes: ByteArray) {
                output.write(bytes)
                if (!request.cancelled.get()) partial(Utf8Preview.completePrefix(output.toByteArray()))
            }
        }
        if (loaded != identity) {
            // Only the owner destroys; the lock excludes the tiny concurrent cancellation call.
            val retired = synchronized(lock) {
                if (request.cancelled.get()) return TranslationResult(TranslationEnd.Cancelled)
                if (loaded != null) handle.also { handle = 0L; loaded = null } else 0L
            }
            if (retired != 0L) {
                api.destroy(retired)
                synchronized(lock) {
                    if (request.cancelled.get()) return TranslationResult(TranslationEnd.Cancelled)
                    handle = api.create(); check(handle != 0L)
                }
            }
            if (!file.isFile) return TranslationResult(TranslationEnd.ModelMissing)
            val start = SystemClock.elapsedRealtime()
            val digest = MessageDigest.getInstance("SHA-256")
            if (file.length() != model.bytes) return TranslationResult(TranslationEnd.Integrity)
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024); var done = 0L
                while (true) {
                    if (request.cancelled.get()) return TranslationResult(TranslationEnd.Cancelled)
                    val count = input.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count); done += count
                    progress(TranslationProgress(TranslationStage.Verifying, (done.toDouble() / model.bytes).toFloat()))
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != model.sha256) return TranslationResult(TranslationEnd.Integrity)
            verification = SystemClock.elapsedRealtime() - start
            if (request.cancelled.get()) return TranslationResult(TranslationEnd.Cancelled)
            val loadStart = SystemClock.elapsedRealtime()
            val status = api.load(handle, file.path.toByteArray(Charsets.UTF_8), model.templateKind, threads, events)
            loading = SystemClock.elapsedRealtime() - loadStart
            if (status != 0) return TranslationResult(if (status == 1) TranslationEnd.Cancelled else TranslationEnd.Error,
                verifyMillis = verification, loadMillis = loading)
            loaded = identity
        }
        if (request.cancelled.get()) return TranslationResult(TranslationEnd.Cancelled)
        progress(TranslationProgress(TranslationStage.Generating))
        val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(source))
        val sourceBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val result = api.run(handle, sourceBytes, limit, timeout, events)
        val end = TranslationEnd.entries.getOrNull(result.status)?.takeIf { result.status <= 5 } ?: TranslationEnd.Error
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(result.output)).toString() }.getOrNull()
        return TranslationResult(if (text == null && end == TranslationEnd.Complete) TranslationEnd.Error else end,
            text ?: Utf8Preview.completePrefix(result.output), result.sourceTokens, result.promptTokens, result.generatedTokens,
            result.prefillMicros, result.firstMicros, result.totalMicros, verification, loading)
    }

    private fun cancel(request: Request) {
        synchronized(lock) {
            request.cancelled.set(true)
            if (active === request && handle != 0L) native?.cancel(handle)
        }
    }
    override fun cancel() { synchronized(lock) { active?.let(::cancel) } }

    /** Await this before starting CPU-heavy ASR or replacing a model file. Never blocks the UI thread. */
    override suspend fun release() {
        cancel()
        suspendCancellableCoroutine { continuation ->
            try { owner.execute {
                val retired = synchronized(lock) { handle.also { handle = 0L; loaded = null } }
                if (retired != 0L) native?.destroy(retired)
                if (continuation.isActive) continuation.resume(Unit)
            } } catch (_: RejectedExecutionException) { if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; active?.let(::cancel)
            owner.execute {
                val retired = synchronized(lock) { handle.also { handle = 0L; loaded = null } }
                if (retired != 0L) native?.destroy(retired)
            }
            owner.shutdown()
        }
    }
}

internal object Utf8Preview {
    /** Token boundaries need not be Unicode boundaries; withhold an incomplete final code point. */
    fun completePrefix(bytes: ByteArray): String {
        var end = bytes.size
        if (end > 0) {
            var lead = end - 1
            while (lead >= 0 && bytes[lead].toInt() and 0xc0 == 0x80) lead--
            if (lead >= 0) {
                val b = bytes[lead].toInt() and 0xff
                val size = when { b < 0x80 -> 1; b and 0xe0 == 0xc0 -> 2; b and 0xf0 == 0xe0 -> 3; b and 0xf8 == 0xf0 -> 4; else -> 1 }
                if (end - lead < size) end = lead
            }
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
