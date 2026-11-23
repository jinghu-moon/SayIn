package seeyuer.sayin.models

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import seeyuer.sayin.translation.copyVerifiedModelFile

internal data class ModelCandidate(val model: LocalModel, val documents: Map<String, Uri>)
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
    fun directory(model: LocalModel) = File(root, model.id)

    fun installed(): Map<String, Long> = buildMap {
        catalog.all.forEach { model ->
            val directory = directory(model)
            if (model.files.all { File(directory, it.path).isFile && File(directory, it.path).length() == it.bytes }) {
                put(model.id, model.bytes)
            }
        }
    }

    fun cleanStaging() {
        root.listFiles()?.filter { it.name.startsWith(".pending-") || it.name.startsWith(".removed-") }
            ?.forEach { it.deleteRecursively() }
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
                    else -> candidates[model.id] = ModelCandidate(model, documents)
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

    fun delete(model: LocalModel) {
        val source = directory(model)
        if (!source.exists()) return
        val removed = File(root, ".removed-${model.id}")
        if (removed.exists() && !removed.deleteRecursively()) throw IOException()
        Os.rename(source.path, removed.path)
        if (!removed.deleteRecursively()) throw IOException()
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
