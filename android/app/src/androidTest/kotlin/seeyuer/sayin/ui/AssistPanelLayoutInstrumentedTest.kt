package seeyuer.sayin.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.assist.*
import java.io.IOException
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Scaffold

/** Production overlay with generated records only; no clipboard writes, Root or screenshots. */
@RunWith(AndroidJUnit4::class)
class AssistPanelLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val records = List(50) { RootClip(it + 1L, it + 1L, "SAYIN_TEST_$it\nline two") }
    private var paused by mutableStateOf(false)

    private fun mount(module: Boolean = true, scale: Float = 1f, densityMultiplier: Float = 1f) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            val context = remember {
                object : ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        super.getSharedPreferences("SAYIN_TEST_layout_$name", mode)
                }
            }
            val client = remember {
                object : RootClipboardAccess {
                    override suspend fun list(): RootClips {
                        if (!module) throw IOException("SAYIN_TEST_no_module")
                        return RootClips("1:51", !paused, records)
                    }
                    override suspend fun get(snapshot: RootClips, id: Long) = error("Layout test cannot read")
                    override suspend fun clear(snapshot: RootClips) = error("Layout test cannot clear")
                }
            }
            CompositionLocalProvider(LocalContext provides context,
                LocalDensity provides Density(density.density * densityMultiplier, scale),
                LocalRootClipboardClient provides client) {
                VoiceLinkTheme {
                    val session = remember { SpeakSession(context, recognition = LayoutRecognition()) }
                    DisposableEffect(session) { onDispose { session.close() } }
                    val storage = remember { MemoryPhraseStorage(PhraseLibraryState(1,
                        List(50) { StoredPhrase(UUID.randomUUID().toString(), "SAYIN_TEST_phrase_$it") })) }
                    val phrases = rememberTestPhraseLibrary(storage)
                    Scaffold {
                        Box(Modifier.fillMaxSize()) {
                            AssistPanel(true, 1, session, phrases, null, AssistTab.Clipboard, {}, {}, {})
                        }
                    }
                }
            }
        }
    }

    private fun footer(tag: String): androidx.compose.ui.geometry.Rect {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("assist-panel").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val nav = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val displayHeight = compose.activity.window.decorView.height
        assertTrue("Footer must fit entirely inside the panel", bounds.bottom <= panel.bottom + 1)
        assertTrue("Footer must clear navigation and its 12dp gap",
            bounds.bottom <= displayHeight - nav - 12 * density + 1)
        return bounds
    }

    @RootHelperDeviceTest
    @Test fun moduleFooterStaysAboveNavigationWhileListScrolls() {
        mount()
        val before = footer("root-clipboard-clear")
        compose.onNodeWithTag("root-clipboard-list").performScrollToIndex(49)
        val after = footer("root-clipboard-clear")
        assertEquals(before.bottom, after.bottom, 1f)
        assertEquals(before.height, after.height, 1f)
    }

    @RootHelperDeviceTest
    @Test fun moduleAndPhraseFootersSharePositionAtLargeFont() {
        paused = true
        mount(scale = 2f)
        val module = footer("root-clipboard-clear")
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        val phrase = footer("assist-clear")
        assertEquals(module.bottom, phrase.bottom, 1f)
        assertEquals(module.height, phrase.height, 1f)
        compose.onNodeWithTag("assist-phrase-list").performScrollToIndex(49)
        assertEquals(phrase.bottom, footer("assist-clear").bottom, 1f)
    }

    @RootHelperDeviceTest
    @Test fun ordinaryAndPhraseFootersStayInsideSafeArea() {
        mount(module = false, scale = 2f)
        val ordinary = footer("assist-clear")
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        assertEquals(ordinary.bottom, footer("assist-clear").bottom, 1f)
    }

    @RootHelperDeviceTest
    @Test fun narrowPanelKeepsBothFootersFullyVisible() {
        // K40's 360dp viewport becomes 320dp without changing system density.
        paused = true
        mount(scale = 1.5f, densityMultiplier = 1.125f)
        val module = footer("root-clipboard-clear")
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        assertEquals(module.bottom, footer("assist-clear").bottom, 1f)
    }
}

private class LayoutRecognition : RecognitionEngine {
    override val state = MutableStateFlow(RecognitionState())
    override val model = MutableStateFlow(LocalModelPhase.Ready)
    override fun prepareLocal() = Unit
    override fun start(cloud: CloudConfiguration?, autoFinish: Boolean, localSilenceMillis: Int) = error("Layout test cannot record")
    override fun finish() = Unit
    override fun cancel() = Unit
    override fun clearResult() = Unit
    override fun close() = Unit
}
