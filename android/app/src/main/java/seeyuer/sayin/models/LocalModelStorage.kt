package seeyuer.sayin.models

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import seeyuer.sayin.translation.copyVerifiedModelFile

internal enum class ModelImportMode { Copy, Reference }
internal class ModelReferenceException : IOException()

internal data class ModelCandidate(
    val model: LocalModel,
    val documents: Map<String, Uri>,
    val tree: Uri? = null,
    val sourceDirectory: File? = null,
)
internal data class ModelScan(
    val candidates: List<ModelCandidate>,
    val installed: List<LocalModel>,
    val duplicates: List<LocalModel>,
    val problems: List<ModelFolderProblem>,
)
internal data class ModelFolderProblem(val model: LocalModel, val files: List<String>)
internal class ModelSpaceException : IOException()

/** Only complete, verified directories are visible to either inference engine. */
internal class LocalModelStorage(context: Context, private val catalog: LocalModelCatalog) {
    private val resolver = context.contentResolver
    private val root = File(context.noBackupFilesDir, "local-models")
    fun directory(model: LocalModel): File {
        referenceSource(model)?.let { return it }
        return File(root, model.id)
    }

    fun isReference(model: LocalModel): Boolean = referenceManifest(model).isFile

    fun installed(): Map<String, Long> = buildMap {
        catalog.all.forEach { model ->
            if (isReference(model)) {
                val source = referenceSource(model)
                if (source != null && model.files.all {
                        File(source, it.path).isFile && File(source, it.path).length() == it.bytes
                    }) {
                    put(model.id, 0L)
                }
                return@forEach
            }
            val directory = directory(model)
            if (model.files.all { File(directory, it.path).isFile && File(directory, it.path).length() == it.bytes }) {
                put(model.id, model.bytes)
            }
        }
    }

    fun cleanStaging() {
        root.listFiles()?.filter {
            it.name.startsWith(".pending-") || it.name.startsWith(".removed-") || it.name.startsWith(".ref-pending-")
        }?.forEach { it.deleteRecursively() }
    }

    suspend fun scan(tree: Uri, installed: Set<String>): ModelScan {
        val directory = DocumentsContract.getTreeDocumentId(tree)
        val rootEntries = children(tree, directory)
        val folders = listOf(rootEntries) + rootEntries.filterIsInstance<Folder>().map { children(tree, it.id) }
        val candidates = linkedMapOf<String, ModelCandidate>()
        val existing = linkedMapOf<String, LocalModel>()
        val duplicates = linkedMapOf<String, LocalModel>()
        val issues = linkedMapOf<String, ModelFolderProblem>()
        for (entries in folders) {
            val byName = entries.groupBy { it.name }
            val nested = mutableMapOf<String, List<Entry>>()
            for (model in catalog.all) {
                coroutineContext.ensureActive()
                val firstLevel = model.files.filterNot { '/' in it.path }
                val matching = firstLevel.any { file ->
                    byName[file.path].orEmpty().filterIsInstance<SourceFile>().any { it.bytes == null || it.bytes == file.bytes }
                }
                val distinctive = firstLevel.any { file ->
                    file.path in byName && catalog.all.count { other -> other.files.any { it.path == file.path } } == 1
                }
                val entryFile = model.modelEntryFile
                val identified = if (entryFile == null) matching || distinctive else entryFile in byName
                if (!identified) continue
                val documents = linkedMapOf<String, Uri>()
                val invalid = mutableListOf<String>()
                for (file in model.files) {
                    val parent = file.path.substringBeforeLast('/', "")
                    val name = file.path.substringAfterLast('/')
                    val entriesForFile = if (parent.isEmpty()) byName[name].orEmpty() else {
                        nested.getOrPut(parent) {
                            val subfolder = byName[parent]?.singleOrNull() as? Folder
                            subfolder?.let { children(tree, it.id) }.orEmpty()
                        }.filter { it.name == name }
                    }
                    val document = entriesForFile.singleOrNull() as? SourceFile
                    if (document == null || document.bytes != null && document.bytes != file.bytes) invalid += file.path
                    else documents[file.path] = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
                }
                when {
                    invalid.isNotEmpty() -> issues.putIfAbsent(model.id, ModelFolderProblem(model, invalid))
                    model.id in installed -> existing[model.id] = model
                    model.id in candidates -> duplicates[model.id] = model
                    else -> candidates[model.id] = ModelCandidate(
                        model,
                        documents,
                        tree,
                        documents.values.firstOrNull()?.let(::sourceDirectoryOf),
                    )
                }
            }
        }
        return ModelScan(candidates.values.toList(), existing.values.toList(), duplicates.values.toList(),
            issues.filterKeys { it !in candidates && it !in existing }.values.toList())
    }

