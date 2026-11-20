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
import androidx.room.withTransaction
import java.util.UUID

internal const val POLISH_USAGE_RETENTION_DAYS = 90L
private const val POLISH_USAGE_RETENTION_MILLIS = POLISH_USAGE_RETENTION_DAYS * 24L * 60L * 60L * 1000L

internal enum class PolishCallPurpose { Polish, Translation, Test }

private const val USAGE_FILTER = "startedAt >= :since AND (:purpose IS NULL OR purpose = :purpose) AND (:configId IS NULL OR configId = :configId) AND (:model IS NULL OR COALESCE(responseModel, requestModel) = :model)"
internal const val POLISH_USAGE_PAGE_SIZE = 30

internal data class PolishUsageTotals(val calls: Long, val tokens: Long, val unknownCost: Long)
internal data class PolishUsageAmount(val currency: String, val source: String, val micros: Long)
internal data class PolishUsageReport(
    val totals: PolishUsageTotals,
    val costs: List<PolishUsageAmount>,
    val medianMillis: Long,
    val entries: List<PolishStatsEntry>,
    val models: List<String>,
)

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

    @Query("SELECT COUNT(*) AS calls, COALESCE(SUM(COALESCE(inputTotal, 0) + COALESCE(outputTotal, 0)), 0) AS tokens, " +
        "COALESCE(SUM(CASE WHEN costMicros IS NULL AND outcome NOT IN ('unsent', 'pending') THEN 1 ELSE 0 END), 0) AS unknownCost " +
        "FROM polish_usage WHERE " + USAGE_FILTER)
    suspend fun totals(since: Long, purpose: String?, configId: String?, model: String?): PolishUsageTotals

    @Query("SELECT costCurrency AS currency, costSource AS source, SUM(costMicros) AS micros FROM polish_usage WHERE " +
        USAGE_FILTER + " AND costCurrency IS NOT NULL AND costMicros IS NOT NULL GROUP BY costCurrency, costSource ORDER BY costCurrency, costSource")
    suspend fun costs(since: Long, purpose: String?, configId: String?, model: String?): List<PolishUsageAmount>

    @Query("SELECT durationMillis FROM polish_usage WHERE " + USAGE_FILTER + " AND outcome NOT IN ('pending', 'unsent') ORDER BY durationMillis")
    suspend fun durations(since: Long, purpose: String?, configId: String?, model: String?): List<Long>

    @Query("SELECT * FROM polish_usage WHERE " + USAGE_FILTER + " ORDER BY startedAt DESC, callId DESC LIMIT :limit OFFSET :offset")
    suspend fun page(since: Long, purpose: String?, configId: String?, model: String?, limit: Int, offset: Int): List<PolishUsageEntity>

    @Query("SELECT DISTINCT COALESCE(responseModel, requestModel) FROM polish_usage WHERE startedAt >= :since " +
        "AND (:configId IS NULL OR configId = :configId) ORDER BY 1")
    suspend fun models(since: Long, configId: String?): List<String>

    @Query("SELECT * FROM polish_usage WHERE callId=:callId")
    suspend fun byId(callId: String): PolishUsageEntity?
}

@Database(entities = [PolishUsageEntity::class], version = 2, exportSchema = false)
internal abstract class PolishUsageDatabase : RoomDatabase() {
    abstract fun dao(): PolishUsageDao
}

internal class PolishUsageStore(context: Context, databaseName: String = "polish-usage.db") {
    private val database = Room.databaseBuilder(
        context.applicationContext, PolishUsageDatabase::class.java, databaseName,
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

    suspend fun finish(callId: String, result: PolishCallResult, outcome: String, price: PolishPrice?,
                       responseModel: String? = null) {
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
            responseModel = responseModel,
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
    suspend fun report(since: Long, purpose: String?, configId: String?, model: String?, page: Int,
                       includeDetails: Boolean = true): PolishUsageReport =
        database.withTransaction {
            val durations = dao.durations(since, purpose, configId, model)
            val median = if (durations.isEmpty()) 0L else {
                val low = durations[(durations.size - 1) / 2]
                val high = durations[durations.size / 2]
                low + (high - low) / 2
            }
            PolishUsageReport(
                dao.totals(since, purpose, configId, model), dao.costs(since, purpose, configId, model), median,
                if (includeDetails) dao.page(since, purpose, configId, model, POLISH_USAGE_PAGE_SIZE, page * POLISH_USAGE_PAGE_SIZE)
                    .map { it.toStatsEntry() } else emptyList(),
                if (includeDetails) dao.models(since, configId) else emptyList(),
            )
        }
    suspend fun clear() = dao.clear()
    suspend fun close() = database.close()
}

internal fun PolishUsageEntity.toStatsEntry(): PolishStatsEntry = PolishStatsEntry(
    callId = callId,
    timestamp = startedAt,
    provider = provider,
    model = requestModel,
    responseModel = responseModel,
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
