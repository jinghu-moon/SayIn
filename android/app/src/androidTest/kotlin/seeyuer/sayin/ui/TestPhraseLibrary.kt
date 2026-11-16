package seeyuer.sayin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import seeyuer.sayin.data.PhraseLibrary
import seeyuer.sayin.data.PhraseLibraryState
import seeyuer.sayin.data.PhraseStorage

/** In-memory, serial, atomic-enough phrase storage for instrumented UI mounts. */
internal class MemoryPhraseStorage(initial: PhraseLibraryState? = PhraseLibraryState()) : PhraseStorage {
    var stored: PhraseLibraryState? = initial
    var failWrites = false
    var writes = 0

    override fun load(): PhraseLibraryState? = stored

    override fun save(state: PhraseLibraryState) {
        if (failWrites) throw java.io.IOException("Test storage unavailable")
        stored = state
        writes++
    }
}

/**
 * Builds the same Application-level PhraseLibrary path the product uses, backed by test memory.
 * There is deliberately no nullable "feature disabled" branch in production.
 */
@Composable
internal fun rememberTestPhraseLibrary(storage: PhraseStorage? = null): PhraseLibrary {
    val memory = remember { storage ?: MemoryPhraseStorage() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val library = remember(memory) { PhraseLibrary(memory, scope).also { it.start() } }
    DisposableEffect(library) { onDispose { scope.cancel() } }
    return library
}
