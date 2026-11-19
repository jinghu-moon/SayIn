package seeyuer.sayin.translation

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation

enum class ModelPreparationStage { Copying, Downloading, Verifying }
data class ModelPreparationProgress(val stage: ModelPreparationStage, val bytes: Long, val total: Long)
class ModelIntegrityException : Exception()

/** Only public weights are downloaded; this object never receives user text or prompts. */
class TranslationModelStore(context: Context, val model: TranslationModel, suffix: String = "") {
    private val assets = context.assets
    private val directory = File(context.noBackupFilesDir, "translation$suffix")
    val file = File(directory, model.fileName)
    private val bundledAsset = if (model.bundled && suffix.isEmpty()) "translation/${model.fileName}" else null
    val bundled: Boolean by lazy {
        val path = bundledAsset ?: return@lazy false
        runCatching { assets.openFd(path).use { it.length == model.bytes } }.getOrDefault(false)
    }
    val ready get() = prepared || bundled
    val prepared get() = file.isFile && file.length() == model.bytes
    val present get() = file.isFile
    val downloadedBytes get() = when { file.isFile -> file.length(); bundled -> model.bytes; else -> 0L }

    /** A directly loadable uncompressed asset; null if the build did not bundle this model. */
    fun openBundled(): AssetFileDescriptor? {
        val path = bundledAsset ?: return null
        val descriptor = runCatching { assets.openFd(path) }.getOrNull() ?: return null
        if (descriptor.length == model.bytes) return descriptor
        runCatching { descriptor.close() }
        return null
    }

    /** Removes only this model's artifact (and any interrupted pending copy). */
    fun delete(): Boolean {
        File(directory, model.fileName + ".pending").delete()
        return !file.exists() || file.delete()
    }

    suspend fun download(progress: (ModelPreparationProgress) -> Unit) = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(false).build()
        val url = "https://huggingface.co/${model.repository}/resolve/${model.revision}/${model.fileName}"
        val call = client.newCall(Request.Builder().url(url).build())
        // A suspended child cancels the blocking call on cancellation, including handshake/read.
        coroutineScope {
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    if (body.contentLength() >= 0 && body.contentLength() != model.bytes) throw ModelIntegrityException()
                    body.byteStream().use { input -> prepare(input, ModelPreparationStage.Downloading, progress) }
                }
            } finally { cancellation.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    /** Caller supplies a single opened SAF/local stream; no long-lived external URI dependency. */
    suspend fun import(input: InputStream, progress: (ModelPreparationProgress) -> Unit) = withContext(Dispatchers.IO) {
        input.use { prepare(it, ModelPreparationStage.Copying, progress) }
    }

    private suspend fun prepare(input: InputStream, stage: ModelPreparationStage, progress: (ModelPreparationProgress) -> Unit) {
        directory.mkdirs()
        check(directory.usableSpace >= model.bytes + 16L * 1024 * 1024)
        val pending = File(directory, model.fileName + ".pending")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(pending).use { output ->
                val buffer = ByteArray(1024 * 1024); var done = 0L
                progress(ModelPreparationProgress(stage, 0, model.bytes))
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    done += count
                    if (done > model.bytes) throw ModelIntegrityException()
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    progress(ModelPreparationProgress(stage, done, model.bytes))
                }
                progress(ModelPreparationProgress(ModelPreparationStage.Verifying, done, model.bytes))
                if (done != model.bytes || digest.digest().joinToString("") { "%02x".format(it) } != model.sha256) throw ModelIntegrityException()
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            // Same private directory: rename is atomic; failed/cancelled preparation retains old model.
            android.system.Os.rename(pending.path, file.path)
        } finally { pending.delete() }
    }
}
