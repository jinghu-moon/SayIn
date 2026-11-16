package seeyuer.sayin.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Same encrypted, identity-bound files as the Outbox. No plaintext image cache. */
internal object HistoryImages {
    private fun source(context: Context, store: ProtectedStore, item: OutboxItem): Pair<ImageReference, ByteArray> {
        require(item.kind == "image" && !item.historyHidden)
        val raw = store.decrypt(requireNotNull(item.imageReference))
        val reference = try { ImageReference.parse(strictJson(raw.toString(Charsets.UTF_8))) } finally { raw.fill(0) }
        require(reference.resourceId == item.imageResource)
        val files = ImageFiles(File(context.noBackupFilesDir, "images/${context.getDatabasePath("outbox.db").nameWithoutExtension}"), store::encryptImage, store::decryptImage)
        return reference to files.read(item.hostId, item.deviceId, reference)
    }

    fun bytes(context: Context, store: ProtectedStore, item: OutboxItem): Pair<ImageReference, ByteArray> = source(context, store, item)

    fun read(context: Context, store: ProtectedStore, item: OutboxItem, edge: Int): Bitmap {
        val (reference, bytes) = source(context, store, item)
        try {
            var sample = 1
            while (maxOf(reference.metadata.width, reference.metadata.height) / sample > edge) sample *= 2
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; inScaled = false
            }))
        } finally { bytes.fill(0) }
    }

    fun uri(context: Context, id: String): Uri = Uri.Builder().scheme("content").authority("${context.packageName}.history-images")
        .appendPath(validId(id)).build()
}

/** Clipboard readers receive a read-only URI grant. Decryption streams through
 * a bounded pipe; no path, key, plaintext file or protocol action is exported. */
class HistoryImageProvider : ContentProvider() {
    private val slots = Semaphore(2)
    private val workers = Executors.newFixedThreadPool(2)
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val app get() = requireNotNull(context).applicationContext
    private val database by lazy {
        Room.databaseBuilder(app, OutboxDatabase::class.java, "outbox.db")
            .addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY).build()
    }
    override fun onCreate() = true
    private fun id(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority == "${app.packageName}.history-images" && uri.query == null && uri.fragment == null && uri.pathSegments.size == 1)
        return validId(uri.pathSegments.single())
    }
    private fun item(uri: Uri): OutboxItem = runBlocking(Dispatchers.IO) {
        database.outbox().visible(id(uri))?.takeIf { it.kind == "image" } ?: throw FileNotFoundException()
    }
    override fun getType(uri: Uri): String {
        val row = item(uri)
        val store = ProtectedStore(app)
        val raw = store.decrypt(requireNotNull(row.imageReference))
        return try { ImageReference.parse(strictJson(raw.toString(Charsets.UTF_8))).metadata.mime.wire } finally { raw.fill(0) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        try { id(uri) } catch (_: Exception) { throw FileNotFoundException() }
        if (!slots.tryAcquire()) throw FileNotFoundException("Busy")
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (failure: Exception) { slots.release(); throw failure }
        // A recipient that never reads cannot occupy both workers indefinitely.
        val deadline = deadlines.schedule({ runCatching { pipe[1].closeWithError("Read timeout") } }, 15, TimeUnit.SECONDS)
        workers.execute {
            try {
                val (_, bytes) = HistoryImages.bytes(app, ProtectedStore(app), item(uri))
                try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
                finally { bytes.fill(0) }
            } catch (_: Exception) { runCatching { pipe[1].closeWithError("Image unavailable") } }
            finally { deadline.cancel(false); slots.release() }
        }
        return pipe[0]
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val row = item(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).filter { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) row.imageByteCount else "SayIn image" }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { deadlines.shutdownNow(); workers.shutdownNow(); database.close(); super.shutdown() }
}
