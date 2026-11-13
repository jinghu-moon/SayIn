package seeyuer.sayin.data

import android.util.MalformedJsonException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address

@RunWith(AndroidJUnit4::class)
class ProtocolInstrumentedTest {
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
