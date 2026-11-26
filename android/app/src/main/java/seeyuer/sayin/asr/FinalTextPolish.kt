package seeyuer.sayin.asr

import android.content.res.AssetManager

/**
 * Final-text pipeline shared by every local engine: hotword correction, then ITN, then
 * formatting. Partial results are never processed; only the finished transcript is.
 */
internal class FinalTextPolish(private val hotwords: HotwordNormalizer) {
    fun apply(raw: String): String {
        if (raw.isEmpty()) return raw
        return TextFormat.clean(LocalItn.normalize(hotwords.apply(raw)))
    }

    companion object {
        fun parseDictionary(text: String): List<Pair<String, String>> =
            text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                val wrong = parts.getOrNull(0)?.trim().orEmpty()
                val correct = parts.getOrNull(1)?.trim().orEmpty()
                if (wrong.isEmpty() || correct.isEmpty()) null else wrong to correct
            }.toList()

        fun of(dictionary: List<Pair<String, String>>): FinalTextPolish = FinalTextPolish(HotwordNormalizer(dictionary))

        fun load(assets: AssetManager): FinalTextPolish = of(
            parseDictionary(assets.open("hotwords.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }),
        )
    }
}
