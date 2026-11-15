package seeyuer.sayin.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.data.RootClipboardClient
import seeyuer.sayin.data.RootClipboardFailure

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RootHelperDeviceTest

@RunWith(AndroidJUnit4::class)
class RootClipboardInstrumentedTest {
    @RootHelperDeviceTest
    @Test fun disabledHistoryIsReadable() = runBlocking {
        val socket = InstrumentationRegistry.getArguments().getString("helperSocket").orEmpty()
        assumeTrue(socket.startsWith("SAYIN_TEST_"))
        val snapshot = RootClipboardClient(socket).list()
        assertFalse(snapshot.captureReady)
        assertEquals(3, snapshot.entries.size)
        assertTrue(RootClipboardClient(socket).get(snapshot, snapshot.entries.first().id).startsWith("SAYIN_TEST_"))
    }

    @RootHelperDeviceTest
    @Test fun authenticatedNativeHistory() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val socket = args.getString("helperSocket") ?: ""
        assumeTrue(socket.startsWith("SAYIN_TEST_"))
        val prefix = args.getString("helperPrefix") ?: ""
        require(prefix.startsWith("SAYIN_TEST_"))
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "seeyuer.sayin.imagetest")
        val client = RootClipboardClient(socket)
        val snapshot = client.list()
        assertEquals(3, snapshot.entries.size)
        assertTrue(snapshot.entries.all { it.preview.startsWith(prefix) })
        val text = client.get(snapshot, snapshot.entries.first().id)
        assertTrue(text.startsWith(prefix))
        client.clear(snapshot)
        assertTrue(client.list().entries.isEmpty())
        try { client.get(snapshot, snapshot.entries.first().id); fail("Stale read must be rejected") }
        catch (failure: RootClipboardFailure) { assertEquals("stale_revision", failure.code) }
        try { client.clear(snapshot); fail("Stale clear must be rejected") }
        catch (failure: RootClipboardFailure) { assertEquals("stale_revision", failure.code) }
    }
}
