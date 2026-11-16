package seeyuer.sayin.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class DraftCharacterCountTest {
    @Test fun countsCodePointsAndReversedSelectionInsteadOfUtf16Units() {
        val text = "中😀a👩‍💻"
        assertEquals(6, draftCharacterCount(TextFieldValue(text)))
        assertEquals(2, draftCharacterCount(TextFieldValue(text, TextRange(4, 1))))
        assertEquals(0, draftCharacterCount(TextFieldValue()))
    }
    @Test fun protocolBoundaryCountsSupplementaryCharactersOnce() {
        assertEquals(3600, draftCharacterCount(TextFieldValue("😀".repeat(3600))))
        assertEquals(4001, draftCharacterCount(TextFieldValue("😀".repeat(4001))))
    }
}
