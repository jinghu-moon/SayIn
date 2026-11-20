package seeyuer.sayin.polish

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.UUID

internal const val POLISH_USAGE_RETENTION_DAYS = 90L
private const val POLISH_USAGE_RETENTION_MILLIS = POLISH_USAGE_RETENTION_DAYS * 24L * 60L * 60L * 1000L

internal enum class PolishCallPurpose { Polish, Translation, Test }

@Entity(tableName = "polish_usage")
internal data class PolishUsageEntity(
    @PrimaryKey val callId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMillis: Long,
    val configId: String,
    val configRevision: Long,
    val provider: String,
    val protocol: String,
    val purpose: String,
    val requestModel: String,
    val responseModel: String?,
    val httpStatus: Int?,
    val outcome: String,
    val inputTotal: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val outputTotal: Long?,
    val outputReasoning: Long?,
    val costSource: String,
    val costMicros: Long?,
    val costCurrency: String?,
    val catalogVersion: String?,
    val priceRule: String?,
)

@Dao
internal interface PolishUsageDao {
    @Insert
    suspend fun insert(entity: PolishUsageEntity)

    @Query("""
        UPDATE polish_usage SET finishedAt=:finishedAt, durationMillis=:durationMillis,
            httpStatus=:httpStatus, outcome=:outcome, responseModel=:responseModel,
            inputTotal=:inputTotal, cacheRead=:cacheRead, cacheWrite=:cacheWrite,
            outputTotal=:outputTotal, outputReasoning=:outputReasoning,
            costSource=:costSource, costMicros=:costMicros, costCurrency=:costCurrency,
            catalogVersion=:catalogVersion, priceRule=:priceRule
        WHERE callId=:callId AND outcome='pending'
    """)
    suspend fun finish(
        callId: String, finishedAt: Long, durationMillis: Long, httpStatus: Int?, outcome: String,
        responseModel: String?, inputTotal: Long?, cacheRead: Long?, cacheWrite: Long?,
        outputTotal: Long?, outputReasoning: Long?, costSource: String, costMicros: Long?,
        costCurrency: String?, catalogVersion: String?, priceRule: String?,
    ): Int

    @Query("""
        UPDATE polish_usage SET finishedAt=:finishedAt,
            durationMillis=MAX(0, :finishedAt - startedAt), outcome='cancelled'
        WHERE callId=:callId AND outcome='pending'
    """)
    suspend fun abandon(callId: String, finishedAt: Long): Int

    @Query("""
        UPDATE polish_usage SET finishedAt=:finishedAt,
            durationMillis=MAX(0, :finishedAt - startedAt), outcome='unknown'
        WHERE outcome='pending'
    """)
    suspend fun recoverPending(finishedAt: Long): Int

    @Query("DELETE FROM polish_usage WHERE startedAt < :cutoff")
    suspend fun prune(cutoff: Long): Int

    @Query("DELETE FROM polish_usage")
    suspend fun clear()

    @Query("SELECT * FROM polish_usage ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PolishUsageEntity>

    @Query("SELECT * FROM polish_usage WHERE callId=:callId")
    suspend fun byId(callId: String): PolishUsageEntity?
}

@Database(entities = [PolishUsageEntity::class], version = 2, exportSchema = false)
internal abstract class PolishUsageDatabase : RoomDatabase() {
    abstract fun dao(): PolishUsageDao
}

internal class PolishUsageStore(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext, PolishUsageDatabase::class.java, "polish-usage.db",
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .fallbackToDestructiveMigration(true).build()
    private val dao = database.dao()

    suspend fun recover() {
        val now = System.currentTimeMillis()
        dao.recoverPending(now)
        dao.prune(now - POLISH_USAGE_RETENTION_MILLIS)
    }

    suspend fun begin(config: PolishConfig, purpose: PolishCallPurpose): PolishUsageEntity {
        val now = System.currentTimeMillis()
        return PolishUsageEntity(
            callId = UUID.randomUUID().toString(), startedAt = now, finishedAt = null,
            durationMillis = 0L, configId = config.id, configRevision = config.revision,
            provider = config.provider.id, protocol = config.protocol.name,
            purpose = purpose.name.lowercase(), requestModel = config.model,
            responseModel = null, httpStatus = null, outcome = "pending",
            inputTotal = null, cacheRead = null, cacheWrite = null, outputTotal = null,
            outputReasoning = null, costSource = PolishCostSource.Unknown.name,
            costMicros = null, costCurrency = null, catalogVersion = null, priceRule = null,
        ).also { dao.insert(it) }
    }

    suspend fun finish(callId: String, result: PolishCallResult, outcome: String, price: PolishPrice?) {
        val usage = when (result) {
            is PolishCallResult.Success -> result.usage
            is PolishCallResult.Failure -> result.usage
        }
        dao.finish(
            callId = callId,
            finishedAt = System.currentTimeMillis(),
            durationMillis = when (result) {
                is PolishCallResult.Success -> result.durationMillis
                is PolishCallResult.Failure -> result.durationMillis
            },
            httpStatus = when (result) {
                is PolishCallResult.Success -> result.httpStatus
                is PolishCallResult.Failure -> result.httpStatus
            },
            outcome = outcome,
            responseModel = null,
            inputTotal = usage?.inputTotal,
            cacheRead = usage?.cacheRead,
            cacheWrite = usage?.cacheWrite,
            outputTotal = usage?.outputTotal,
            outputReasoning = usage?.outputReasoning,
            costSource = if (price != null) price.source else PolishCostSource.Unknown.name,
            costMicros = price?.micros, costCurrency = price?.currency,
            catalogVersion = price?.catalogVersion, priceRule = price?.rule,
        )
    }

    suspend fun abandon(callId: String) {
        dao.abandon(callId, System.currentTimeMillis())
    }

    suspend fun byId(callId: String): PolishUsageEntity? = dao.byId(callId)
    suspend fun recent(limit: Int = 500): List<PolishUsageEntity> = dao.recent(limit)
    suspend fun clear() = dao.clear()
    suspend fun close() = database.close()
}

internal fun PolishUsageEntity.toStatsEntry(): PolishStatsEntry = PolishStatsEntry(
    timestamp = startedAt,
    provider = provider,
    model = requestModel,
    inputTokens = inputTotal,
    outputTokens = outputTotal,
    durationMillis = durationMillis,
    outcome = outcome,
    costSource = runCatching { PolishCostSource.valueOf(costSource) }.getOrDefault(PolishCostSource.Unknown),
    status = httpStatus,
    configId = configId,
    configRevision = configRevision,
    cacheReadTokens = cacheRead,
    cacheWriteTokens = cacheWrite,
    reasoningTokens = outputReasoning,
    costMicros = costMicros,
    costCurrency = costCurrency,
    catalogVersion = catalogVersion,
    priceRule = priceRule,
    purpose = purpose,
)
