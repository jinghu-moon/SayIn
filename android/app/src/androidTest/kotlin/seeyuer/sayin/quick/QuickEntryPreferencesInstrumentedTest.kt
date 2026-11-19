package seeyuer.sayin.quick

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickEntryPreferencesInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ballSizeOpacityAndDoubleTapArePersistedWithinSupportedBounds() {
        QuickEntryService.setSize(context, 40)
        assertEquals(44, QuickEntryService.size(context))
        QuickEntryService.setSize(context, 100)
        assertEquals(72, QuickEntryService.size(context))
        QuickEntryService.setPeekAlpha(context, 0.1f)
        assertEquals(0.35f, QuickEntryService.peekAlpha(context), 0.001f)
        QuickEntryService.setPeekAlpha(context, 2f)
        assertEquals(1f, QuickEntryService.peekAlpha(context), 0.001f)
        QuickEntryService.setDoubleAction(context, QuickEntryService.DOUBLE_RECORD)
        assertEquals(QuickEntryService.DOUBLE_RECORD, QuickEntryService.doubleAction(context))
        QuickEntryService.setDoubleAction(context, "invalid")
        assertEquals(QuickEntryService.DOUBLE_CARD, QuickEntryService.doubleAction(context))
        // Restore the shipped defaults so the shared test package starts clean.
        QuickEntryService.setSize(context, 56)
        QuickEntryService.setPeekAlpha(context, 0.5f)
        QuickEntryService.setDoubleAction(context, QuickEntryService.DOUBLE_CARD)
    }

    @Test fun quickCardIntentCarriesAutoRecordOnlyWhenRequested() {
        val plain = QuickActivity.intent(context)
        assertEquals(false, plain.getBooleanExtra("seeyuer.sayin.AUTO_RECORD", false))
        val recording = QuickActivity.intent(context, autoRecord = true)
        assertEquals(true, recording.getBooleanExtra("seeyuer.sayin.AUTO_RECORD", false))
    }
}
