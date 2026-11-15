package seeyuer.sayin.data

import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outbox")
internal data class OutboxItem(
    @PrimaryKey val id: String,
    val hostId: String,
    val deviceId: String,
    val target: String,
    val payload: ByteArray?,
    val byteCount: Int,
    val state: String,
    val order: String?,
    val reason: String?,
    val createdAt: Long,
    val transcript: ByteArray? = null,
    @ColumnInfo(defaultValue = "'text'") val kind: String = "text",
    val imageResource: String? = null,
    val imageReference: ByteArray? = null,
    @ColumnInfo(defaultValue = "0") val imageByteCount: Int = 0,
)

@Entity(tableName = "image_drafts", primaryKeys = ["hostId", "deviceId"])
internal data class ImageDraftEntity(val hostId: String, val deviceId: String, val resourceId: String, val reference: ByteArray, val createdAt: Long)

@Dao
internal interface OutboxDao {
    @Insert suspend fun insert(item: OutboxItem)
    @Query("SELECT COUNT(*) FROM outbox WHERE payload IS NOT NULL") suspend fun pendingCount(): Int
    @Query("SELECT COALESCE(SUM(byteCount),0) FROM outbox WHERE payload IS NOT NULL") suspend fun pendingBytes(): Int
    @Query("SELECT COUNT(*) FROM outbox WHERE kind='image' AND payload IS NOT NULL") suspend fun pendingImages(): Int
    @Query("SELECT COUNT(*) FROM outbox WHERE hostId=:hostId AND deviceId=:deviceId AND kind='image' AND payload IS NOT NULL") suspend fun pendingImagesFor(hostId: String, deviceId: String): Int
    @Query("SELECT COALESCE(SUM(imageByteCount),0) FROM outbox WHERE kind='image' AND payload IS NOT NULL") suspend fun pendingImageBytes(): Int
    @Transaction suspend fun enqueue(item: OutboxItem): List<String> {
        check(pendingCount() < 16 && pendingBytes() + item.byteCount <= 65536)
        check(item.kind in setOf("text", "image"))
        if (item.kind == "image") {
            check(item.byteCount == 0 && item.imageResource != null && item.imageReference != null && item.imageByteCount in 1..MAX_IMAGE_BYTES)
            check(pendingImagesFor(item.hostId, item.deviceId) < 1 && pendingImageBytes().toLong() + item.imageByteCount <= 3L * MAX_IMAGE_BYTES)
        } else check(item.imageResource == null && item.imageReference == null && item.imageByteCount == 0)
        insert(item)
        return trimTerminal()
    }
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT 100") fun observe(): Flow<List<OutboxItem>>
    @Query("SELECT * FROM outbox WHERE hostId=:hostId AND deviceId=:deviceId AND payload IS NOT NULL ORDER BY createdAt,id") suspend fun pending(hostId: String, deviceId: String): List<OutboxItem>
    @Query("SELECT * FROM outbox WHERE id=:id AND hostId=:hostId AND deviceId=:deviceId LIMIT 1") suspend fun find(id: String, hostId: String, deviceId: String): OutboxItem?
    @Query("UPDATE outbox SET state=:state,`order`=:order,reason=:reason,payload=CASE WHEN :terminal THEN NULL ELSE payload END,byteCount=CASE WHEN :terminal THEN 0 ELSE byteCount END WHERE id=:id AND hostId=:hostId AND deviceId=:deviceId")
    suspend fun status(id: String, hostId: String, deviceId: String, state: String, order: String?, reason: String?, terminal: Boolean)
    @Query("SELECT DISTINCT imageResource FROM outbox WHERE imageResource IS NOT NULL AND payload IS NULL AND id NOT IN (SELECT id FROM outbox ORDER BY createdAt DESC LIMIT 100)") suspend fun terminalGarbageImages(): List<String>
    @Query("DELETE FROM outbox WHERE payload IS NULL AND id NOT IN (SELECT id FROM outbox ORDER BY createdAt DESC LIMIT 100)") suspend fun deleteTerminalGarbage()
    @Transaction suspend fun trimTerminal(): List<String> {
        val candidates = terminalGarbageImages()
        deleteTerminalGarbage()
        return candidates.filter { !imageReferenced(it) }
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveImageDraft(item: ImageDraftEntity)
    @Query("SELECT * FROM image_drafts WHERE hostId=:hostId AND deviceId=:deviceId LIMIT 1") suspend fun imageDraft(hostId: String, deviceId: String): ImageDraftEntity?
    @Query("DELETE FROM image_drafts WHERE hostId=:hostId AND deviceId=:deviceId") suspend fun deleteImageDraft(hostId: String, deviceId: String)
    @Query("SELECT imageResource FROM outbox WHERE imageResource IS NOT NULL UNION SELECT resourceId FROM image_drafts") suspend fun imageResources(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE imageResource=:id UNION ALL SELECT 1 FROM image_drafts WHERE resourceId=:id)") suspend fun imageReferenced(id: String): Boolean
    @Query("UPDATE outbox SET payload=:payload,state='saved' WHERE id=:id AND hostId=:hostId AND deviceId=:deviceId AND kind='image' AND payload IS NOT NULL AND state='uploading'")
    suspend fun imageReady(id: String, hostId: String, deviceId: String, payload: ByteArray): Int
}

@Database(entities = [OutboxItem::class, ImageDraftEntity::class], version = 3, exportSchema = true)
internal abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    companion object {
        // Keep the user's paired identity and existing ledger; previous terminal text cannot be reconstructed.
        val ADD_IMAGES = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE outbox ADD COLUMN imageResource TEXT")
                db.execSQL("ALTER TABLE outbox ADD COLUMN imageReference BLOB")
                db.execSQL("ALTER TABLE outbox ADD COLUMN imageByteCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE image_drafts (hostId TEXT NOT NULL,deviceId TEXT NOT NULL,resourceId TEXT NOT NULL,reference BLOB NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(hostId,deviceId))")
            }
        }
        val KEEP_LEDGER = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbox ADD COLUMN transcript BLOB") }
        }
    }
}
