package seeyuer.sayin.asr

internal enum class DoubaoAuth { AppKey, AppIdToken }

/** Deliberately not a data class: credentials must never appear in generated diagnostics. */
internal class CloudConfiguration(
    val auth: DoubaoAuth,
    val appKey: String,
    val appId: String,
    val accessToken: String,
    hotwords: List<String>,
) {
    val hotwords = hotwords.toList()
    init {
        fun credential(value: String) = value.isNotEmpty() && value.length <= 4096 && value.all { it.code in 33..126 }
        require(when (auth) {
            DoubaoAuth.AppKey -> credential(appKey) && appId.isEmpty() && accessToken.isEmpty()
            DoubaoAuth.AppIdToken -> credential(appId) && credential(accessToken) && appKey.isEmpty()
        })
        require(hotwords.size <= 20 && hotwords.sumOf { it.length } <= 80)
        require(hotwords.all { it.isNotBlank() && it.length <= 40 && it.none(Char::isISOControl) })
        require(hotwords.distinct().size == hotwords.size)
    }
    override fun toString() = "CloudConfiguration(auth=$auth, credentials=[redacted])"
}

internal fun parseHotwords(text: String): List<String> {
    require(text.length <= 4096)
    return text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList().also {
        require(it.size <= 20 && it.sumOf(String::length) <= 80)
    }
}

internal enum class CloudIssue { Configuration, Storage, Network, Authentication, AccessDenied, Quota, Timeout, Protocol, Service }
/** Detail is a code-generated structural diagnostic, never provider payloads or credentials. */
internal class CloudRecognitionException(val issue: CloudIssue, val detail: String? = null) :
    Exception("Cloud ASR: $issue${detail?.let { " ($it)" }.orEmpty()}")
