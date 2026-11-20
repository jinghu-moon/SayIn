package seeyuer.sayin.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.sayin.polish.PolishConfig
import seeyuer.sayin.polish.PolishProvider

class PolishEditorStateTest {
    @Test fun `editing preserves stable identity name and revision`() {
        val original = PolishConfig.preset(PolishProvider.DeepSeek)
            .copy(id = "stable-id", name = "Work", revision = 4, key = "key")
        val editor = PolishEditorState(original)
        editor.model = "deepseek-v4-flash"
        val built = editor.build()
        assertEquals("stable-id", built.id)
        assertEquals("Work", built.name)
        assertEquals(4L, built.revision)
        assertEquals("deepseek-v4-flash", built.model)
    }

    @Test fun `switching provider loads its preset and clears the old key`() {
        val editor = PolishEditorState(PolishConfig.preset(PolishProvider.DeepSeek).copy(key = "old"))
        editor.selectProvider(PolishProvider.Anthropic)
        assertEquals("https://api.anthropic.com/v1", editor.baseUrl)
        assertEquals("claude-haiku-5-5", editor.model)
        assertEquals("", editor.key)
        assertTrue(editor.apiVersion == "2026-10-21")
    }

    @Test fun `changing endpoint clears the key but changing model keeps it`() {
        val editor = PolishEditorState(PolishConfig.preset(PolishProvider.DeepSeek).copy(key = "key"))
        editor.updateBaseUrl("https://example.com/v1")
        assertEquals("", editor.key)
        editor.key = "key2"
        editor.model = "other-model"
        assertEquals("key2", editor.key)
    }

    @Test fun `custom protocol change clears the key`() {
        val editor = PolishEditorState(PolishConfig.custom().copy(key = "key"))
        editor.selectProtocol(seeyuer.sayin.polish.PolishProtocol.Messages)
        assertEquals("", editor.key)
        assertTrue(editor.apiVersion == "2023-06-01")
    }
}
