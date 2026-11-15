package seeyuer.sayin.translation
import org.junit.Assert.*
import org.junit.Test

class Utf8PreviewTest {
    @Test fun everyTokenBoundaryPreservesChineseEmojiAndNewlines() {
        val text = "中文😊🚀❤️\nSayIn\u0000末尾"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (end in 0..bytes.size) {
            val prefix = Utf8Preview.completePrefix(bytes.copyOf(end))
            assertTrue(text.startsWith(prefix))
            assertFalse(prefix.contains('\uFFFD'))
        }
        assertEquals(text, Utf8Preview.completePrefix(bytes))
    }
}
