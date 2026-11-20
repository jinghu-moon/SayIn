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
                 model: String, purpose: PolishCallPurpose,
                 atMillis: Long = System.currentTimeMillis()): PolishPrice? {
        if (purpose == PolishCallPurpose.Test || catalog == null || usage == null) return null
        val entry = catalog.modelsFor(provider).firstOrNull { it.id == model } ?: return null
        val input = usage.ordinaryInput ?: return null
        val cacheRead = usage.cacheRead ?: 0L
        val cacheWrite = usage.cacheWrite ?: 0L
        val output = usage.outputTotal ?: return null
        val tier = entry.tiers.filter { input + cacheRead + cacheWrite > it.minContext }
            .maxByOrNull { it.minContext }
        val window = matchWindow(entry.schedule, atMillis)
        val rates = window?.rates ?: tier?.rates ?: PolishPriceRates(
            entry.inputUsdPerMillion, entry.outputUsdPerMillion,
            entry.cacheReadUsdPerMillion, entry.cacheWriteUsdPerMillion,
        )
        val inputRate = rates.input ?: return null
        val outputRate = rates.output ?: return null
        val cacheReadRate = rates.cacheRead ?: 0.0
        if (cacheWrite > 0L && rates.cacheWrite == null) return null
        val micros = BigDecimal.valueOf(input).multiply(BigDecimal.valueOf(inputRate))
            .add(BigDecimal.valueOf(cacheRead).multiply(BigDecimal.valueOf(cacheReadRate)))
            .add(BigDecimal.valueOf(cacheWrite).multiply(BigDecimal.valueOf(rates.cacheWrite ?: 0.0)))
            .add(BigDecimal.valueOf(output).multiply(BigDecimal.valueOf(outputRate)))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        return PolishPrice(
            micros = micros,
            currency = "USD",
            source = PolishCostSource.Estimated.name,
            catalogVersion = catalog.version,
            rule = buildString {
                append("basellm:").append(entry.id).append(":usd")
                if (tier != null) append(":tier").append(tier.minContext)
                if (window != null) append(":window")
            },
        )
    }

    private fun matchWindow(schedule: PolishPriceSchedule?, atMillis: Long): PolishPriceWindow? {
        if (schedule == null) return null
        val zone = runCatching { java.time.ZoneId.of(schedule.zoneId) }.getOrNull() ?: return null
        val local = java.time.Instant.ofEpochMilli(atMillis).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return schedule.windows.firstOrNull { window ->
            (window.weekdays.isEmpty() || local.dayOfWeek.value in window.weekdays) &&
                window.hours.any { (start, end) -> minute >= start && minute < end }
        }
    }
}
