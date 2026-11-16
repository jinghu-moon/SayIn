package seeyuer.sayin.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Ephemeral display snapshot; never persisted or used as an input destination. */
internal data class ForegroundApplication(val name: String, val icon: Bitmap? = null) {
    companion object {
        /** Repository actor calls this on IO, never during composition. */
        fun from(hint: ForegroundApplicationHint): ForegroundApplication? {
            val name = hint.name ?: return null
            val bytes = hint.iconPng?.let(ForegroundApplicationHint::iconBytes)
            val icon = bytes?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                if (bounds.outWidth != 48 || bounds.outHeight != 48 || bounds.outMimeType != "image/png") null
                else BitmapFactory.decodeByteArray(it, 0, it.size)?.takeIf { bitmap -> bitmap.width == 48 && bitmap.height == 48 }
            }
            return ForegroundApplication(name, icon)
        }
    }
}
