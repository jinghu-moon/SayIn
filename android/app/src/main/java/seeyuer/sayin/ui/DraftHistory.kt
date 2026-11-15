package seeyuer.sayin.ui

/** Stores the state before an operation. Streaming updates are committed by their owner once. */
internal class DraftHistory<T>(private val limit: Int = 60) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var group: String? = null
    private var lastEdit = Long.MIN_VALUE
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun record(before: T, kind: String? = null, now: Long = 0, composing: Boolean = false) {
        val merge = kind != null && group == kind && (composing || now - lastEdit in 0..900)
        if (!merge) {
            past.addLast(before)
            while (past.size > limit) past.removeFirst()
        }
        future.clear()
        group = kind
        lastEdit = now
    }

    fun breakGroup() { group = null }
    fun undo(current: T): T? {
        if (!canUndo) return null
        breakGroup(); future.addLast(current); return past.removeLast()
    }
    fun redo(current: T): T? {
        if (!canRedo) return null
        breakGroup(); past.addLast(current); return future.removeLast()
    }
    fun clear() { past.clear(); future.clear(); breakGroup() }
}
