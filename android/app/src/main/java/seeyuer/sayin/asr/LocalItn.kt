package seeyuer.sayin.asr

/** Final-only ITN for Chinese numbers with an explicit format. */
internal object LocalItn {
    private const val DIGITS = "零一二三四五六七八九"
    private const val NUMBER = "(?:[一二三四五六七八九]?十[一二三四五六七八九]?|[零一二三四五六七八九])"
    private const val NUMERAL = "${DIGITS}〇两十百千万亿0-9"
    private val protected = Regex("""`[^`]*`|(?:https?://|www\.|[A-Za-z]:[\\/]|/)[^\s，。！？；、]+|(?!\d+\.\d+(?![A-Za-z0-9_.\\@~+:%=-]))[A-Za-z0-9_]+(?:[.\\@~+:%=-][A-Za-z0-9_]+)+""")
    private val year = Regex("(?<![$NUMERAL])(二[零〇一二三四五六七八九]{3}|一千九百$NUMBER|两千(?:零$NUMBER)?)年")
    private val date = Regex("(?<![$NUMERAL])($NUMBER)月($NUMBER)([日号])")
    private val time = Regex("(?<![$NUMERAL])($NUMBER)点(半|整|钟|((?:零[一二三四五六七八九]|$NUMBER))分(?!之))")
    private val percentage = Regex(
        "(?<![A-Za-z0-9_.:/@=+%`-])百分之($NUMBER)(?:点([$DIGITS]+))?(?![$NUMERAL.点分之几多余来到至~～—])",
    )
    private val decimalWithUnit = Regex("(?<![$NUMERAL])($NUMBER)点([$DIGITS]+)(公里|千米|厘米|毫米|米|倍|折)")
    private const val DURATION_NUMBER = "(?:(?:$NUMBER|两)(?:点[$DIGITS]+)?|[0-9]+(?:\\.[0-9]+)?)"
    private val durationValue = Regex(DURATION_NUMBER)
    private val duration = Regex(
        "(?<![$NUMERAL.点第])($DURATION_NUMBER(?:[ \\t]*(?:、|或者|或|以及|和)[ \\t]*$DURATION_NUMBER)*)[ \\t]*(秒钟|秒)(?![杀表])",
    )
    private val durationSeparator = Regex("[ \\t]*(?:、|或者|或|以及|和)[ \\t]*")

    fun normalize(text: String): String = buildString {
        var start = 0
        for (match in protected.findAll(text)) {
            append(normalizeProse(text.substring(start, match.range.first)))
            append(match.value)
            start = match.range.last + 1
        }
        append(normalizeProse(text.substring(start)))
    }

    private fun normalizeProse(text: String): String {
        val withYear = year.replace(text) {
            val raw = it.groupValues[1]
            val value = when {
                raw == "两千" -> "2000"
                raw.startsWith("两千零") -> (2000 + number(raw.drop(3))).toString()
                raw.startsWith("一千九百") -> (1900 + number(raw.drop(4))).toString()
                else -> digits(raw)
            }
            "${value}年"
        }
        val withDate = date.replace(withYear) {
            val (monthText, dayText, suffix) = it.destructured
            val month = number(monthText)
            val day = number(dayText)
            val days = when (month) {
                2 -> 29
                4, 6, 9, 11 -> 30
                in 1..12 -> 31
                else -> 0
            }
            if (day !in 1..days) it.value else "${month}月$day$suffix"
        }
        val withTime = time.replace(withDate) {
            val (hourText, suffix, minuteText) = it.destructured
            val hour = number(hourText)
            val minute = if (minuteText.startsWith("零") && minuteText.length == 2) {
                number(minuteText.drop(1)).toString().padStart(2, '0')
            } else minuteText.takeIf(String::isNotEmpty)?.let { minute -> number(minute).toString() }
            if (hour !in 0..23 || (minute?.toIntOrNull() ?: 0) > 59) it.value
            else "${hour}点${if (minute == null) suffix else "${minute}分"}"
        }
        val withPercentage = percentage.replace(withTime) {
            val (integral, fractional) = it.destructured
            val fraction = if (fractional.isEmpty()) "" else ".${digits(fractional)}"
            "${number(integral)}$fraction%"
        }
        val withDecimals = decimalWithUnit.replace(withPercentage) {
            val (integral, fractional, unit) = it.destructured
            "${number(integral)}.${digits(fractional)}$unit"
        }
        return duration.replace(withDecimals) {
            val (values, unit) = it.destructured
            val normalized = durationValue.replace(values) { match ->
                val raw = match.value
                if (raw.first() in '0'..'9') raw else {
                    val whole = raw.substringBefore('点')
                    val fraction = raw.substringAfter('点', "")
                    val integer = if (whole == "两") 2 else number(whole)
                    if (fraction.isEmpty()) integer.toString() else "$integer.${digits(fraction)}"
                }
            }
            if (!durationSeparator.containsMatchIn(normalized)) {
                if (normalized == values) it.value else "$normalized$unit"
            } else {
                val formatted = durationSeparator.replace(normalized) { match ->
                    val separator = match.value.trim()
                    if (separator == "、") separator else " $separator "
                }
                "$formatted $unit"
            }
        }
    }

    private fun digits(raw: String) = raw.map { if (it == '〇') 0 else DIGITS.indexOf(it) }.joinToString("")

    private fun number(raw: String): Int {
        val tens = raw.indexOf('十')
        if (tens < 0) return DIGITS.indexOf(raw.single())
        val leading = raw.getOrNull(tens - 1)?.let(DIGITS::indexOf) ?: 1
        val ones = raw.getOrNull(tens + 1)?.let(DIGITS::indexOf) ?: 0
        return 10 * leading + ones
    }
}
