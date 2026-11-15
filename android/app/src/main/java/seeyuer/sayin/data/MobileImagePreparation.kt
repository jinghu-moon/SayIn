package seeyuer.sayin.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** Frozen bytes are previewed, encrypted and uploaded unchanged. No source URI
 * becomes a durable payload; this work belongs on a bounded IO preparation job. */
internal class PreparedMobileImage(val metadata: ImageMetadata, val bytes: ByteArray) : Closeable {
    override fun close() { bytes.fill(0) }
}

/** Reject APNG before BitmapFactory can silently select its default frame. */
internal fun inspectImageContainer(bytes: ByteArray): ImageMime {
    require(bytes.size in 1..MAX_IMAGE_BYTES)
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
        // BitmapFactory may return partial pixels for a truncated JPEG. Require
        // an actual terminal marker before its permissive legacy decoder.
        require(bytes.size >= 5 && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        return ImageMime.JPEG
    }
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(signature))
    var offset = 8
    var ended = false
    while (offset <= bytes.size - 12) {
        val length = ByteBuffer.wrap(bytes, offset, 4).int
        require(length >= 0 && length <= bytes.size - offset - 12)
        val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
        require(type != "acTL")
        if (offset == 8) require(type == "IHDR" && length == 13)
        offset += length + 12
        if (type == "IEND") { require(length == 0); ended = true; break }
    }
    require(ended && offset == bytes.size)
    return ImageMime.PNG
}

internal fun prepareMobileImage(source: ByteArray): PreparedMobileImage {
    val mime = inspectImageContainer(source)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    fun validDimensions(width: Int, height: Int) {
        require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE && width.toLong() * height <= MAX_IMAGE_PIXELS)
    }
    validDimensions(bounds.outWidth, bounds.outHeight)
    require(bounds.outMimeType == mime.wire)
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
    val decoded = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
        validDimensions(info.size.width, info.size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    } else requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size, options))
    var oriented = decoded
    try {
        // ImageDecoder already applies EXIF; legacy BitmapFactory does not.
        val orientation = if (Build.VERSION.SDK_INT >= 28) ExifInterface.ORIENTATION_NORMAL else
            ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, false)
        validDimensions(oriented.width, oriented.height)
        // Bitmap compression writes pixels, not the source EXIF/GPS. Preserve
        // PNG transparency; JPEG is normalized once, then preview those bytes.
        val output = object : ByteArrayOutputStream() {
            override fun write(value: ByteArray, offset: Int, length: Int) {
                require(length >= 0 && size() <= MAX_IMAGE_BYTES - length)
                super.write(value, offset, length)
            }
            override fun write(value: Int) { require(size() < MAX_IMAGE_BYTES); super.write(value) }
            fun wipe() { buf.fill(0) }
        }
        val bytes = try {
            require(oriented.compress(if (mime == ImageMime.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, output))
            output.toByteArray()
        } finally { output.wipe() }
        val metadata = ImageMetadata(bytes.size, mime, oriented.width, oriented.height, imageDigest(bytes))
        return PreparedMobileImage(metadata, bytes)
    } finally {
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
    }
}
