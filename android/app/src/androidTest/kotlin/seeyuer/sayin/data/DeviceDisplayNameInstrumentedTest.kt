package seeyuer.sayin.data

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.pairing.NativePairing

@RunWith(AndroidJUnit4::class)
class DeviceDisplayNameInstrumentedTest {
    @Test fun publicNativePropertiesResolveUnderTheAppUid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = DeviceDisplayName.current()
        assertFalse(name.isBlank())
        assertEquals("", NativePairing.readDeviceMarketName("ro.serialno"))
        InstrumentationRegistry.getArguments().getString("expectedDeviceDisplayName")?.let {
            assertEquals(it, name)
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("deviceDisplayName", name) })
    }
}
