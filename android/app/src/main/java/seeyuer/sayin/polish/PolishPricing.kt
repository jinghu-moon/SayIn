package seeyuer.sayin.polish

import java.math.BigDecimal
import java.math.RoundingMode

internal data class PolishPrice(
    val micros: Long,
    val currency: String,
    val source: String,
    val catalogVersion: String?,
    val rule: String,
)

internal object PolishPricing {
    /** Prices in basellm are USD per million tokens; one micro-USD is 1e-6 USD. */
    fun estimate(catalog: PolishCatalog?, usage: PolishUsage?, provider: PolishProvider,
                 model: String, purpose: PolishCallPurpose): PolishPrice? {
        if (purpose == PolishCallPurpose.Test || catalog == null || usage == null) return null
        val entry = catalog.modelsFor(provider).firstOrNull { it.id == model } ?: return null
        val input = usage.ordinaryInput ?: return null
        val cacheRead = usage.cacheRead ?: 0L
        val cacheWrite = usage.cacheWrite ?: 0L
        val output = usage.outputTotal ?: return null
        if (cacheWrite > 0L) return null // basellm has no verified cache-write rate in this snapshot
        val inputRate = entry.inputUsdPerMillion ?: return null
        val cacheRate = entry.cacheReadUsdPerMillion ?: 0.0
        val outputRate = entry.outputUsdPerMillion ?: return null
        val micros = BigDecimal.valueOf(input).multiply(BigDecimal.valueOf(inputRate))
            .add(BigDecimal.valueOf(cacheRead).multiply(BigDecimal.valueOf(cacheRate)))
            .add(BigDecimal.valueOf(output).multiply(BigDecimal.valueOf(outputRate)))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        return PolishPrice(
            micros = micros,
            currency = "USD",
            source = PolishCostSource.Estimated.name,
            catalogVersion = catalog.version,
            rule = "basellm:${entry.id}:usd",
        )
    }
}
