package seeyuer.sayin.polish

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolishUsageStoreInstrumentedTest {
    private val databaseName = "polish-usage-test.db"

    @Test fun pendingRecoveryTerminalIdempotenceAndClear() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val store = PolishUsageStore(context, databaseName)
        try {
            val config = PolishConfig.preset(PolishProvider.DeepSeek).copy(key = "test-key", model = "deepseek-flash")

            val abandoned = store.begin(config, PolishCallPurpose.Polish)
            assertEquals("pending", store.byId(abandoned.callId)?.outcome)
            store.recover()
            assertEquals("unknown", store.byId(abandoned.callId)?.outcome)
            val completed = store.begin(config, PolishCallPurpose.Polish)
            val result = PolishCallResult.Success(
                text = "ok", usage = PolishUsage(1000, 200, null, 300, 80),
                durationMillis = 42, httpStatus = 200,
            )
            store.finish(completed.callId, result, "success", null)
            val finished = store.byId(completed.callId)!!
            assertEquals("success", finished.outcome)
            assertEquals(1000L, finished.inputTotal)
            assertEquals(200L, finished.cacheRead)
            assertEquals(80L, finished.outputReasoning)
            store.finish(completed.callId, result, "success", null)
            assertEquals("success", store.byId(completed.callId)?.outcome)

            store.clear()
            assertTrue(store.recent().isEmpty())
            store.finish(completed.callId, result, "success", null)
            assertNull(store.byId(completed.callId))
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun concurrentTerminalWritesKeepEveryRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val store = PolishUsageStore(context, databaseName)
        try {
            val config = PolishConfig.preset(PolishProvider.DeepSeek).copy(key = "test-key", model = "deepseek-flash")
            val calls = (0 until 4).map { store.begin(config, PolishCallPurpose.Polish) }
            coroutineScope {
                calls.map { call ->
                    async(Dispatchers.IO) {
                        store.finish(call.callId, PolishCallResult.Success(
                            text = "ok", usage = PolishUsage(10, null, null, 2), durationMillis = 5,
                            httpStatus = 200,
                        ), "success", null)
                    }
                }.awaitAll()
            }
            assertEquals(4, store.recent().count { it.outcome == "success" })
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun recordsOlderThanNinetyDaysArePruned() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val now = System.currentTimeMillis()
        val db = Room.databaseBuilder(context, PolishUsageDatabase::class.java, databaseName).build()
        try {
            db.dao().insert(PolishUsageEntity(
                callId = "old-call", startedAt = now - 91L * 24 * 60 * 60 * 1000,
                finishedAt = now, durationMillis = 10, configId = "config", configRevision = 1,
                provider = "deepseek", protocol = "Chat", purpose = "polish",
                requestModel = "deepseek-flash", responseModel = null, httpStatus = 200,
                outcome = "success", inputTotal = 1, cacheRead = null, cacheWrite = null,
                outputTotal = 1, outputReasoning = null, costSource = "Unknown",
                costMicros = null, costCurrency = null, catalogVersion = null, priceRule = null,
            ))
        } finally {
            db.close()
        }
        val store = PolishUsageStore(context, databaseName)
        try {
            store.recover()
            assertTrue(store.recent().none { it.callId == "old-call" })
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }
}
