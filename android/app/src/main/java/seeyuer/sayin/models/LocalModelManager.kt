package seeyuer.sayin.models

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.sayin.translation.ModelIntegrityException

internal enum class ModelOperationError { ReadFolder, Space, Integrity, Copy, Reference, Delete }

internal data class ModelCopyProgress(
    val model: LocalModel,
    val copied: Long,
    val publishing: Boolean = false,
    val reference: Boolean = false,
)
internal data class ModelOperationProblem(val error: ModelOperationError, val model: LocalModel? = null)

internal class LocalModelManager(
    val catalog: LocalModelCatalog,
    val storage: LocalModelStorage,
    private val scope: CoroutineScope,
    private val onInstalled: (List<LocalModel>) -> Unit,
) {
    var installed by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    var initialized by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var scan by mutableStateOf<ModelScan?>(null); private set
    var progress by mutableStateOf<ModelCopyProgress?>(null); private set
    var deleting by mutableStateOf<LocalModel?>(null); private set
    var problems by mutableStateOf<List<ModelOperationProblem>>(emptyList()); private set
    var importedCount by mutableIntStateOf(0); private set
    val busy get() = !initialized || scanning || progress != null || deleting != null
    private var operation: Job? = null

    fun start() {
        scope.launch {
            installed = withContext(Dispatchers.IO) {
                storage.cleanStaging()
                storage.installed()
            }
            initialized = true
            onInstalled(emptyList())
        }
    }

    fun scanFolder(tree: Uri) {
        if (busy) return
        scanning = true
        scan = null
        problems = emptyList()
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val ids = installed.keys
                scan = withContext(Dispatchers.IO) { storage.scan(tree, ids) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                problems = listOf(ModelOperationProblem(ModelOperationError.ReadFolder))
            } finally {
                scanning = false
            }
        }
    }

    fun dismissScan() { scan = null }

    fun dismissProblems() { problems = emptyList() }

    fun consumeImportResult() { importedCount = 0 }

    fun importScanned(mode: ModelImportMode) {
        val candidates = scan?.candidates.orEmpty()
        if (busy || candidates.isEmpty()) return
        scan = null
        problems = emptyList()
        progress = ModelCopyProgress(candidates.first().model, 0, reference = mode == ModelImportMode.Reference)
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val initialIds = installed.keys
            val updates = MutableStateFlow(checkNotNull(progress))
            val observer = launch { updates.collect { progress = it } }
            try {
                for (candidate in candidates) {
                    val model = candidate.model
                    updates.value = ModelCopyProgress(model, 0, reference = mode == ModelImportMode.Reference)
                    val error = withContext(Dispatchers.IO) {
                        try {
                            if (mode == ModelImportMode.Reference) {
                                storage.reference(candidate, onPublishing = {
                                    updates.value = ModelCopyProgress(
                                        model,
                                        model.bytes,
                                        publishing = true,
                                        reference = true,
                                    )
                                }) { copied ->
                                    updates.value = ModelCopyProgress(model, copied, reference = true)
                                }
                            } else {
                                storage.install(candidate, onPublishing = {
                                    updates.value = ModelCopyProgress(model, model.bytes, publishing = true)
                                }) { copied ->
                                    updates.value = ModelCopyProgress(model, copied)
                                }
                            }
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: ModelIntegrityException) {
                            ModelOperationError.Integrity
                        } catch (_: ModelSpaceException) {
                            ModelOperationError.Space
                        } catch (_: ModelReferenceException) {
                            ModelOperationError.Reference
                        } catch (_: Exception) {
                            ModelOperationError.Copy
                        }
                    }
                    if (error != null) problems += ModelOperationProblem(error, model)
                }
            } finally {
                observer.cancel()
                withContext(NonCancellable) {
                    installed = withContext(Dispatchers.IO) { storage.installed() }
                    progress = null
                    val imported = candidates.map { it.model }.filter { it.id in installed && it.id !in initialIds }
                    val existingCategories = catalog.all.filter { it.id in initialIds }.map { it.category }.toSet()
                    onInstalled(imported.filter { it.category !in existingCategories })
                    importedCount = imported.size
                }
            }
        }
    }

    fun cancelImport() {
        if (scanning || progress?.publishing == false) operation?.cancel()
    }

    fun delete(model: LocalModel, beforeDelete: suspend () -> Unit) {
        if (busy || model.id !in installed) return
        deleting = model
        problems = emptyList()
        operation = scope.launch {
            try {
                beforeDelete()
                withContext(Dispatchers.IO) { storage.delete(model) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                problems = listOf(ModelOperationProblem(ModelOperationError.Delete, model))
            } finally {
                withContext(NonCancellable) {
                    installed = withContext(Dispatchers.IO) { storage.installed() }
                    deleting = null
                    onInstalled(emptyList())
                }
            }
        }
    }
}
