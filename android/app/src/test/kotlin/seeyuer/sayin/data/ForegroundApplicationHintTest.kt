package seeyuer.sayin.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForegroundApplicationHintTest {
    @Test fun acceptsBoundedLosslessWebpAndDropsMalformedIconsWithoutLosingName() {
        val bytes = ByteArray(26)
        "RIFF".toByteArray().copyInto(bytes)
        bytes[4] = 18
        "WEBPVP8L".toByteArray().copyInto(bytes, 8)
        bytes[16] = 5
        bytes[20] = 0x2f
        val dimensions = 47 or (47 shl 14)
        for (index in 0..3) bytes[21 + index] = (dimensions ushr (index * 8)).toByte()
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, ForegroundApplicationHint.iconBytes(encoded))
        val current = ForegroundApplicationHint().receive(frame("1").put("icon_webp", encoded))
        assertEquals("Word", current.name)
        assertNotNull(current.iconWebp)
        for (bad in listOf(bytes.copyOf(4097), bytes.copyOf(25), bytes.copyOf().also { it[21] = 48 })) {
            assertNull(ForegroundApplicationHint.iconBytes(java.util.Base64.getEncoder().encodeToString(bad)))
        }
    }

    private fun webpHeader(width: Int = 48): String {
        val bytes = ByteArray(26)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x46464952).putInt(4, 18).putInt(8, 0x50424557).putInt(12, 0x4c385056).putInt(16, 5)
        bytes[20] = 0x2f
        buffer.putInt(21, (width - 1) or (47 shl 14))
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
    @Test fun boundedIconEnvelopeReplacesAtomicallyAndNeverRevivesAnOldIcon() {
        val encoded = webpHeader()
        val current = ForegroundApplicationHint().receive(frame("1").put("icon_webp", encoded))
        assertEquals(encoded, current.iconWebp)
        assertEquals(current, current.receive(frame("1").put("icon_webp", JSONObject.NULL)))
        for (bad in listOf(webpHeader(49), "x".repeat(5468), "https://example/icon.webp", "!".repeat(44), encoded.dropLast(1))) {
            val changed = current.receive(frame("2", "Browser").put("icon_webp", bad))
            assertEquals("Browser", changed.name)
            assertNull(changed.iconWebp)
        }
        assertNull(current.receive(frame("2")).iconWebp)
        assertNull(current.receive(frame("2", JSONObject.NULL).put("icon_webp", encoded)).iconWebp)
        assertNull(current.receive(frame("bad")).iconWebp)
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
        val foreground = ready.copy(foregroundApplication = ForegroundApplication("Word", sequence = 1))
        assertNull(foreground.autoSendDestination)
        assertEquals("Word", foreground.copy(autoTextSupported = true).autoSendDestination?.application)
    }

}
