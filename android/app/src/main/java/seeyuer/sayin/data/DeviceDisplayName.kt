package seeyuer.sayin.data

import android.os.Build
import seeyuer.sayin.pairing.NativePairing

/** Display metadata only. Call from the repository's IO dispatcher, never composition. */
internal object DeviceDisplayName {
    private val marketKeys = listOf(
        "ro.product.marketname",
        "ro.product.vendor.marketname",
        "ro.product.odm.marketname",
    )

    fun current(): String = resolve(Build.MODEL) { NativePairing.readDeviceMarketName(it) }

    internal fun resolve(model: String?, readProperty: (String) -> String?): String {
        for (key in marketKeys) {
            val candidate = clean(try {
                readProperty(key)
            } catch (_: LinkageError) {
                null
            } catch (_: RuntimeException) {
                null
            })
            if (candidate.isNotEmpty()) return candidate
        }
        return clean(model).ifEmpty { "Android" }
    }

    /** Match Rust chars(): preserve whole Unicode scalars, drop controls/unpaired surrogates. */
    internal fun clean(value: String?): String {
        val cleaned = buildString {
            value?.codePoints()?.forEach { codePoint ->
                if (!Character.isISOControl(codePoint) && codePoint !in 0xD800..0xDFFF) {
                    appendCodePoint(codePoint)
                }
            }
        }.trim()
        val end = cleaned.offsetByCodePoints(0, minOf(64, cleaned.codePointCount(0, cleaned.length)))
        return cleaned.substring(0, end).trimEnd()
    }
}
