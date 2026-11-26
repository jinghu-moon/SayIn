package seeyuer.sayin.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.client.android.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.data.PairingInvitation

@RunWith(AndroidJUnit4::class)
class ScannerInstrumentedTest {
    @Test fun scannerUsesSystemRotationInsteadOfLibraryLandscapeDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = pairingScanOptions("test prompt").createScanIntent(context)
        assertEquals(ComponentName(context, PairingCaptureActivity::class.java), intent.component)
        val activity = context.packageManager.getActivityInfo(requireNotNull(intent.component), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.screenOrientation)
        assertFalse(activity.exported)
        assertFalse(intent.getBooleanExtra(Intents.Scan.ORIENTATION_LOCKED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
        assertEquals("QR_CODE", intent.getStringExtra(Intents.Scan.FORMATS))
        assertEquals("test prompt", intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE))
    }

    @Test fun scannerAcceptsOnlyValidCompactPairingInvitations() {
        val qr = "SAYIN3:N80000G60 TDXR638FDR6WS6AQ6XR6*AFIC2X507H0QS00+0J61%H1CT1V+1572OI2/T2H-2 73AJ3TU3G-3X507H0QS00+0J61%H1CT1F0"
        val invitation = PairingInvitation.fromQr(qr)
        assertEquals("127.0.0.1", invitation.address)
        assertEquals(7788, invitation.port)
        assertEquals("sayin-12345678-1234-4234-8234-123456789012.local", invitation.tlsName)
        for (invalid in listOf(qr.dropLast(1), qr.replaceRange(7, 10, ":::"), qr.dropLast(2) + "::", qr.lowercase())) {
            assertTrue(runCatching { PairingInvitation.fromQr(invalid) }.isFailure)
        }
    }
}
