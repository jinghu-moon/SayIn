package seeyuer.sayin.translation

import java.io.Closeable
import java.io.File

/** Caller serializes decode and close. Cancellation may be signaled from the capture scope. */
class R2t2Native(
    model: File,
    projector: File,
    prefillThreads: Int = 4,
    gpuDecoder: Boolean = false,
    gpuProjector: Boolean = false,
) : Closeable {
    private val bindings = R2t2Bindings()
    private val handle = bindings.load(
        model.absolutePath.encodeToByteArray(), projector.absolutePath.encodeToByteArray(),
        prefillThreads, gpuDecoder, gpuProjector,
    )

    fun reset() = bindings.reset(handle)
    fun cancel() = bindings.cancel(handle)
    fun prioritizeFinal(revision: Int) = bindings.prioritizeFinal(handle, revision)

    fun decode(
        samples: FloatArray,
        prefix: String,
        final: Boolean,
        revision: Int,
        tokenBudget: Int,
        preview: R2t2Preview?,
        timings: LongArray? = null,
    ): ByteArray? = bindings.decode(handle, samples, prefix.encodeToByteArray(), final, revision, tokenBudget, preview, timings)

    override fun close() = bindings.destroy(handle)
}

fun interface R2t2Preview {
    fun publish(text: ByteArray)
}

private class R2t2Bindings {
    init { System.loadLibrary("sayin_translation") }
    external fun load(
        model: ByteArray, projector: ByteArray, prefillThreads: Int, gpuDecoder: Boolean, gpuProjector: Boolean,
    ): Long
    external fun reset(handle: Long)
    external fun cancel(handle: Long)
    external fun prioritizeFinal(handle: Long, revision: Int)
    external fun decode(
        handle: Long,
        samples: FloatArray,
        prefix: ByteArray,
        final: Boolean,
        revision: Int,
        tokenBudget: Int,
        preview: R2t2Preview?,
        timings: LongArray?,
    ): ByteArray?
    external fun destroy(handle: Long)
}
