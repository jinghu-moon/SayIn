package seeyuer.sayin.data

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * One-shot adapter for the visible clipboard tab. It never registers a listener, polls, reads in
 * the background, resolves a URI or calls `coerceToText()`.
 *
 * [focused] must be the caller's verified window/panel focus; a background process returns
 * [ClipboardRead.Unavailable] instead of pretending the clipboard is empty or asking for a fake
 * clipboard permission.
 */
internal class AndroidClipboard(private val context: Context) {
    fun read(focused: Boolean): ClipboardRead {
        if (!focused) return ClipboardRead.Unavailable
        return try {
            val manager = context.getSystemService(ClipboardManager::class.java)
                ?: return ClipboardRead.Unavailable
            val clip = manager.primaryClip ?: return ClipboardRead.Unavailable
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            } else {
                false
            }
            if (clip.itemCount == 0) return ClipboardRead.Empty
            val item = clip.getItemAt(0)
            // Only the explicit plain-text field is text. URI/Intent/image items stay non-text.
            val text = item.text
            if (text != null && text.length > ClipboardText.MAX_UTF16_UNITS) return ClipboardRead.TooLong
            ClipboardText.classify(
                firstText = text?.toString(),
                hasUri = item.uri != null,
                hasIntent = item.intent != null,
                itemCount = clip.itemCount,
                sensitive = sensitive,
            )
        } catch (error: Exception) {
            ClipboardRead.Unavailable
        }
    }
}
