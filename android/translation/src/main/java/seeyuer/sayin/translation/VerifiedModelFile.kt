package seeyuer.sayin.translation

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class ModelIntegrityException : Exception()

/** Copies into a private staging path. Only its caller publishes the verified artifact. */
suspend fun copyVerifiedModelFile(
    input: InputStream,
    destination: File,
    expectedBytes: Long,
    sha256: String,
    progress: (Long) -> Unit,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    FileOutputStream(destination).use { output ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > expectedBytes) throw ModelIntegrityException()
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            progress(copied)
        }
        if (copied != expectedBytes || digest.digest().joinToString("") { "%02x".format(it) } != sha256) {
            throw ModelIntegrityException()
        }
        output.fd.sync()
    }
}
