package seeyuer.sayin.translation

import java.io.Closeable
import java.io.File

/** Caller serializes decode and close. Cancellation may be signaled from the capture scope. */
class R2t2Native(model: File, projector: File) : Closeable {
    private val bindings = R2t2Bindings()
    private val handle = bindings.load(model.absolutePath.encodeToByteArray(), projector.absolutePath.encodeToByteArray())

    fun reset() = bindings.reset(handle)
    fun cancel() = bindings.cancel(handle)
    fun finishInput() = bindings.finishInput(handle)

    fun decode(samples: FloatArray, prefix: String, final: Boolean, preview: R2t2Preview?): ByteArray? =
        bindings.decode(handle, samples, prefix.encodeToByteArray(), final, preview)

    override fun close() = bindings.destroy(handle)
}

fun interface R2t2Preview {
    fun publish(text: ByteArray)
}

private class R2t2Bindings {
    init { System.loadLibrary("sayin_translation") }
    external fun load(model: ByteArray, projector: ByteArray): Long
    external fun reset(handle: Long)
    external fun cancel(handle: Long)
    external fun finishInput(handle: Long)
    external fun decode(handle: Long, samples: FloatArray, prefix: ByteArray, final: Boolean, preview: R2t2Preview?): ByteArray?
    external fun destroy(handle: Long)
}
