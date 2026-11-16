package seeyuer.sayin.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForegroundApplicationHintTest {
    private fun pngHeader(width: Int = 48): String {
        val bytes = ByteArray(33)
        byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82).copyInto(bytes)
        bytes[19] = width.toByte(); bytes[23] = 48
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
    @Test fun boundedIconEnvelopeReplacesAtomicallyAndNeverRevivesAnOldIcon() {
        val encoded = pngHeader()
        val current = ForegroundApplicationHint().receive(frame("1").put("icon_png", encoded))
        assertEquals(encoded, current.iconPng)
        assertEquals(current, current.receive(frame("1").put("icon_png", JSONObject.NULL)))
        for (bad in listOf(pngHeader(49), "x".repeat(16388), "https://example/icon.png", "!".repeat(44), encoded.dropLast(1))) {
            val changed = current.receive(frame("2", "Browser").put("icon_png", bad))
            assertEquals("Browser", changed.name)
            assertNull(changed.iconPng)
        }
        assertNull(current.receive(frame("2")).iconPng)
        assertNull(current.receive(frame("2", JSONObject.NULL).put("icon_png", encoded)).iconPng)
        assertNull(current.receive(frame("bad")).iconPng)
    }
    private fun frame(sequence: String, name: Any = "Word") = JSONObject().put("seq", sequence).put("name", name)
    @Test fun newerNamesReplaceAndLateFramesCannotOverwriteOrClearCurrentName() {
        val word = ForegroundApplicationHint().receive(frame("1"))
        val browser = word.receive(frame("2", "Browser"))
        assertEquals("Browser", browser.name)
        assertEquals(browser, browser.receive(frame("1", JSONObject.NULL)))
        assertEquals(browser, browser.receive(frame("2", "late")))
        assertNull(browser.receive(frame("3", JSONObject.NULL)).name)
    }
    @Test fun malformedMetadataFailsOnlyTheDisplayChannel() {
        val current = ForegroundApplicationHint().receive(frame("1"))
        for (bad in listOf(frame("+2"), frame("9223372036854775808"), frame("2", "a\nb"), frame("2", 5), frame("2", "x".repeat(97)), JSONObject().put("seq", "2"))) {
            val cleared = current.receive(bad)
            assertNull(cleared.name)
            assertEquals(1, cleared.sequence)
        }
        assertEquals("😀".repeat(96), current.receive(frame("2", "😀".repeat(96))).name)
    }
    @Test fun onlyMatchingHeartbeatConfirmsTheCurrentConnectionObservation() {
        val current = ForegroundApplicationHint().receive(frame("8"))
        assertTrue(current.confirmed(JSONObject().put("foreground_seq", "8")))
        assertFalse(current.confirmed(JSONObject().put("foreground_seq", "7")))
        assertFalse(current.confirmed(JSONObject()))
        assertFalse(ForegroundApplicationHint().confirmed(JSONObject().put("foreground_seq", "8")))
    }
    @Test fun displayChangesCannotChangeReceptionAuthorization() {
        val ready = ClientState(connection = Connection.ONLINE, active = true, paused = false, target = "scope", revision = "42")
        assertTrue(ready.ready)
        assertEquals(ready.receptionScope, ready.copy(foregroundApplication = ForegroundApplication("Word")).receptionScope)
        assertEquals(ready.receptionScope, ready.copy(foregroundApplication = null).receptionScope)
        assertFalse(ready.copy(paused = true, foregroundApplication = ForegroundApplication("Word")).ready)
        assertNull(ready.copy(connection = Connection.OFFLINE, foregroundApplication = ForegroundApplication("Word")).receptionScope)
    }

}
