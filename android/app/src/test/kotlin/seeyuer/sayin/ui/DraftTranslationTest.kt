package seeyuer.sayin.ui

import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.translation.TranslationEnd

class DraftTranslationTest {
    private val ticket = TranslationTicket("task", "draft", 5, "original", "a".repeat(64), "template")
    @Test fun editingAwayAndBackCannotReauthorizeOldTask() {
        assertTrue(ticket.matches("draft", 5))
        assertFalse(ticket.matches("draft", 7))
        assertFalse(ticket.matches("new-draft", 5))
    }
    @Test fun anotherTaskCannotPublishIntoSameSourceRevision() {
        val state = DraftTranslation(ticket = ticket, running = true)
        assertTrue(state.accepts("task", "draft", 5))
        assertFalse(state.accepts("old-task", "draft", 5))
    }
    @Test fun onlyNormalEndProducesAnEditableCompleteCandidate() {
        for (end in TranslationEnd.entries) {
            val state = DraftTranslation(ticket = ticket, end = end)
            assertEquals(end == TranslationEnd.Complete, state.complete)
            assertFalse(state.copy(running = true).complete)
        }
        assertFalse(DraftTranslation(end = TranslationEnd.Complete).complete)
    }
}
