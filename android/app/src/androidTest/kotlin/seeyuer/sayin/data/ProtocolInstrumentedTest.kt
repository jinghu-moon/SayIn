package seeyuer.sayin.data

import android.graphics.BitmapFactory
import android.util.JsonWriter
import android.util.MalformedJsonException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter
import java.net.Inet4Address

@RunWith(AndroidJUnit4::class)
class ProtocolInstrumentedTest {
    @Test fun sharedReceiptKeepsExactWireBytesAndDisplayBoundaries() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val withIcon = assets.open("status-budget-with-icon.json").use { it.readBytes() }
        val withoutIcon = assets.open("status-budget-icon-omitted.json").use { it.readBytes() }
        assertEquals(8192, withIcon.size)
        for (bytes in listOf(withIcon, withoutIcon)) {
            val receipt = strictJson(bytes.toString(Charsets.UTF_8))
            val execution = receipt.getJSONObject("execution")
            assertEquals("injected", receipt.getString("st"))
            assertEquals("input_submitted", execution.getString("stage"))
            assertEquals("unicode", execution.getString("method"))
            assertEquals(false, execution.getBoolean("confirmed"))
            val application = receipt.getJSONObject("application")
            val display = checkNotNull(HistoryApplication.parse(application))
            assertEquals(96, display.name.codePointCount(0, display.name.length))
            if (bytes === withIcon) {
                val icon = checkNotNull(ForegroundApplicationHint.iconBytes(checkNotNull(display.iconWebp)))
                assertEquals(4096, icon.size)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(icon, 0, icon.size))
                try {
                    assertEquals(48, bitmap.width)
                    assertEquals(48, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            } else {
                assertNull(display.iconWebp)
            }

            // The client consumes status frames. Re-encode the parsed fixture
            // with canonical key order to compare the shared wire representation.
            val output = StringWriter()
            JsonWriter(output).use { it.writeReceiptObject(receipt) }
            assertArrayEquals(bytes, output.toString().toByteArray(Charsets.UTF_8))
            application.put("name", display.name + "😀")
            assertNull(HistoryApplication.parse(application))
        }
    }

    private fun JsonWriter.writeReceiptObject(value: JSONObject) {
        beginObject()
        for (key in value.keys().asSequence().sorted()) {
            name(key)
            when (val field = value.get(key)) {
                is JSONObject -> writeReceiptObject(field)
                is String -> value(field)
                is Boolean -> value(field)
                is Number -> value(field)
                else -> error("Unexpected receipt fixture field: $key")
            }
        }
        endObject()
    }

    @Test fun jsonRejectsDuplicateKeysTrailingDocumentsAndDeepValues() {
        assertThrows(IllegalArgumentException::class.java) { strictJson("{\"v\":2,\"v\":2}") }
        assertThrows(MalformedJsonException::class.java) { strictJson("{} {}") }
        assertThrows(IllegalArgumentException::class.java) { strictJson("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":1}}}}}}}}}") }
    }

    @Test fun textNormalizationAndBoundsAreExplicit() {
        assertEquals("a b c d", normalizedText("a\r\nb\nc\td"))
        assertThrows(IllegalArgumentException::class.java) { normalizedText("bad\u0000text") }
        assertThrows(IllegalArgumentException::class.java) { normalizedText("😀".repeat(4001)) }
    }

    @Test fun endpointAcceptsOnlyCanonicalNumericIpv4() {
        assertEquals(Inet4Address.getByName("192.168.1.2"), numericAddress("192.168.1.2"))
        for (value in listOf("host.local", "192.168.001.2", "192.168.1.256", "127.0.0.1/24")) {
            assertThrows(IllegalArgumentException::class.java) { numericAddress(value) }
        }
        assertEquals("192.168.1.2", (numericAddress("192.168.1.2") as Inet4Address).hostAddress)
    }

    @Test fun identifierIsCanonicalUuidV4() {
        val value = "12345678-1234-4234-8234-123456789012"
        assertEquals(value, validId(value))
        assertThrows(IllegalArgumentException::class.java) { validId("12345678-1234-1234-8234-123456789012") }
    }
}
