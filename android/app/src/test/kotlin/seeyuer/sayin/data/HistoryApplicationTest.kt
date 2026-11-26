package seeyuer.sayin.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryApplicationTest {
    @Test fun boundedNamesAndInvalidIconsDoNotAffectReceiptState() {
        for (name in listOf("", " ", "x".repeat(97), "Editor\nspoof"))
            assertNull(HistoryApplication.parse(JSONObject().put("name", name)))
        val value = HistoryApplication.parse(JSONObject().put("name", "Editor").put("icon_webp", "https://untrusted/icon"))!!
        assertEquals("Editor", value.name)
        assertNull(value.iconWebp)
        assertEquals("😀".repeat(96), HistoryApplication.parse(JSONObject().put("name", "😀".repeat(96)))?.name)
    }
    @Test fun protectedDisplayIsBoundToOriginalRequestHostAndDevice() {
        val item = OutboxItem("request", "host", "device", "scope", null, 0, "injected", "1", null, 1)
        val snapshot = HistoryApplication("Original editor", null)
        val bytes = snapshot.protectedJson(item)
        assertEquals(snapshot, HistoryApplication.restore(bytes, item))
        for (wrong in listOf(item.copy(id = "other"), item.copy(hostId = "other"), item.copy(deviceId = "other"))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryApplication.restore(bytes, wrong) }
        }
        assertFalse(bytes.toString(Charsets.UTF_8).contains("scope"))
    }
    @Test fun legacyPngHistoryRetainsItsNameWhileDroppingTheOldIcon() {
        val value = HistoryApplication.parse(JSONObject().put("name", "Original editor").put("icon_png", "legacy-icon"))!!
        assertEquals("Original editor", value.name)
        assertNull(value.iconWebp)
    }
}
