package seeyuer.sayin.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolishPricingTest {
    private val catalog = PolishCatalog(
        version = "sha",
        source = "fixture",
        fetchedAt = "now",
        modelsByProvider = mapOf("deepseek" to listOf(
            PolishCatalogModel("deepseek-flash", "DeepSeek Flash", "deepseek", true, 0.15, 0.6, 0.003),
        )),
    )

    @Test fun `estimated price uses ordinary cache and output parts`() {
        val usage = PolishUsage(inputTotal = 1000, cacheRead = 200, outputTotal = 300)
        val price = PolishPricing.estimate(catalog, usage, PolishProvider.DeepSeek, "deepseek-flash",
            PolishCallPurpose.Polish)!!
        assertEquals(301L, price.micros)
        assertEquals("USD", price.currency)
        assertEquals(PolishCostSource.Estimated.name, price.source)
        assertEquals("basellm:deepseek-flash:usd", price.rule)
    }

    @Test fun `unknown model test purpose and cache write stay unknown`() {
        val usage = PolishUsage(inputTotal = 100, cacheWrite = 10, outputTotal = 20)
        assertNull(PolishPricing.estimate(catalog, usage, PolishProvider.DeepSeek, "deepseek-flash",
            PolishCallPurpose.Polish))
        assertNull(PolishPricing.estimate(catalog, usage, PolishProvider.DeepSeek, "missing",
            PolishCallPurpose.Polish))
        assertNull(PolishPricing.estimate(catalog, PolishUsage(1, null, null, 1), PolishProvider.DeepSeek,
            "deepseek-flash", PolishCallPurpose.Test))
    }
}
