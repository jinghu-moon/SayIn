package seeyuer.sayin.asr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance suites for the final-text stages. Fixtures mirror the refer/voice corpora.
 */
private data class CorpusRow(val id: String, val input: String, val expected: List<String>, val kind: String)

private fun String.unescapeCorpus() = replace("\\n", "\n").replace("<EMPTY>", "")

private fun corpus(name: String): List<CorpusRow> {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/voice/$name")) { "missing $name" }
    return stream.bufferedReader(Charsets.UTF_8).readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        val parts = line.split('\t')
        CorpusRow(parts[0], parts[1].unescapeCorpus(), parts[2].unescapeCorpus().split('|'), parts[3])
    }
}

private fun assertCorpus(rows: List<CorpusRow>, transform: (String) -> String) {
    val failures = mutableListOf<String>()
    for (row in rows) {
        val out = transform(row.input)
        val ok = if (row.kind == "EITHER") out in row.expected else out == row.expected[0]
        if (!ok) failures += "${row.id} ${row.kind} '${row.input}' -> '$out' want ${row.expected}"
    }
    assertTrue("${failures.size}/${rows.size} cases failed:\n" + failures.joinToString("\n"), failures.isEmpty())
}

private fun dictionary(): List<Pair<String, String>> {
    val file = File("src/main/assets/hotwords.tsv")
    assertTrue("hotword dictionary missing at ${file.absolutePath}", file.isFile)
    return FinalTextPolish.parseDictionary(file.readText(Charsets.UTF_8))
}

class HotwordCorpusTest {
    @Test fun matchesEveryCorpusCase() {
        val normalizer = HotwordNormalizer(dictionary())
        assertCorpus(corpus("hotword.tsv")) { normalizer.apply(it) }
    }

    @Test fun isIdempotent() {
        val normalizer = HotwordNormalizer(dictionary())
        val failures = corpus("hotword.tsv").mapNotNull { row ->
            val once = normalizer.apply(row.input)
            val twice = normalizer.apply(once)
            if (twice != once) "${row.id} once='$once' twice='$twice'" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

class FormatCorpusTest {
    @Test fun matchesEveryCorpusCase() {
        assertCorpus(corpus("format.tsv")) { TextFormat.clean(it) }
    }
}

class PipelineCorpusTest {
    @Test fun matchesEveryCorpusCase() {
        val polish = FinalTextPolish.of(dictionary())
        assertCorpus(corpus("pipeline.tsv")) { polish.apply(it) }
    }

    @Test fun isIdempotent() {
        val polish = FinalTextPolish.of(dictionary())
        val failures = corpus("pipeline.tsv").mapNotNull { row ->
            val once = polish.apply(row.input)
            val twice = polish.apply(once)
            if (twice != once) "${row.id} once='$once' twice='$twice'" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
