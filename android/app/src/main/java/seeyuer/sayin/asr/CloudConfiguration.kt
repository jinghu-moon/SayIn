package seeyuer.sayin.asr

internal enum class DoubaoAuth { AppKey, AppIdToken }

internal enum class CloudProvider { Doubao, Qwen }

internal sealed interface CloudCredentials {
    val provider: CloudProvider
}

/** Deliberately not a data class: credentials must never appear in generated diagnostics. */
internal class DoubaoCredentials(
    val auth: DoubaoAuth,
    val appKey: String,
    val appId: String,
    val accessToken: String,
) : CloudCredentials {
    override val provider = CloudProvider.Doubao
    init {
        require(when (auth) {
            DoubaoAuth.AppKey -> credential(appKey) && appId.isEmpty() && accessToken.isEmpty()
            DoubaoAuth.AppIdToken -> credential(appId) && credential(accessToken) && appKey.isEmpty()
        })
    }
}

internal class QwenCredentials(val apiKey: String, val workspaceId: String) : CloudCredentials {
    override val provider = CloudProvider.Qwen
    init {
        require(credential(apiKey))
        require(Regex("ws-[a-z0-9]{1,60}").matches(workspaceId))
    }
}

private fun credential(value: String) = value.isNotEmpty() && value.length <= 4096 && value.all { it.code in 33..126 }

internal class CloudConfiguration(val credentials: CloudCredentials, hotwords: List<String>) {
    val hotwords = hotwords.toList()
    init { validateHotwords(this.hotwords) }
}

internal data class CloudProfiles(
    val selected: CloudProvider = CloudProvider.Doubao,
    val doubao: DoubaoCredentials? = null,
    val qwen: QwenCredentials? = null,
    val hotwords: List<String> = emptyList(),
) {
    init { validateHotwords(hotwords) }
    val credentials: CloudCredentials? get() = when (selected) {
        CloudProvider.Doubao -> doubao
        CloudProvider.Qwen -> qwen
    }
}

private fun validateHotwords(words: List<String>) {
    require(words.size <= 20 && words.sumOf(String::length) <= 80)
    require(words.all { it.isNotBlank() && it.length <= 40 && it.none(Char::isISOControl) })
    require(words.distinct().size == words.size)
}

internal fun parseHotwords(text: String): List<String> {
    require(text.length <= 4096)
    return text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList().also(::validateHotwords)
}

internal enum class CloudIssue { Configuration, Storage, Network, Authentication, AccessDenied, Quota, Timeout, Protocol, Service }
/** Detail is a code-generated structural diagnostic, never provider payloads or credentials. */
internal class CloudRecognitionException(val issue: CloudIssue, val detail: String? = null) :
    Exception("Cloud ASR: $issue${detail?.let { " ($it)" }.orEmpty()}")
