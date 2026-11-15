package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/** Explicit emulator-only platform checks, outside regular transport suites. */
class PlatformNetworkInstrumentedTest {
    @Test fun systemPermissionAndTlsCapabilities() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("platformProbe") == "true")
        val target = instrumentation.targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        if (Build.VERSION.SDK_INT < 37) {
            check(Build.VERSION.SDK_INT == 29)
            assertTrue(target.localNetworkPermitted())
            val tls = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            assertTrue("The minimum supported OS must provide TLS1.3", "TLSv1.3" in tls.supportedSSLParameters.protocols)
            return@runBlocking
        }
        check(Build.VERSION.SDK_INT == 37)
        val namespace = "platform-network-api37-"
        val checkpoint = target.getSharedPreferences("platform-network-checkpoint", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val discoveries = AtomicInteger()
        val repository = VoiceLinkRepository(context, HostDiscovery { _, _ -> discoveries.incrementAndGet(); emptyList() })
        suspend fun waitFor(predicate: (ClientState) -> Boolean): ClientState = withTimeout(20000) {
            while (!predicate(repository.state.value)) delay(20)
            repository.state.value
        }
        val revoked = args.getString("permissionPhase") == "revoked"
        try {
            assertFalse(context.localNetworkPermitted())
            waitFor { it.connection == Connection.OFFLINE }
            repository.foreground(true)
            waitFor { it.issue == ClientIssue.LOCAL_NETWORK_PERMISSION }
            assertEquals(0, discoveries.get())
            if (revoked) {
                assertTrue(repository.state.value.paired)
                val identity = checkNotNull(ProtectedStore(context).load())
                assertEquals(checkpoint.getString("identity_hash", null), imageDigest(identity.toString().toByteArray()))
                assertFalse(repository.state.value.discovering)
                assertEquals(Connection.OFFLINE, repository.state.value.connection)
            } else {
                suspend fun choose(allow: Boolean) {
                    val ticket = java.util.UUID.randomUUID().toString()
                    val replies = target.getSharedPreferences("platform-permission-probe", Context.MODE_PRIVATE)
                    target.startActivity(Intent().setClassName(target.packageName, "seeyuer.sayin.NetworkPermissionProbeActivity")
                        .putExtra("ticket", ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        val id = node.viewIdResourceName.orEmpty()
                        if (id.endsWith(if (allow) "/permission_allow_button" else "/permission_deny_button")) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    withTimeout(10000) {
                        var button: AccessibilityNodeInfo?
                        do { button = find(instrumentation.uiAutomation.rootInActiveWindow); if (button == null) delay(50) } while (button == null)
                        assertTrue(button.packageName.toString().endsWith(".permissioncontroller"))
                        // Accessibility may expose controls before the first
                        // composed frame; retain readable system-dialog evidence.
                        delay(500)
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        if (screenshot != null) {
                            try { java.io.File(target.getExternalFilesDir(null), "network-permission-${if (allow) "allow" else "deny"}.png").outputStream().use {
                                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            } } finally { screenshot.recycle() }
                        }
                        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    withTimeout(10000) { while (!replies.contains(ticket)) delay(20) }
                    assertEquals(allow, replies.getBoolean(ticket, !allow))
                }
                choose(false)
                assertFalse(context.localNetworkPermitted())
                repository.pairCode("127.0.0.1", "1", "12345678")
                waitFor { it.issue == ClientIssue.LOCAL_NETWORK_PERMISSION }
                assertEquals(0, discoveries.get())
                choose(true)
                assertTrue(context.localNetworkPermitted())
                val network = checkNotNull(LanNetwork(context) {}.current())
                val browser = NsdHostDiscovery(context)
                val began = SystemClock.elapsedRealtime()
                assertTrue(withTimeout(12000) { browser.discover(java.util.UUID.randomUUID().toString(), network) }.isEmpty())
                assertTrue(SystemClock.elapsedRealtime() - began < 12000)
                val cancelled = launch { browser.discover(java.util.UUID.randomUUID().toString(), network) }
                delay(100); withTimeout(3000) { cancelled.cancelAndJoin() }
                repository.pairCode(checkNotNull(args.getString("fixtureAddress")), checkNotNull(args.getString("fixturePort")), checkNotNull(args.getString("fixtureCode")))
                waitFor { it.ready && it.imageSupported }
                val identity = checkNotNull(ProtectedStore(context).load())
                assertTrue(checkpoint.edit().putString("identity_hash", imageDigest(identity.toString().toByteArray())).commit())
            }
        } finally {
            repository.close(); delay(200)
            target.deleteSharedPreferences("platform-permission-probe")
            if (revoked) {
                target.deleteDatabase(namespace + "outbox.db")
                target.deleteSharedPreferences(namespace + "paired_host")
                target.deleteSharedPreferences("platform-network-checkpoint")
            }
        }
    }
}
