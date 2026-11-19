package seeyuer.sayin.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
    private val records = listOf(
        HistoryExportRecord("11111111-1111-4111-8111-111111111111", 1_700_000_000_000, "injected", "text", "Editor", "第一句。Hello"),
        HistoryExportRecord("22222222-2222-4222-8222-222222222222", 1_700_000_100_000, "held", "image", null, null, 1280, 720, 4096),
    )

    @Test
    fun markdownContainsMetadataAndTextButNotImageBinary() {
        val value = HistoryExport.markdown(records, exportedAt = 1_700_000_200_000)
        assertTrue(value.contains("# SayIn history"))
        assertTrue(value.contains("Target: Editor"))
        assertTrue(value.contains("第一句。Hello"))
        assertTrue(value.contains("Image: 1280×720, 4 KiB"))
        assertTrue(value.contains("Target: unknown"))
    }

    @Test
    fun jsonIsVersionedAndExplicitAboutImages() {
        val value = JSONObject(HistoryExport.json(records, exportedAt = 1_700_000_200_000))
        assertEquals("sayin-history", value.getString("format"))
        assertEquals(1, value.getInt("version"))
        assertFalse(value.getBoolean("includes_images"))
        assertEquals(2, value.getJSONArray("records").length())
        assertEquals("第一句。Hello", value.getJSONArray("records").getJSONObject(0).getString("text"))
        assertEquals(JSONObject.NULL, value.getJSONArray("records").getJSONObject(1).get("text"))
    }

    @Test
    fun encryptedBackupRoundTripsAndRejectsWrongPassphrase() {
        val plain = HistoryExport.json(records).toByteArray()
        val encrypted = HistoryExport.encrypted(plain, "correct horse battery".toCharArray())
        assertFalse(encrypted.contentEquals(plain))
        assertArrayEquals(plain, HistoryExport.decrypt(encrypted, "correct horse battery".toCharArray()))
        val failed = runCatching { HistoryExport.decrypt(encrypted, "wrong passphrase".toCharArray()) }.isFailure
        assertTrue(failed)
    }
}
