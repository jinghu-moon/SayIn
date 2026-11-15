package seeyuer.sayin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore AES-256-GCM + AtomicFile storage for the quick-phrase library.
 *
 * The file lives in `noBackupFilesDir`, uses its own alias and AAD domain, and never shares the
 * pairing keys (and never the speaking-draft key). A file that exists without its key, a wrong key,
 * a truncated file or failed authentication throws instead of creating a new key or returning an
 * empty library.
 */
internal class EncryptedPhraseStorage(context: Context, suffix: String = "") : PhraseStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, storageFileName(suffix)))
    private val keyAlias = storageKeyAlias(suffix)

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        // Never create a fresh identity for an existing library: that would make the bytes
        // permanently undecryptable and look like a corrupt-but-resettable store.
        check(create && !file.baseFile.exists())
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    override fun load(): PhraseLibraryState? {
        val encrypted = try {
            file.openRead().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= PhraseRules.LIMIT_ENCODED_BYTES + 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error else return null
        }
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(AAD.toByteArray())
        return PhraseCodec.decode(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)))
    }

    override fun save(state: PhraseLibraryState) {
        val plain = PhraseCodec.encode(state)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(AAD.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    internal companion object {
        private const val AAD = "SayIn/phrases/v1"
        private const val FILE_PREFIX = "phrases"
        private const val ALIAS_PREFIX = "sayin.phrases.v1"

        fun storageFileName(suffix: String) = "$FILE_PREFIX$suffix.bin"

        fun storageKeyAlias(suffix: String) = "$ALIAS_PREFIX$suffix"
    }
}
