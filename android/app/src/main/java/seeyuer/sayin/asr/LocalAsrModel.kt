package seeyuer.sayin.asr

import android.content.res.AssetManager

/** The single local ASR artifact that is packaged and verified by the build. */
internal object LocalAsrModel {
    const val DIRECTORY = XAsrDecoder.MODEL
    const val SPEC_LABEL = "X-ASR 480ms"
    const val VERSION = "2026-06-05"
    val files = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    fun size(assets: AssetManager): Long = files.sumOf { name -> bytes(assets, "$DIRECTORY/$name") }

    fun bytes(assets: AssetManager, path: String): Long {
        runCatching { assets.openFd(path).use { return it.length } }
        return assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
    }
}
