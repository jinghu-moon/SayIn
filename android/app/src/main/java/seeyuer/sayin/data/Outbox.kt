package seeyuer.sayin.data

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
)

@Dao
internal interface OutboxDao {
    @Insert suspend fun insert(item: OutboxItem)
    @Query("SELECT COUNT(*) FROM outbox WHERE payload IS NOT NULL") suspend fun pendingCount(): Int
    @Query("SELECT COALESCE(SUM(byteCount),0) FROM outbox WHERE payload IS NOT NULL") suspend fun pendingBytes(): Int
    @Transaction suspend fun enqueue(item: OutboxItem) {
        check(pendingCount() < 16 && pendingBytes() + item.byteCount <= 65536)
        insert(item)
        trimTerminal()
    }
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT 100") fun observe(): Flow<List<OutboxItem>>
    @Query("SELECT * FROM outbox WHERE hostId=:hostId AND deviceId=:deviceId AND payload IS NOT NULL ORDER BY createdAt,id") suspend fun pending(hostId: String, deviceId: String): List<OutboxItem>
    @Query("SELECT * FROM outbox WHERE id=:id AND hostId=:hostId AND deviceId=:deviceId LIMIT 1") suspend fun find(id: String, hostId: String, deviceId: String): OutboxItem?
    @Query("UPDATE outbox SET state=:state,`order`=:order,reason=:reason,payload=CASE WHEN :terminal THEN NULL ELSE payload END,byteCount=CASE WHEN :terminal THEN 0 ELSE byteCount END WHERE id=:id AND hostId=:hostId AND deviceId=:deviceId")
    suspend fun status(id: String, hostId: String, deviceId: String, state: String, order: String?, reason: String?, terminal: Boolean)
    @Query("DELETE FROM outbox WHERE payload IS NULL AND id NOT IN (SELECT id FROM outbox ORDER BY createdAt DESC LIMIT 100)") suspend fun trimTerminal()
}

@Database(entities = [OutboxItem::class], version = 2, exportSchema = true)
internal abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    companion object {
        // Keep the user's paired identity and existing ledger; previous terminal text cannot be reconstructed.
        val KEEP_LEDGER = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbox ADD COLUMN transcript BLOB") }
        }
    }
}
