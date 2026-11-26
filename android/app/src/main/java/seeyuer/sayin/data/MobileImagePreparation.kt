package seeyuer.sayin.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** Frozen bytes are previewed, encrypted and uploaded unchanged. No source URI
 * becomes a durable payload; this work belongs on a bounded IO preparation job. */
internal class PreparedMobileImage(val metadata: ImageMetadata, val bytes: ByteArray) : Closeable {
    override fun close() { bytes.fill(0) }
}

/** Reject APNG before a decoder can silently select its default frame. */
internal fun inspectImageContainer(bytes: ByteArray): ImageMime {
    require(bytes.size in 1..MAX_IMAGE_BYTES)
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
        // Require a complete container before decoding; no partial JPEGs.
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

internal inline fun <T> measureImageStage(noinline observer: ((String, Long) -> Unit)?, name: String, block: () -> T): T {
    if (observer == null) return block()
    val started = SystemClock.elapsedRealtimeNanos()
    return try { block() } finally { observer(name, SystemClock.elapsedRealtimeNanos() - started) }
}

internal fun prepareMobileImage(
    source: ByteArray,
    onStage: ((String, Long) -> Unit)? = null,
): PreparedMobileImage {
    val mime = measureImageStage(onStage, "container_validation") { inspectImageContainer(source) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    measureImageStage(onStage, "bounds_decode") { BitmapFactory.decodeByteArray(source, 0, source.size, bounds) }
    fun validDimensions(width: Int, height: Int) {
        require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE && width.toLong() * height <= MAX_IMAGE_PIXELS)
    }
    validDimensions(bounds.outWidth, bounds.outHeight)
    require(bounds.outMimeType == mime.wire)
    val decoded = measureImageStage(onStage, "pixel_decode") { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
        validDimensions(info.size.width, info.size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    } }
    try {
        // ImageDecoder applies EXIF exactly once on every supported OS.
        validDimensions(decoded.width, decoded.height)
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
            measureImageStage(onStage, "pixel_encode") {
                require(decoded.compress(if (mime == ImageMime.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, output))
                output.toByteArray()
            }
        } finally { output.wipe() }
        val digest = measureImageStage(onStage, "sha256") { imageDigest(bytes) }
        val metadata = ImageMetadata(bytes.size, mime, decoded.width, decoded.height, digest)
        return PreparedMobileImage(metadata, bytes)
    } finally {
        decoded.recycle()
    }
}
