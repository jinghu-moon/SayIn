package seeyuer.sayin.data

import android.content.ClipData
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedTextInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun plainTextExtraIsTrimmed() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  分享到 SayIn  ")
        assertEquals("分享到 SayIn", intent.sharedText(context))
    }

    @Test fun clipDataIsUsedWhenNoTextExtraExists() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        intent.clipData = ClipData.newPlainText("shared", "Clipboard fallback")
        assertEquals("Clipboard fallback", intent.sharedText(context))
    }

    @Test fun imagesAndOtherActionsAreNotTextShares() {
        assertNull(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "text").sharedText(context))
        assertNull(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "text").sharedText(context))
        assertNull(Intent(Intent.ACTION_SEND).setType("text/plain").sharedText(context))
    }
}
