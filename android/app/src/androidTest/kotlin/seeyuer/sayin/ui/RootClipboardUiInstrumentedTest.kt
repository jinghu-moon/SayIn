package seeyuer.sayin.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.data.RootClipboardAccess
import seeyuer.sayin.data.RootClips
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.assist.LocalRootClipboardClient
import seeyuer.sayin.ui.assist.RootClipboardTab
import top.yukonga.miuix.kmp.basic.Text

@RunWith(AndroidJUnit4::class)
class RootClipboardUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mounted = mutableStateOf(true)
    private var loads = 0

    private fun mount(reply: CompletableDeferred<RootClips>, nonCancellable: Boolean = false) {
        val client = object : RootClipboardAccess {
            override suspend fun list(): RootClips {
                loads++
                return if (nonCancellable) withContext(NonCancellable) { reply.await() } else reply.await()
            }
            override suspend fun get(snapshot: RootClips, id: Long): String = error("Unexpected GET")
            override suspend fun clear(snapshot: RootClips) = error("Unexpected CLEAR")
        }
        compose.setContent {
            VoiceLinkTheme {
                CompositionLocalProvider(LocalRootClipboardClient provides client) {
                    if (mounted.value) RootClipboardTab({}, {}, {}, {}, {}, {}, {}) {
                        Text("SAYIN_TEST_current_clip", Modifier.testTag("ordinary-test-clipboard"))
                    }
                }
            }
        }
        compose.waitUntil(5_000) { loads == 1 }
    }

    @RootHelperDeviceTest
    @Test fun resolvingModuleDoesNotFlashCurrentClipboard() {
        val reply = CompletableDeferred<RootClips>()
        mount(reply)
        compose.onNodeWithTag("root-clipboard-loading").assertExists()
        compose.onNodeWithTag("ordinary-test-clipboard").assertDoesNotExist()
        reply.complete(RootClips("1:1", true, emptyList()))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("root-clipboard-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ordinary-test-clipboard").assertDoesNotExist()
        compose.onNodeWithTag("root-clipboard-loading").assertDoesNotExist()
    }

    @RootHelperDeviceTest
    @Test fun absentModuleFallsBackAfterResolution() {
        val reply = CompletableDeferred<RootClips>()
        mount(reply)
        compose.onNodeWithTag("ordinary-test-clipboard").assertDoesNotExist()
        reply.completeExceptionally(IOException("SAYIN_TEST_no_module"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("ordinary-test-clipboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("root-clipboard-list").assertDoesNotExist()
    }

    @RootHelperDeviceTest
    @Test fun lateResolutionAfterClosingPanelHasNoUiEffect() {
        val reply = CompletableDeferred<RootClips>()
        mount(reply, nonCancellable = true)
        compose.runOnIdle { mounted.value = false }
        reply.complete(RootClips("1:1", true, emptyList()))
        compose.waitForIdle()
        compose.onNodeWithTag("root-clipboard-list").assertDoesNotExist()
        compose.onNodeWithTag("ordinary-test-clipboard").assertDoesNotExist()
        assertEquals(1, loads)
    }
}
