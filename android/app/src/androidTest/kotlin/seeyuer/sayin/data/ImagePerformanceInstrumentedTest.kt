package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Explicit, isolated production-path benchmark. Never part of regular suites,
 * never opens the daily app's identity, and never invokes a paid service. */
class ImagePerformanceInstrumentedTest {
    @Test fun productionLanImagesWithFrozenPreparationAndProtectedReceipts() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("imageBenchmark")=="true") { "Use verify_image_performance.ps1 explicitly" }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="seeyuer.sayin.imagetest")
        val repeats=(args.getString("imageRepeats")?:"3").toInt().also { require(it in 1..5) }
        val sources=File(target.getExternalFilesDir(null),"image-performance").listFiles()!!.filter{it.extension=="jpg"}.sortedBy{it.name}
        require(sources.size==8) { "Provide the eight image-test samples" }
        val namespace="image-perf-${UUID.randomUUID()}-"
        val context=object:ContextWrapper(target) {
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(name:String)=target.getDatabasePath(namespace+name)
            override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(namespace+name,mode)
        }
        val maximumPss=AtomicLong(0);val samples=AtomicLong(0)
        val memory=launch(Dispatchers.IO) {
            while(isActive) {
                val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                maximumPss.accumulateAndGet(info.totalPss.toLong(),::maxOf);samples.incrementAndGet()
                delay(200)
            }
        }
        val repository=VoiceLinkRepository(context,HostDiscovery{_,_->emptyList()})
        val results=JSONArray();val rejections=JSONArray()
        suspend fun waitFor(predicate:(ClientState)->Boolean):ClientState=withTimeout(90000) {
            while(!predicate(repository.state.value)) {check(repository.state.value.storageHealthy);delay(10)}
            repository.state.value
        }
        try {
            waitFor{it.connection==Connection.OFFLINE};repository.foreground(true)
            repository.pairCode(checkNotNull(args.getString("fixtureAddress")),checkNotNull(args.getString("fixturePort")),checkNotNull(args.getString("fixtureCode")))
            waitFor{it.ready&&it.imageSupported}
            val original=PairedHost(checkNotNull(ProtectedStore(context).load()))
            val proxyPort = args.getString("fixtureProxyPort")?.toInt() ?: 0
            if (proxyPort != 0) {
                repository.updateAddress(original.address, proxyPort.toString())
                waitFor { it.ready && it.imageSupported &&
                    ProtectedStore(context).load()?.let(::PairedHost)?.port == proxyPort }
            }
            for(source in sources) {
                if(source.length()>MAX_IMAGE_BYTES) {
                    val start=SystemClock.elapsedRealtimeNanos()
                    assertThrows(IllegalArgumentException::class.java) {source.inputStream().use{boundedImageRead(it)}}
                    rejections.put(JSONObject().put("file",source.name).put("source_bytes",source.length()).put("bounded_reject_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6))
                    continue
                }
                repeat(repeats) {iteration->
                    assertTrue(repository.state.value.ready)
                    val oldResource=repository.state.value.imageDraft?.image?.resourceId
                    val prepareStart=SystemClock.elapsedRealtimeNanos()
                    repository.selectImage(FileProvider.getUriForFile(target,"${target.packageName}.fixtures",source))
                    val prepared=waitFor{!it.imagePreparing&&it.imageDraft!=null&&it.imageDraft.image.resourceId!=oldResource}
                    val preparationMs=(SystemClock.elapsedRealtimeNanos()-prepareStart)/1e6
                    val image=checkNotNull(prepared.imageDraft).image
                    val previous=prepared.history.map{it.id}.toSet()
                    val sendStart=SystemClock.elapsedRealtimeNanos()
                    repository.sendImage(image.resourceId)
                    val finished=waitFor{state->state.history.any{it.id !in previous&&it.kind=="image"&&it.state in setOf("injected","failed","uncertain","held","expired","cancelled")}}
                    val receipt=finished.history.single{it.id !in previous&&it.kind=="image"}
                    assertEquals("${source.name}: ${receipt.reason}; pause=${finished.pauseReason}","injected",receipt.state)
                    assertNull(receipt.payload);assertEquals(image.resourceId,receipt.imageResource)
                    val sendMs=(SystemClock.elapsedRealtimeNanos()-sendStart)/1e6
                    val saved=PairedHost(checkNotNull(ProtectedStore(context).load()))
                    assertEquals(original.hostId,saved.hostId);assertEquals(original.deviceId,saved.deviceId)
                    assertTrue("TLS name and device credential must remain unchanged", original.tlsName == saved.tlsName && original.token == saved.token)
                    assertArrayEquals(original.certificate.encoded,saved.certificate.encoded)
                    results.put(JSONObject().put("file",source.name).put("iteration",iteration+1).put("source_bytes",source.length())
                        .put("normalized_bytes",image.metadata.bytes).put("width",image.metadata.width).put("height",image.metadata.height)
                        .put("prepare_ms",preparationMs).put("send_to_receipt_ms",sendMs).put("total_ms",preparationMs+sendMs))
                }
            }
            assertEquals(5*repeats,results.length());assertEquals(3,rejections.length())
            memory.cancelAndJoin()
            val output=JSONObject().put("scope","isolated imageTest; production Repository/Keystore/Room/pinned dual WSS; see desktop adapter mode")
                .put("controlled_slow_link",proxyPort != 0).put("measurements",results).put("rejections",rejections).put("sampled_max_pss_kib",maximumPss.get()).put("pss_sample_count",samples.get())
            File(target.getExternalFilesDir(null),"image-performance-result.json").writeText(output.toString(2))
        } finally {
            memory.cancelAndJoin();repository.close();delay(250)
            target.deleteDatabase(namespace+"outbox.db");target.deleteSharedPreferences(namespace+"paired_host")
            File(target.noBackupFilesDir,"images/${namespace}outbox").deleteRecursively()
        }
    }
}
