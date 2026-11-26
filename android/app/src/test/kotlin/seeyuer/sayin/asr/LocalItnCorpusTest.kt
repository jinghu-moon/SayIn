package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance suite for final-text number normalization. The fixture mirrors
 * refer/voice/itn.tsv from the voice-corpus review materials.
 */
class LocalItnCorpusTest {
    private data class Case(val id: String, val input: String, val expected: List<String>, val kind: String)

    private fun cases(): List<Case> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/voice/itn.tsv")) { "missing itn.tsv" }
        return stream.bufferedReader(Charsets.UTF_8).readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            Case(parts[0], parts[1].replace("\\n", "\n"), parts[2].split('|'), parts[3])
        }
    }

    @Test fun matchesEveryCorpusCase() {
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val out = LocalItn.normalize(case.input)
            val ok = when (case.kind) {
                "EITHER" -> out in case.expected
                else -> out == case.expected[0]
            }
            if (!ok) failures += "${case.id} ${case.kind} '${case.input}' -> '$out' want ${case.expected}"
        }
        assertTrue("${failures.size} cases failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun isIdempotent() {
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val once = LocalItn.normalize(case.input)
            val twice = LocalItn.normalize(once)
            if (twice != once) failures += "${case.id} '${case.input}' once='$once' twice='$twice'"
        }
        assertTrue("${failures.size} cases are not idempotent:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun keepsExistingProductExamples() {
        assertEquals("会议定在2026年11月24号下午3点半。", LocalItn.normalize("会议定在二零二六年十一月二十四号下午三点半。"))
        assertEquals("可在设置选择2、2.5 或 3 秒。", LocalItn.normalize("可在设置选择二、二点五或三秒。"))
    }
}
