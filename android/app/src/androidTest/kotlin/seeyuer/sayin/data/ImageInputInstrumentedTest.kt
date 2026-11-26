package seeyuer.sayin.data

import org.json.JSONObject

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageInputInstrumentedTest {
    @Test fun jpegAllExifOrientationsAreAppliedOnceAndMetadataIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".imagetest"))
        val file = File(context.cacheDir, "exif-${UUID.randomUUID()}.jpg")
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())
        val expected = arrayOf(intArrayOf(0,1,2,3), intArrayOf(1,0,3,2), intArrayOf(3,2,1,0), intArrayOf(2,3,0,1),
            intArrayOf(0,2,1,3), intArrayOf(2,0,3,1), intArrayOf(3,1,2,0), intArrayOf(1,3,0,2))
        val bitmap = Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) bitmap.setPixel(x,y,colors[(if(y>=32)2 else 0)+(if(x>=48)1 else 0)])
        try {
            for (orientation in 1..8) {
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)) }
                androidx.exifinterface.media.ExifInterface(file).apply {
                    setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString())
                    setLatLong(30.0,120.0);saveAttributes()
                }
                prepareMobileImage(file.readBytes()).use { prepared ->
                    assertEquals(if(orientation>=5)64 else 96,prepared.metadata.width)
                    assertEquals(if(orientation>=5)96 else 64,prepared.metadata.height)
                    val decoded = BitmapFactory.decodeByteArray(prepared.bytes,0,prepared.bytes.size)
                    try {
                        val points = arrayOf(8 to 8, decoded.width-9 to 8, 8 to decoded.height-9, decoded.width-9 to decoded.height-9)
                        points.forEachIndexed { index, (x,y) ->
                            val actual=decoded.getPixel(x,y);val wanted=colors[expected[orientation-1][index]]
                            for(shift in intArrayOf(0,8,16)) assertTrue("EXIF $orientation corner $index",kotlin.math.abs((actual ushr shift and 255)-(wanted ushr shift and 255))<24)
                        }
                    } finally { decoded.recycle() }
                    val exif=androidx.exifinterface.media.ExifInterface(prepared.bytes.inputStream())
                    assertNull(exif.latLong)
                    // Re-encoded pixels are already upright. An absent/undefined
                    // orientation is valid too; no transform may remain in EXIF.
                    val outputOrientation = exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED,
                    )
                    assertTrue(outputOrientation in setOf(
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                    ))
                }
                val truncated=file.readBytes().let { it.copyOf(it.size-2) }
                assertThrows(IllegalArgumentException::class.java) { prepareMobileImage(truncated) }
            }
        } finally { bitmap.recycle();file.delete() }
    }
    private fun png(): ByteArray {
        val bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0,0,0x804060ff.toInt())
        return try { ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); output.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test fun actualBitmapPreparationAndKeystoreFilesPreserveFrozenPixelsAndRejectWrongIdentity() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".imagetest")) { "Use the isolated imageTest application" }
        val directory=File(target.noBackupFilesDir,"image-files-${UUID.randomUUID()}")
        val store=ProtectedStore(target)
        val files=ImageFiles(directory,store)
        val host=UUID.randomUUID().toString();val device=UUID.randomUUID().toString()
        store.save(JSONObject().put("host_id", host).put("pair_id", device))
        val source=png()
        try {
            prepareMobileImage(source).use { prepared ->
                assertEquals(3,prepared.metadata.width);assertEquals(2,prepared.metadata.height)
                val bitmap=BitmapFactory.decodeByteArray(prepared.bytes,0,prepared.bytes.size)
                assertEquals(0x804060ff.toInt(),bitmap.getPixel(0,0));bitmap.recycle()
                val reference=files.write(host,device,prepared.metadata,prepared.bytes)
                val file = directory.resolve("${reference.resourceId}.image")
                val ciphertext = file.readBytes()
                assertFalse(ciphertext.contentEquals(prepared.bytes))
                assertTrue(ciphertext.copyOfRange(0, 16).contentEquals("SAYIN-IMAGE-AE02".toByteArray()))
                val restored=ImageFiles(directory,store).read(host,device,reference)
                assertArrayEquals(prepared.bytes,restored);restored.fill(0)
                assertThrows(IllegalStateException::class.java) { files.read(UUID.randomUUID().toString(),device,reference) }
                assertThrows(IllegalStateException::class.java) { files.read(host,UUID.randomUUID().toString(),reference) }
                for (offset in listOf(16, 76, ciphertext.lastIndex)) {
                    val corrupt = ciphertext.copyOf()
                    corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
                    file.writeBytes(corrupt)
                    assertThrows(AEADBadTagException::class.java) { files.read(host, device, reference) }
                }
                val envelope = JSONObject().put("host_id", host).put("pair_id", device)
                    .put("resource", reference.json()).toString().toByteArray()
                val legacy = ByteBuffer.allocate(12 + envelope.size + prepared.bytes.size)
                    .put("SIIMAGE1".toByteArray()).putInt(envelope.size).put(envelope).put(prepared.bytes).array()
                try {
                    file.writeBytes(store.encryptImage(host, device, legacy))
                    val reopened = files.read(host, device, reference)
                    try { assertArrayEquals(prepared.bytes, reopened) } finally { reopened.fill(0) }
                } finally { legacy.fill(0) }
            }
            assertThrows(IllegalArgumentException::class.java) { prepareMobileImage("not an image".toByteArray()) }
        } finally { store.forget(host, device); source.fill(0);directory.deleteRecursively() }
    }

    @Test fun schemaUpgradeKeepsExistingOutboxAndSeparatesImageQuotaFromText() = runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".imagetest"))
        val name="image-migration-${UUID.randomUUID()}.db"
        val id=UUID.randomUUID().toString();val host=UUID.randomUUID().toString();val device=UUID.randomUUID().toString()
        target.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("CREATE TABLE outbox (id TEXT NOT NULL PRIMARY KEY,hostId TEXT NOT NULL,deviceId TEXT NOT NULL,target TEXT NOT NULL,payload BLOB,byteCount INTEGER NOT NULL,state TEXT NOT NULL,`order` TEXT,reason TEXT,createdAt INTEGER NOT NULL,transcript BLOB)")
            db.execSQL("INSERT INTO outbox VALUES(?,?,?,'scope',?,2,'saved',NULL,NULL,1,?)",arrayOf(id,host,device,byteArrayOf(1,2),byteArrayOf(3,4)))
            db.version=2
        }
        val database=Room.databaseBuilder(target,OutboxDatabase::class.java,name).addMigrations(OutboxDatabase.KEEP_LEDGER,OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY, OutboxDatabase.ADD_V3_RECEIPT_FIELDS, OutboxDatabase.SCOPE_REQUEST_KEYS).build()
        try {
            val dao=database.outbox();val original=checkNotNull(dao.find(id,host,device))
            assertEquals("text",original.kind);assertArrayEquals(byteArrayOf(1,2),original.payload)
            assertArrayEquals(byteArrayOf(3,4),original.transcript)
            assertNull(original.requestRev)
            assertNull(original.executionStage)
            assertFalse(original.confirmed)
            val image=OutboxItem(UUID.randomUUID().toString(),host,device,"scope",byteArrayOf(7),0,"uploading",null,null,2,
                kind="image",imageResource=UUID.randomUUID().toString(),imageReference=byteArrayOf(8),imageByteCount=MAX_IMAGE_BYTES)
            dao.enqueue(image)
            assertEquals(2,dao.pendingBytes());assertEquals(MAX_IMAGE_BYTES,dao.pendingImageBytes())
            try { dao.enqueue(image.copy(id=UUID.randomUUID().toString()));fail("Second pending image must be refused") } catch (_: IllegalStateException) { }
            dao.status(image.id,host,device,"cancelled",null,"image_not_submitted",true)
            assertEquals(0,dao.pendingImages());assertEquals(2,dao.pendingBytes())
        } finally { database.close();target.deleteDatabase(name) }
    }

    @Test fun batchSubmitsInOrderAndStopsAtInvalidSecondImage() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("fixtureImageBatch") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "image-batch-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val source = File(target.filesDir, "test-images/$namespace").apply { mkdirs() }
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
        try {
            suspend fun await(predicate: (ClientState) -> Boolean): ClientState = withTimeout(15_000) {
                repository.state.first { check(it.storageHealthy); predicate(it) }
            }
            fun photo(name: String, width: Int): android.net.Uri {
                val file = File(source, "$name.png")
                if (width == 0) file.writeBytes(byteArrayOf(0, 1, 2))
                else {
                    val bitmap = Bitmap.createBitmap(width, 32, Bitmap.Config.ARGB_8888)
                    try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { bitmap.recycle() }
                }
                return FileProvider.getUriForFile(target, target.packageName + ".fixtures", file)
            }
            val first = photo("first", 31)
            val second = photo("second", 34)
            val invalid = photo("broken", 0)
            val third = photo("third", 38)
            await { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            repository.pairCode(checkNotNull(arguments.getString("fixtureAddress")), checkNotNull(arguments.getString("fixturePort")),
                checkNotNull(arguments.getString("fixtureCode")))
            await { it.ready && it.imageSupported }

            suspend fun send(images: List<android.net.Uri>) {
                repository.selectImages(images)
                val ready = await { it.imageDraft != null && !it.imagePreparing && it.imageBatch?.total == images.size }
                repository.sendImage(checkNotNull(ready.imageDraft).image.resourceId)
            }
            if (arguments.getString("fixtureBatchPathReceipt") == "true") {
                send(listOf(first, second))
                val copied = await { it.imageBatch?.stopped == true && it.history.any { row -> row.state == "injected" } }
                assertEquals(ImageBatchProgress(total = 2, sent = 1, stopped = true), copied.imageBatch)
                assertEquals(ImageIssue.PATH_COPIED, copied.imageIssue)
                assertEquals(IMAGE_PATH_COPY_METHOD, copied.history.single().method)
                return@runBlocking
            }
            send(listOf(first, second))
            val complete = await { it.imageBatch?.sent == 2 && it.history.count { row -> row.state == "injected" } == 2 }
            val delivered = complete.history.filter { it.kind == "image" }.sortedBy { it.order?.toLong() }
            assertEquals(2, delivered.map { it.id }.distinct().size)
            assertEquals(listOf(31, 34), delivered.map { complete.historyImages[it.key]?.width })

            send(listOf(first, invalid, third))
            val stopped = await { it.imageBatch?.stopped == true && it.imageIssue == ImageIssue.INVALID }
            assertEquals(1, stopped.imageBatch?.sent)
            assertEquals(3, stopped.imageBatch?.total)
            val after = await { it.history.count { row -> row.state == "injected" } == 3 }
            assertEquals(listOf(31, 34, 31), after.history.sortedBy { it.order?.toLong() }
                .map { after.historyImages[it.key]?.width })
        } finally {
            repository.close()
            target.deleteDatabase(namespace + "outbox.db")
            target.deleteSharedPreferences(namespace + "paired_host")
            File(target.noBackupFilesDir, "images/${namespace}outbox").deleteRecursively()
            source.deleteRecursively()
        }
    }

    @Test fun pinnedDualWssImageAndCancellationAndRestartNeverReplay() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val code=args.getString("fixtureCode");assumeTrue("Run the isolated --images fixture",code!=null)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".imagetest"))
        val namespace="image-transport-${UUID.randomUUID()}-"
        val context=object:ContextWrapper(target) {
            override fun getApplicationContext(): Context=this
            override fun getDatabasePath(name:String)=target.getDatabasePath(namespace+name)
            override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(namespace+name,mode)
        }
        val source=File(target.filesDir,"test-images/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs();writeBytes(png()) }
        var repository=VoiceLinkRepository(context,HostDiscovery{_,_->emptyList()})
        suspend fun waitFor(predicate:(ClientState)->Boolean):ClientState=withTimeout(15000) {
            while(!predicate(repository.state.value)) { check(repository.state.value.storageHealthy);delay(20) }
            repository.state.value
        }
        try {
            waitFor{it.connection==Connection.OFFLINE};repository.foreground(true)
            repository.pairCode(args.getString("fixtureAddress")?:"127.0.0.1",args.getString("fixturePort")?:"17889",code!!)
            waitFor{it.ready&&it.imageSupported}
            repository.selectImage(FileProvider.getUriForFile(target,"${target.packageName}.fixtures",source))
            val prepared=waitFor{it.imageDraft!=null&&!it.imagePreparing}
            val image=checkNotNull(prepared.imageDraft).image
            repository.sendImage(image.resourceId)
            val delivered=waitFor{it.history.any{row->row.kind=="image"&&row.state=="injected"}}
            val first=delivered.history.single()
            assertNull(first.payload);assertEquals("image",first.kind);assertNotNull(first.imageReference)
            assertEquals(0,first.byteCount);assertEquals(image.resourceId,first.imageResource)
            val thumbnail = checkNotNull(repository.historyImage(first.key, 256))
            assertTrue(thumbnail.width <= 256 && thumbnail.height <= 256); thumbnail.recycle()
            waitFor { it.historyApplications[first.key]?.name == "Fixture Editor" }
            // A committed image spends the single-use draft; the sheet starts from the picker again.
            waitFor { it.imageDraft == null }
            repository.selectImage(FileProvider.getUriForFile(target,"${target.packageName}.fixtures",source))
            val second=waitFor{it.imageDraft!=null&&!it.imagePreparing}.imageDraft!!.image
            repository.sendImage(second.resourceId);repository.cancelImage()
            waitFor{it.history.any{row->row.state=="cancelled"&&row.reason=="image_not_submitted"}}
            assertEquals(2,repository.state.value.history.size)
            // An uncommitted draft still survives a process restart.
            repository.selectImage(FileProvider.getUriForFile(target,"${target.packageName}.fixtures",source))
            val pendingDraft=waitFor {
                val draft = it.imageDraft
                draft != null && draft.image.resourceId != second.resourceId && !it.imagePreparing
            }.imageDraft!!.image
            val paired=PairedHost(checkNotNull(ProtectedStore(context).load()))
            repository.foreground(false);waitFor{it.connection==Connection.OFFLINE};repository.close();delay(200)
            val database=Room.databaseBuilder(context,OutboxDatabase::class.java,"outbox.db").addMigrations(OutboxDatabase.KEEP_LEDGER, OutboxDatabase.ADD_IMAGES, OutboxDatabase.ADD_HISTORY_DISPLAY, OutboxDatabase.ADD_V3_RECEIPT_FIELDS, OutboxDatabase.SCOPE_REQUEST_KEYS).build()
            val pendingId=UUID.randomUUID().toString()
            try {
                database.outbox().enqueue(OutboxItem(pendingId,paired.hostId,paired.deviceId,checkNotNull(prepared.target),
                    ProtectedStore(context).encrypt(paired.hostId,paired.pairId,"{\"kind\":\"image_pending\"}".toByteArray()),0,"uploading",null,null,System.currentTimeMillis(),
                    kind="image",imageResource=pendingDraft.resourceId,
                    imageReference=ProtectedStore(context).encrypt(paired.hostId,paired.pairId,
                        pendingDraft.json().toString().toByteArray()),imageByteCount=pendingDraft.metadata.bytes))
            } finally {database.close()}
            repository=VoiceLinkRepository(context,HostDiscovery{_,_->emptyList()})
            waitFor{it.connection==Connection.OFFLINE};repository.foreground(true)
            val restored=waitFor{it.ready&&!it.recovering&&it.history.any{row->row.id==pendingId&&row.state=="review"}}
            assertEquals(pendingDraft,restored.imageDraft!!.image)
            assertNotNull(restored.history.single{it.id==pendingId}.payload)
            repository.action(restored.history.single { it.id == pendingId }.key,"retry");delay(250)
            assertEquals("review",repository.state.value.history.single{it.id==pendingId}.state)
            assertEquals(1,repository.state.value.history.count{it.state=="injected"})
        } finally {
            repository.close();delay(200)
            target.deleteDatabase(namespace+"outbox.db");target.deleteSharedPreferences(namespace+"paired_host")
            File(target.noBackupFilesDir,"images/${namespace}outbox").deleteRecursively();source.delete()
        }
    }
}

internal fun VoiceLinkRepository.selectImage(uri: android.net.Uri) = selectImages(listOf(uri))
