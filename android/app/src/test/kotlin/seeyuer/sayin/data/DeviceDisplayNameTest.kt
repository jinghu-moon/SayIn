package seeyuer.sayin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDisplayNameTest {
    @Test fun prefersMarketThenVendorThenOdmWithoutChangingCase() {
        val values = mapOf("ro.product.marketname" to " Redmi K40 ", "ro.product.vendor.marketname" to "Vendor")
        assertEquals("Redmi K40", DeviceDisplayName.resolve("M2012K11AC", values::get))
        assertEquals("Vendor", DeviceDisplayName.resolve("Model") {
            if (it == "ro.product.vendor.marketname") "Vendor" else " \n "
        })
        assertEquals("ODM 商品名", DeviceDisplayName.resolve("Model") {
            if (it == "ro.product.odm.marketname") "ODM 商品名" else null
        })
    }

    @Test fun unreadableOrMissingPropertiesNeverBlockModelOrGenericFallback() {
        assertEquals("Model", DeviceDisplayName.resolve(" Model ") { throw SecurityException() })
        assertEquals("Model", DeviceDisplayName.resolve("Model") { throw UnsatisfiedLinkError() })
        assertEquals("Android", DeviceDisplayName.resolve("\u0000 \n") { null })
        assertEquals("Android", DeviceDisplayName.resolve(null) { "" })
    }

    @Test fun cleansControlsAndPreservesSixtyFourWholeUnicodeScalars() {
        assertEquals("Redmi K40", DeviceDisplayName.clean("\u0000 Redmi\n K40\u007f "))
        assertEquals("😀".repeat(64), DeviceDisplayName.clean("😀".repeat(65)))
        assertEquals("a".repeat(63) + "😀", DeviceDisplayName.clean("a".repeat(63) + "😀z"))
        assertEquals("AB", DeviceDisplayName.clean("A\ud800B\udc00"))
    }
}
