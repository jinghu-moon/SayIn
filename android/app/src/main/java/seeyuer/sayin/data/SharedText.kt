package seeyuer.sayin.data

import android.content.Context
import android.content.Intent

/** Reads the first shared text payload without trusting the MIME type alone. */
internal fun Intent.sharedText(context: Context): String? {
    if (action != Intent.ACTION_SEND) return null
    val mime = type
    if (mime != null && !mime.startsWith("text/")) return null
    val direct = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    val item = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val fallback = runCatching { item?.coerceToText(context)?.toString() }.getOrNull()
    return (direct ?: fallback)?.trim()?.takeIf { it.isNotEmpty() }
}
