package seeyuer.sayin.data

import org.junit.Assert.*
import org.junit.Test

class RootClipboardProtocolTest {
    private fun parse(entries: String = """[{"id":2,"timestamp":123,"preview":"你好🙂"}]""", revision: String = "1:3") =
        RootClipboardProtocol.list(RootClipboardProtocol.response("""{"protocol":1,"revision":"$revision","capture_ready":true,"entries":$entries}"""))
    @Test fun boundedMetadata() {
        val value = parse()
        assertEquals("1:3", value.revision)
        assertEquals("你好🙂", value.entries.single().preview)
        assertTrue(value.captureReady)
    }
    @Test fun rejectsUntrustedOrMalformedResponses() {
        fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) {} }
        reject { RootClipboardProtocol.response("""{"error":"client_not_authorized"}""") }
        reject { RootClipboardProtocol.response("""{"protocol":2}""") }
        reject { parse(revision = "1 3") }
        reject { parse("""[{"id":0,"timestamp":123,"preview":"x"}]""") }
        reject { parse("""[{"id":2,"timestamp":123,"preview":"x"},{"id":2,"timestamp":123,"preview":"x"}]""") }
        reject { parse("""[{"id":1,"timestamp":123,"preview":"${"x".repeat(161)}"}]""") }
        reject { parse("[" + (1..51).joinToString(",") { """{"id":${52-it},"timestamp":123,"preview":"x"}""" } + "]") }
    }
}
