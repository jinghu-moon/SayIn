package seeyuer.sayin.polish

internal object PolishPreferenceRules {
    fun validTerms(value: String): Boolean {
        val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 20) return false
        return lines.all { line ->
            val eq = line.indexOf('=')
            line.codePointCount(0, line.length) <= 60 &&
                eq in 1 until line.lastIndex &&
                line.substring(0, eq).isNotBlank() && line.substring(eq + 1).isNotBlank()
        }
    }

    fun validPrompt(value: String): Boolean {
        if (value.any { it == '\n' || it == '\r' || it.isISOControl() }) return false
        return value.codePointCount(0, value.length) <= 200
    }
}
