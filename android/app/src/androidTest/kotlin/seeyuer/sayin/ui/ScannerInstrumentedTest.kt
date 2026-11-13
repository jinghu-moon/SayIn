package seeyuer.sayin.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerInstrumentedTest {
    @Test fun scannerUsesSystemRotationInsteadOfLibraryLandscapeDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = pairingScanOptions("test prompt").createScanIntent(context)
        assertEquals(ComponentName(context, CaptureActivity::class.java), intent.component)
        val activity = context.packageManager.getActivityInfo(requireNotNull(intent.component), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.screenOrientation)
        assertFalse(activity.exported)
        assertFalse(intent.getBooleanExtra(Intents.Scan.ORIENTATION_LOCKED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
        assertEquals("QR_CODE", intent.getStringExtra(Intents.Scan.FORMATS))
        assertEquals("test prompt", intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE))
    }
}
