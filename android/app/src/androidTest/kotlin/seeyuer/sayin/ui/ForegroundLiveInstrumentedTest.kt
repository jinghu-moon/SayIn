package seeyuer.sayin.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.TrafficStats
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme

/** Explicit fixture only: native Windows foreground → authenticated TLS → real phone Compose. */
class ForegroundLiveInstrumentedTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
                .use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private class Recognition : RecognitionEngine {
        override val state = MutableStateFlow(RecognitionState())
        override val model = MutableStateFlow(LocalModelPhase.Ready)
        override fun prepareLocal() {}
        override fun start(cloud: CloudConfiguration?, autoFinish: Boolean, localSilenceMillis: Int) {}
        override fun finish() {}
        override fun cancel() {}
        override fun clearResult() {}
        override fun close() {}
    }
    @Test fun recordsForegroundDisplayChangesWithoutSubmittingInput() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("foregroundLive") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "foreground-live-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> emptyList() })
        var session: SpeakSession? = null
        val output = File(target.filesDir, "ui-verification/54-foreground-live.json")
        val ready = File(target.filesDir, "ui-verification/54-foreground-ready")
        output.parentFile!!.mkdirs(); ready.delete()
        val observations = JSONArray()
        try {
            compose.setContent {
                val state by repository.state.collectAsState()
                val retained = remember {
                    SpeakSession(context, TextFieldValue("Foreground display verification"), recognition = Recognition()).also { session = it }
                }
                SideEffect { retained.updateReceiver(state) }
                VoiceLinkTheme { SpeakPage(state, retained, rememberTestPhraseLibrary(), {}, {}) }
                LaunchedEffect(state.foregroundApplication, state.connection, state.paused) {
                    withFrameNanos { }; withFrameNanos { }
                    val pixels = state.foregroundApplication?.icon?.let { bitmap ->
                        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                    }
                    observations.put(JSONObject().put("name", state.foregroundApplication?.name ?: JSONObject.NULL)
                        .put("icon_present", state.foregroundApplication?.icon != null)
                        .put("icon_pixels_hash", pixels?.contentHashCode() ?: JSONObject.NULL)
                        .put("displayed_at_ms", System.currentTimeMillis()).put("connection", state.connection.name))
                    output.writeText(observations.toString())
                }
            }
            compose.waitUntil(10000) { repository.state.value.connection == Connection.OFFLINE }
            repository.foreground(true)
            repository.pairCode("127.0.0.1", checkNotNull(args.getString("fixturePort")), checkNotNull(args.getString("fixtureCode")))
            compose.waitUntil(15000) { repository.state.value.ready }
            compose.waitUntil(5000) { repository.state.value.foregroundApplication != null }
            fun pump(milliseconds: Long) {
                val until = android.os.SystemClock.elapsedRealtime() + milliseconds
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    compose.mainClock.advanceTimeByFrame(); Thread.sleep(16)
                }
            }
            pump(500)
            compose.runOnIdle { ready.writeText("ready") }
            val idleStart = System.currentTimeMillis()
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val tx = TrafficStats.getUidTxBytes(Process.myUid())
            val idleObservations = observations.length()
            pump(18_000)
            File(target.filesDir, "ui-verification/54-foreground-idle.json").writeText(JSONObject()
                .put("duration_ms", System.currentTimeMillis() - idleStart)
                .put("rx_bytes", TrafficStats.getUidRxBytes(Process.myUid()) - rx)
                .put("tx_bytes", TrafficStats.getUidTxBytes(Process.myUid()) - tx)
                .put("display_changes", observations.length() - idleObservations).toString())
            // External fixture changes only its own disposable app windows.
            val seconds = args.getString("foregroundSeconds")?.toInt() ?: 38
            pump(seconds * 1000L)
            assertTrue(repository.state.value.ready)
            assertTrue(repository.state.value.history.isEmpty())
            assertNotNull(repository.state.value.foregroundApplication?.icon)
            compose.onNodeWithTag("foreground-application-icon").assertIsDisplayed()
            val image = File(target.filesDir, "ui-verification/54-foreground-live.png")
            image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            repository.foreground(false)
            compose.waitUntil(5000) { repository.state.value.connection == Connection.OFFLINE }
            assertNull(repository.state.value.foregroundApplication)
        } finally {
            compose.runOnIdle { session?.close() }
            repository.close(); Thread.sleep(200)
            target.deleteDatabase(namespace + "outbox.db")
            target.getSharedPreferences(namespace + "paired_host", 0).edit().clear().commit()
            ready.delete()
        }
    }
}
