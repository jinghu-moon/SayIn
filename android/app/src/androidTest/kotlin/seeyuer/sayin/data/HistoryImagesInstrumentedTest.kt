package seeyuer.sayin.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class HistoryImagesInstrumentedTest {
    @Test fun encryptedImagePreviewAndReadOnlyClipboardProviderRespectHistoryVisibility() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "seeyuer.sayin.imagetest")
        val database = Room.databaseBuilder(context, OutboxDatabase::class.java, "outbox.db")
            .addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY).build()
        val store = ProtectedStore(context)
        val files = ImageFiles(File(context.noBackupFilesDir, "images/outbox"), store::encryptImage, store::decryptImage)
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(0x882277aa.toInt()) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val host = UUID.randomUUID().toString(); val device = UUID.randomUUID().toString(); val id = UUID.randomUUID().toString()
        val metadata = ImageMetadata(bytes.size, ImageMime.PNG, 800, 1200, imageDigest(bytes))
        val reference = files.write(host, device, metadata, bytes)
        val item = OutboxItem(id, host, device, "scope", null, 0, "injected", "1", null, System.currentTimeMillis(),
            kind = "image", imageResource = reference.resourceId, imageReference = store.encrypt(reference.json().toString().toByteArray()), imageByteCount = bytes.size)
        try {
            database.outbox().enqueue(item)
            val preview = HistoryImages.read(context, store, item, 256)
            assertTrue(preview.width <= 256 && preview.height <= 256)
            assertTrue(preview.hasAlpha()); preview.recycle()
            val uri = HistoryImages.uri(context, id)
            assertEquals("image/png", context.contentResolver.getType(uri))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertThrows(Exception::class.java) { context.contentResolver.openFileDescriptor(uri, "rw") }
            assertThrows(Exception::class.java) { context.contentResolver.openInputStream(Uri.parse("content://${context.packageName}.history-images/../secret")) }
            val provider = requireNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
            assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(uri, 0, 2000, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(uri, 0, 2000, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            database.outbox().hide(id)
            assertThrows(Exception::class.java) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            assertEquals("injected", database.outbox().find(id, host, device)!!.state)
        } finally {
            database.openHelper.writableDatabase.execSQL("DELETE FROM outbox WHERE id=?", arrayOf(id))
            database.close(); files.remove(reference.resourceId); bytes.fill(0)
        }
    }
}
