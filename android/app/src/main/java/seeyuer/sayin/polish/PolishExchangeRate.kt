package seeyuer.sayin.polish

import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class PolishExchangeRate(
    val cnyPerUsd: BigDecimal,
    val fetchedAt: Long,
    val source: String = "Coinbase",
) {
    val valid: Boolean get() = cnyPerUsd > BigDecimal.ZERO
}

internal object PolishExchangeRateClient {
    private const val URL = "https://api.coinbase.com/v2/exchange-rates?currency=USD"
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetch(): Result<PolishExchangeRate> = runCatching {
        val request = Request.Builder().url(URL).get().header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val text = response.body?.string().orEmpty()
            require(text.length <= 64_000) { "响应过大" }
            val cny = JSONObject(text).getJSONObject("data").getJSONObject("rates").getString("CNY")
            val value = BigDecimal(cny)
            require(value > BigDecimal.ZERO) { "汇率无效" }
            PolishExchangeRate(value, System.currentTimeMillis())
        }
    }
}
