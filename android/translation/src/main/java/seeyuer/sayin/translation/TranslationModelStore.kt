package seeyuer.sayin.translation

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ModelPreparationStage { Copying, Verifying }
data class ModelPreparationProgress(val stage: ModelPreparationStage, val bytes: Long, val total: Long)

/** Translation artifact access, including bundled assets for isolated device tests. */
class TranslationModelStore(context: Context, val model: TranslationModel, suffix: String = "", importedFile: File? = null) {
    private val assets = context.assets
    private val directory = File(context.noBackupFilesDir, "translation$suffix")
    val file = importedFile ?: File(directory, model.fileName)
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

    /** Imports a fixture through the same verified copy used by folder imports. */
    suspend fun import(input: InputStream, progress: (ModelPreparationProgress) -> Unit) = withContext(Dispatchers.IO) {
        input.use {
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val pending = File(file.parentFile, file.name + ".pending")
            try {
                copyVerifiedModelFile(it, pending, model.bytes, model.sha256) { copied ->
                    progress(ModelPreparationProgress(ModelPreparationStage.Copying, copied, model.bytes))
                }
                coroutineContext.ensureActive()
                android.system.Os.rename(pending.path, file.path)
            } finally {
                pending.delete()
            }
        }
    }
}
