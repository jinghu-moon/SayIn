package seeyuer.sayin.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ImageTransferTest {
    @Test fun windowBoundsQueuedBytesAndRequiresExactCumulativeAcknowledgements() {
        val window = ImageSendWindow(IMAGE_CHUNK_BYTES * 4 + 7)
        repeat(4) { index -> assertEquals(index * IMAGE_CHUNK_BYTES until (index + 1) * IMAGE_CHUNK_BYTES, window.next()) }
        assertFalse(window.canSend)
        assertThrows(IllegalStateException::class.java) { window.next() }
        assertThrows(IllegalArgumentException::class.java) { window.acknowledge(IMAGE_CHUNK_BYTES * 2L) }
        window.acknowledge(IMAGE_CHUNK_BYTES.toLong())
        assertEquals(IMAGE_CHUNK_BYTES * 4 until IMAGE_CHUNK_BYTES * 4 + 7, window.next())
        for (end in 2..4) window.acknowledge(IMAGE_CHUNK_BYTES * end.toLong())
        assertFalse(window.complete)
        window.acknowledge((IMAGE_CHUNK_BYTES * 4 + 7).toLong())
        assertTrue(window.complete)
        assertFalse(window.canSend)
        assertThrows(IllegalArgumentException::class.java) { window.acknowledge(0) }
    }

    @Test fun formatAndAnimationAndContainerLengthsAreCheckedBeforeBitmapAllocation() {
        fun chunk(type: String, body: ByteArray) = ByteBuffer.allocate(body.size + 12).putInt(body.size).put(type.toByteArray()).put(body).putInt(0).array()
        val signature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        val header=chunk("IHDR", ByteArray(13))
        val end=chunk("IEND",byteArrayOf())
        val png=signature+header+end
        assertEquals(ImageMime.PNG,inspectImageContainer(png))
        assertEquals(ImageMime.JPEG,inspectImageContainer(byteArrayOf(255.toByte(),216.toByte(),255.toByte(),255.toByte(),217.toByte())))
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer(byteArrayOf(255.toByte(),216.toByte(),255.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer(signature+header+chunk("acTL",ByteArray(8))+end) }
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer(png.copyOf(png.size-1)) }
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer(png+byteArrayOf(0)) }
        val oversized=png.copyOf(); ByteBuffer.wrap(oversized,8,4).putInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer(oversized) }
        assertThrows(IllegalArgumentException::class.java) { inspectImageContainer("GIF89a".toByteArray()) }
    }
}
