package seeyuer.sayin.polish

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class PolishCostSource { Unknown, PresetNotConfigured }

internal data class PolishStatsEntry(
    val timestamp: Long,
    val provider: String,
    val model: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val durationMillis: Long,
    val outcome: String,
    val costSource: PolishCostSource,
    val status: Int?,
)

internal class PolishStatsStore(context: Context) {
    private val preferences = context.getSharedPreferences("polish_stats", Context.MODE_PRIVATE)

    fun load(): List<PolishStatsEntry> {
        val raw = preferences.getString("entries", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                    val item = array.optJSONObject(index) ?: continue
                    add(PolishStatsEntry(
                        timestamp = item.optLong("timestamp"),
                        provider = item.optString("provider"),
                        model = item.optString("model"),
                        inputTokens = item.optLongOrNull("inputTokens"),
                        outputTokens = item.optLongOrNull("outputTokens"),
                        durationMillis = item.optLong("durationMillis"),
                        outcome = item.optString("outcome"),
                        costSource = runCatching { PolishCostSource.valueOf(item.optString("costSource")) }
                            .getOrDefault(PolishCostSource.Unknown),
                        status = if (item.has("status")) item.optInt("status") else null,
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(entry: PolishStatsEntry) {
        val entries = (listOf(entry) + load()).take(MAX_ENTRIES)
        val array = JSONArray()
        entries.forEach { value ->
            array.put(JSONObject()
                .put("timestamp", value.timestamp)
                .put("provider", value.provider)
                .put("model", value.model)
                .put("inputTokens", value.inputTokens)
                .put("outputTokens", value.outputTokens)
                .put("durationMillis", value.durationMillis)
                .put("outcome", value.outcome)
                .put("costSource", value.costSource.name)
                .put("status", value.status))
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove("entries").apply()
    }

    companion object { const val MAX_ENTRIES = 100 }
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null
