package seeyuer.sayin.ui

import org.junit.Assert.*
import org.junit.Test

class DraftHistoryTest {
    @Test fun editsMergeWithinWindowButClearAndUtterancesAreSeparate() {
        val history = DraftHistory<String>()
        history.record("", "edit", 100)
        history.record("h", "edit", 200)
        history.breakGroup()
        history.record("hello") // one completed utterance
        history.record("hello world") // clear
        assertEquals("hello world", history.undo(""))
        assertEquals("hello", history.undo("hello world"))
        assertEquals("", history.undo("hello"))
        assertNull(history.undo(""))
        assertEquals("hello", history.redo(""))
    }

    @Test fun compositionRemainsOneTransactionEvenWhenLongerThanEditWindow() {
        val history = DraftHistory<String>()
        history.record("before", "composition", 100)
        history.record("before n", "composition", 5000, composing = true)
        history.record("before ni", "composition", 10000, composing = true)
        history.breakGroup()
        history.record("before 你", "edit", 10001)
        assertEquals("before 你", history.undo("before 你好"))
        assertEquals("before", history.undo("before 你"))
    }

    @Test fun redoIsInvalidatedAndHistoryIsBounded() {
        val history = DraftHistory<Int>(60)
        repeat(100) { history.record(it) }
        assertEquals(99, history.undo(100))
        history.record(99)
        assertFalse(history.canRedo)
        repeat(60) { assertNotNull(history.undo(100)) }
        assertFalse(history.canUndo)
        history.clear()
        assertFalse(history.canRedo)
    }

    @Test fun pauseOrOperationKindBreaksTypingGroup() {
        val history = DraftHistory<String>()
        history.record("", "edit", 100)
        history.record("a", "edit", 1100)
        history.record("ab", "delete", 1101)
        assertEquals("ab", history.undo("a"))
        assertEquals("a", history.undo("ab"))
        assertEquals("", history.undo("a"))
    }
}