    suspend fun install(candidate: ModelCandidate, onPublishing: () -> Unit, progress: (Long) -> Unit) {
        val model = candidate.model
        val destination = directory(model)
        if (model.id in installed()) return
        if (!root.isDirectory && !root.mkdirs()) throw IOException()
        if (root.usableSpace < model.bytes + 16L * 1024 * 1024) throw ModelSpaceException()
        val pending = File(root, ".pending-${model.id}")
        if (!pending.mkdir()) throw IOException()
        try {
            var completed = 0L
            for (file in model.files) {
                val output = File(pending, file.path)
                if (output.parentFile?.let { it.isDirectory || it.mkdirs() } != true) throw IOException()
                val uri = candidate.documents.getValue(file.path)
                val stream = resolver.openInputStream(uri) ?: throw IOException()
                stream.use { copyVerifiedModelFile(it, output, file.bytes, file.sha256) { copied -> progress(completed + copied) } }
                completed += file.bytes
            }
            coroutineContext.ensureActive()
            onPublishing()
            // An incomplete installation is never opened by a decoder. Move it out of the way before publication.
            if (destination.exists()) delete(model)
            Os.rename(pending.path, destination.path)
        } finally {
            pending.deleteRecursively()
        }
    }

    suspend fun reference(candidate: ModelCandidate, onPublishing: () -> Unit, progress: (Long) -> Unit) {
        val model = candidate.model
        if (model.id in installed()) return
        val source = candidate.sourceDirectory ?: throw ModelReferenceException()
        if (!source.isDirectory || model.files.any { !File(source, it.path).isFile }) {
            throw ModelReferenceException()
        }
        if (!root.isDirectory && !root.mkdirs()) throw IOException()
        var completed = 0L
        for (file in model.files) {
            coroutineContext.ensureActive()
            val local = File(source, file.path)
            val digest = MessageDigest.getInstance("SHA-256")
            local.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                var read = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                    read += count
                    progress(completed + read)
                }
                if (read != file.bytes || digest.digest().joinToString("") { "%02x".format(it) } != file.sha256) {
                    throw ModelReferenceException()
                }
            }
            completed += file.bytes
        }
        val manifest = referenceManifest(model)
        val pending = File(root, ".ref-pending-${model.id}")
        pending.delete()
        pending.writeText(source.absolutePath)
        onPublishing()
        manifest.delete()
        Os.rename(pending.path, manifest.path)
    }

    fun delete(model: LocalModel) {
        if (isReference(model)) {
            referenceManifest(model).delete()
            return
        }
        val source = directory(model)
        if (!source.exists()) return
        val removed = File(root, ".removed-${model.id}")
        if (removed.exists() && !removed.deleteRecursively()) throw IOException()
        Os.rename(source.path, removed.path)
        if (!removed.deleteRecursively()) throw IOException()
    }

    private fun referenceManifest(model: LocalModel) = File(root, ".ref-${model.id}.txt")

    private fun referenceSource(model: LocalModel): File? {
        val manifest = referenceManifest(model)
        if (!manifest.isFile) return null
        return runCatching { File(manifest.readText().trim()).takeIf { it.isDirectory } }.getOrNull()
    }

    private fun sourceDirectoryOf(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (!documentId.startsWith("primary:")) return null
        val relative = documentId.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStorageDirectory()
        return File(base, relative).parentFile
    }

    private sealed interface Entry {
        val id: String
        val name: String
    }
    private data class Folder(override val id: String, override val name: String) : Entry
    private data class SourceFile(override val id: String, override val name: String, val bytes: Long?) : Entry

    private suspend fun children(tree: Uri, id: String): List<Entry> = buildList {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException()
        cursor.use {
            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                if (size == MAX_ENTRIES) throw IOException()
                val document = it.getString(0) ?: throw IOException()
                val name = it.getString(1) ?: throw IOException()
                add(if (it.getString(2) == Document.MIME_TYPE_DIR) Folder(document, name)
                else SourceFile(document, name, if (it.isNull(3)) null else it.getLong(3)))
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 256
    }
}
