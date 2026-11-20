package seeyuer.sayin.polish

import org.json.JSONObject

/**
 * Normalized token accounting. Chat Completions includes cache hits in
 * prompt_tokens; Anthropic reports the three input components separately.
 */
internal data class PolishUsage(
    val inputTotal: Long?,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val outputTotal: Long?,
    val outputReasoning: Long? = null,
) {
    val ordinaryInput: Long? get() = when {
        inputTotal == null -> null
        else -> (inputTotal - (cacheRead ?: 0L) - (cacheWrite ?: 0L)).coerceAtLeast(0L)
    }

    companion object {
        fun fromChat(json: JSONObject?): PolishUsage? {
            val usage = json ?: return null
            if (usage.hasNegative("prompt_tokens", "completion_tokens",
                    "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")) return null
            val prompt = usage.nonNegative("prompt_tokens")
            val completion = usage.nonNegative("completion_tokens")
            val cached = usage.optJSONObject("prompt_tokens_details")?.nonNegative("cached_tokens")
                ?: usage.nonNegative("prompt_cache_hit_tokens")
            val miss = usage.nonNegative("prompt_cache_miss_tokens")
            val input = prompt ?: listOfNotNull(cached, miss).takeIf { it.isNotEmpty() }?.sum()
            if (input == null && completion == null) return null
            if (cached != null && input != null && cached > input) return null
            val reasoning = usage.optJSONObject("completion_tokens_details")?.nonNegative("reasoning_tokens")
            if (reasoning != null && completion != null && reasoning > completion) return null
            return PolishUsage(input, cached, null, completion, reasoning)
        }

        fun fromMessages(json: JSONObject?): PolishUsage? {
            val usage = json ?: return null
            if (usage.hasNegative("input_tokens", "cache_creation_input_tokens",
                    "cache_read_input_tokens", "output_tokens")) return null
            val plain = usage.nonNegative("input_tokens")
            val cacheWrite = usage.nonNegative("cache_creation_input_tokens")
            val cacheRead = usage.nonNegative("cache_read_input_tokens")
            val output = usage.nonNegative("output_tokens")
            if (plain == null && cacheWrite == null && cacheRead == null && output == null) return null
            val input = listOfNotNull(plain, cacheWrite, cacheRead).takeIf { it.isNotEmpty() }?.sum()
            return PolishUsage(input, cacheRead, cacheWrite, output)
        }

        private fun JSONObject.nonNegative(name: String): Long? {
            if (!has(name) || isNull(name)) return null
            val value = optLong(name, -1L)
            return value.takeIf { it >= 0L }
        }

        private fun JSONObject.hasNegative(vararg names: String): Boolean =
            names.any { has(it) && !isNull(it) && optLong(it, 0L) < 0L }
    }
}
