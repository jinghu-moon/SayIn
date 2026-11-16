package seeyuer.sayin.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import seeyuer.sayin.R
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.UUID
import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import java.io.File

class ImageUiInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule(order=0) val foreground = object:ExternalResource() {
        override fun before() {
            check(context.packageName.endsWith(".imagetest"))
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity"
            ).use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun imageRequiresExplicitSendAndDismissCancelsWithoutEditingText() {
        val bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
        val png=try { ByteArrayOutputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.toByteArray() } } finally {bitmap.recycle()}
        val id=UUID.randomUUID().toString()
        val image=ImageReference(id,ImageMetadata(png.size,ImageMime.PNG,3,2,imageDigest(png)))
        var shown by mutableStateOf(true)
        var state by mutableStateOf(ClientState(paired=true,connection=Connection.ONLINE,paused=false,active=true,target="scope",revision="2",
            imageSupported=true,imageDraft=LocalImageDraft(image,png)))
        var sends=0;var cancels=0
        compose.setContent { VoiceLinkTheme { Scaffold { ImageInputSheet(shown,state,true,{cancels++;shown=false},{fail("Picker was not invoked")},{assertEquals(id,it);sends++},{cancels++},{}) } } }
        val send = hasText(context.getString(R.string.image_send)) and hasClickAction()
        compose.onNode(send).performScrollTo().assertIsEnabled()
        assertEquals(0,sends)
        compose.onNode(send).performClick()
        assertEquals(1,sends)
        compose.runOnIdle { state=state.copy(paused=true) }
        compose.onNode(send).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.image_close)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(cancels>=1);assertEquals(1,sends)
        assertEquals(image,state.imageDraft!!.image)
    }

    @Test fun imageHistoryNeverOffersReleaseAndShowsPasteSemantics() {
        val item=OutboxItem(UUID.randomUUID().toString(),"host","device","scope",null,0,"held","1",null,System.currentTimeMillis(),kind="image")
        var state by mutableStateOf(ClientState(history=listOf(item),connection=Connection.ONLINE,active=true,paused=false,target="scope"))
        compose.setContent { VoiceLinkTheme { Scaffold { androidx.compose.foundation.layout.Column { MobileHistoryPage(state, action = { _,action -> assertNotEquals("release",action) }) } } } }
        compose.onNodeWithText(context.getString(R.string.image_history)).assertExists()
        compose.onNodeWithText(context.getString(R.string.release_request)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.image_held)).assertExists()
        compose.runOnIdle { state=state.copy(history=listOf(item.copy(state="injected"))) }
        compose.onNodeWithTag("history-more-${item.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.history_details)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_submitted_help)).assertExists()
    }

    @Test fun imageSheetSupportsLanguagesThemesAndLargeText() {
        var language by mutableStateOf("en")
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        val state=ClientState(imageIssue=ImageIssue.INVALID)
        compose.setContent {
            val configuration=Configuration(LocalConfiguration.current).apply { setLocales(LocaleList.forLanguageTags(language)) }
            val localized=LocalContext.current.createConfigurationContext(configuration)
            val density=LocalDensity.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides compose.activity,
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density,fontScale)) {
                VoiceLinkTheme(dark) { Scaffold { ImageInputSheet(true,state,true,{}, {}, {}, {}, {}) } }
            }
        }
        for ((locale,night,scale) in listOf(Triple("en",false,1f),Triple("zh-Hans",true,1.5f))) {
            compose.runOnIdle {language=locale;dark=night;fontScale=scale}
            val resources=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocales(LocaleList.forLanguageTags(locale))})
            compose.onNode(hasText(resources.getString(R.string.image_choose)) and hasClickAction()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(resources.getString(R.string.image_invalid)).assertExists()
            compose.onNode(hasText(resources.getString(R.string.image_close)) and hasClickAction()).performScrollTo().assertIsDisplayed()
            val screenshot=compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null),"image-sheet-$locale.png").outputStream().use {assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))}
        }
    }
}
