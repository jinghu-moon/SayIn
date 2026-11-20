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
        val price = PolishPricing.estimate(catalog, usage, "deepseek", "deepseek-flash",
            PolishCallPurpose.Polish)!!
        assertEquals(301L, price.micros)
        assertEquals("USD", price.currency)
        assertEquals(PolishCostSource.Estimated.name, price.source)
        assertEquals("basellm:deepseek-flash:usd", price.rule)
    }

    @Test fun `unknown model test purpose and missing cache write rate stay unknown`() {
        val usage = PolishUsage(inputTotal = 100, cacheWrite = 10, outputTotal = 20)
        assertNull(PolishPricing.estimate(catalog, usage, "deepseek", "deepseek-flash",
            PolishCallPurpose.Polish))
        assertNull(PolishPricing.estimate(catalog, usage, "deepseek", "missing",
            PolishCallPurpose.Polish))
        assertNull(PolishPricing.estimate(catalog, PolishUsage(1, null, null, 1), "deepseek",
            "deepseek-flash", PolishCallPurpose.Test))
    }

    @Test fun `matching schedule window overrides base rates`() {
        val model = PolishCatalogModel(
            "scheduled", "Scheduled", "deepseek", true, 0.15, 0.6, 0.003, null,
            schedule = PolishPriceSchedule("UTC", "off_peak", listOf(
                PolishPriceWindow(setOf(5), listOf(60 to 240), PolishPriceRates(0.3, 1.2, 0.006, null)),
            )),
        )
        val scheduled = PolishCatalog("sha", "fixture", "now", mapOf("fixture" to listOf(model)))
        val at = java.time.Instant.parse("2026-11-20T02:00:00Z").toEpochMilli()
        val price = PolishPricing.estimate(scheduled, PolishUsage(100, null, null, 10),
            "fixture", "scheduled", PolishCallPurpose.Polish, at)!!
        assertEquals(42L, price.micros) // 100 * 0.3 + 10 * 1.2
        assertEquals("basellm:scheduled:usd:window", price.rule)
    }

    @Test fun `cache write and context tier use their own rates`() {
        val model = PolishCatalogModel(
            "tiered", "Tiered", "deepseek", true, 0.15, 0.6, 0.003, 0.125,
            tiers = listOf(PolishPriceTier(2000, PolishPriceRates(0.3, 1.2, 0.006, 0.25))),
        )
        val tierCatalog = PolishCatalog("sha", "fixture", "now", mapOf("deepseek" to listOf(model)))
        val usage = PolishUsage(inputTotal = 3100, cacheWrite = 100, outputTotal = 100)
        val price = PolishPricing.estimate(tierCatalog, usage, "deepseek", "tiered",
            PolishCallPurpose.Polish)!!
        // (3000 ordinary * 0.3) + (100 cache write * 0.25) + (100 output * 1.2) = 1045 micros
        assertEquals(1045L, price.micros)
        assertEquals("basellm:tiered:usd:tier2000", price.rule)
    }

    @Test fun `official endpoint determines the catalog region and relays get no official price`() {
        val config = PolishConfig.preset(PolishProvider.Qwen)
        val international = config.copy(baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
        val relay = config.copy(baseUrl = "https://relay.example/v1")
        assertEquals("alibaba-cn", config.officialCatalogProvider)
        assertEquals("alibaba", international.officialCatalogProvider)
        assertNull(relay.officialCatalogProvider)
        val mainland = PolishCatalogModel("qwen", "Qwen", "alibaba-cn", false, 0.1, 0.2, null)
        val global = mainland.copy(providerId = "alibaba", inputUsdPerMillion = 0.4, outputUsdPerMillion = 0.8)
        val regional = catalog.copy(modelsByProvider = mapOf("alibaba-cn" to listOf(mainland), "alibaba" to listOf(global)))
        val usage = PolishUsage(100, outputTotal = 50)
        assertEquals(20L, PolishPricing.estimate(regional, usage, config.officialCatalogProvider, "qwen", PolishCallPurpose.Polish)?.micros)
        assertEquals(80L, PolishPricing.estimate(regional, usage, international.officialCatalogProvider, "qwen", PolishCallPurpose.Polish)?.micros)
        assertNull(PolishPricing.estimate(regional, usage.copy(cacheRead = 10), config.officialCatalogProvider, "qwen", PolishCallPurpose.Polish))
    }
}
