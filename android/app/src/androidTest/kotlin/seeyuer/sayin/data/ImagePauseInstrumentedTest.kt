package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
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

/** Opt-in real LAN pause under production dual-WSS load. The fixture consumes
 * a test-only text marker without input, emulates a local pause, then emulates
 * local resume only after the test explicitly cancels that marker. */
class ImagePauseInstrumentedTest {
    @Test fun pauseInterruptsImageAndReconnectCannotReviveIntent() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("imagePauseProbe") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val source = File(target.getExternalFilesDir(null), "image-performance/005_2.04MB_5.jpg")
        require(source.isFile)
        val namespace = "image-pause-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
        suspend fun waitFor(predicate: (ClientState) -> Boolean): ClientState = withTimeout(20000) {
            while (!predicate(repository.state.value)) { check(repository.state.value.storageHealthy); delay(2) }
            repository.state.value
        }
        val measurements = JSONArray()
        try {
            waitFor { it.connection == Connection.OFFLINE }; repository.foreground(true)
            repository.pairCode(checkNotNull(args.getString("fixtureAddress")), checkNotNull(args.getString("fixturePort")), checkNotNull(args.getString("fixtureCode")))
            waitFor { it.ready && it.imageSupported }
            val original = PairedHost(checkNotNull(ProtectedStore(context).load()))
            val proxyPort = args.getString("fixtureProxyPort")?.toInt() ?: 0
            if (proxyPort != 0) {
                repository.updateAddress(original.address, proxyPort.toString())
                waitFor { it.ready && it.imageSupported &&
                    ProtectedStore(context).load()?.let(::PairedHost)?.port == proxyPort }
            }
            repository.selectImage(FileProvider.getUriForFile(target, "${target.packageName}.fixtures", source))
            val image = checkNotNull(waitFor { it.imageDraft != null && !it.imagePreparing }.imageDraft).image
            repeat(5) { iteration ->
                waitFor { it.ready && !it.imageBusy }
                var cancelMs: Double? = null
                if (proxyPort != 0) {
                    repository.sendImage(image.resourceId)
                    val inFlight = waitFor { it.imageBusy && it.imageProgress > 0 }
                    assertFalse(inFlight.imageSubmitted)
                    val cancelledId = checkNotNull(inFlight.imageRequest)
                    val cancelStart = SystemClock.elapsedRealtimeNanos()
                    repository.cancelImage()
                    waitFor { !it.imageBusy && it.history.any { row -> row.id == cancelledId && row.state == "cancelled" } }
                    cancelMs = (SystemClock.elapsedRealtimeNanos() - cancelStart) / 1e6
                    repository.foreground(false); waitFor { it.connection == Connection.OFFLINE }
                    repository.foreground(true)
                    waitFor { it.ready && !it.recovering }
                    assertFalse(repository.state.value.imageBusy)
                    assertEquals(image, checkNotNull(repository.state.value.imageDraft).image)
                }
                repository.sendImage(image.resourceId)
                val uploading = waitFor { it.imageBusy && it.imageProgress > 0 }
                assertFalse("Must trigger during upload, before submit", uploading.imageSubmitted)
                assertTrue(uploading.imageProgress < image.metadata.bytes)
                val oldRequest = checkNotNull(uploading.imageRequest)
                val marker = UUID.randomUUID().toString()
                val started = SystemClock.elapsedRealtimeNanos()
                repository.send("fixture: pause upload", marker)
                val paused = waitFor { it.paused }
                val roundtrip = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                assertFalse(paused.ready)
                val cancelled = waitFor { !it.imageBusy && it.history.any { row -> row.id == oldRequest && row.state == "cancelled" } }
                assertEquals(image, checkNotNull(cancelled.imageDraft).image)
                assertTrue(cancelled.history.none { it.kind == "image" && it.state == "injected" })
                // Reconnect queries old requests, retains the pause, and does
                // not upload, submit, release or resurrect the previous image.
                repository.foreground(false); waitFor { it.connection == Connection.OFFLINE }
                repository.foreground(true)
                val recovered = waitFor { it.connection == Connection.ONLINE && !it.recovering }
                assertTrue(recovered.paused); assertFalse(recovered.imageBusy)
                assertEquals(image, checkNotNull(recovered.imageDraft).image)
                val saved = PairedHost(checkNotNull(ProtectedStore(context).load()))
                assertEquals(original.hostId, saved.hostId); assertEquals(original.deviceId, saved.deviceId)
                assertTrue("TLS name and device credential must remain unchanged", original.tlsName == saved.tlsName && original.token == saved.token)
                assertArrayEquals(original.certificate.encoded, saved.certificate.encoded)
                // This cancellation is only the fixture's cue to perform its
                // next deliberate local resume, not a product remote resume.
                repository.action(marker, "cancel")
                waitFor { it.ready }
                delay(250)
                assertFalse(repository.state.value.imageBusy)
                assertEquals("cancelled", repository.state.value.history.single { it.id == oldRequest }.state)
                measurements.put(JSONObject().put("iteration", iteration + 1)
                    .put("normalized_bytes", image.metadata.bytes).put("bytes_acked_at_trigger", uploading.imageProgress)
                    .put("explicit_cancel_ms", cancelMs ?: JSONObject.NULL)
                    .put("control_marker_to_paused_ms", roundtrip))
            }
            File(target.getExternalFilesDir(null), "image-pause-result.json").writeText(JSONObject()
                .put("scope", "Real LAN production Repository/dual WSS; marker acceptance + fixture <=50ms polling + durable local pause + pushed state to Android. Fake adapter, no paste; not one-way latency.")
                .put("controlled_slow_link", proxyPort != 0).put("measurements", measurements).put("image_inputs", 0).toString(2))
        } finally {
            repository.close(); delay(250)
            target.deleteDatabase(namespace + "outbox.db"); target.deleteSharedPreferences(namespace + "paired_host")
            File(target.noBackupFilesDir, "images/${namespace}outbox").deleteRecursively()
        }
    }
}
