package seeyuer.sayin.asr

/**
 * Final-only ITN for Chinese numbers, dates, times, percentages, decimals, money and quantities.
 * The acceptance corpus lives in `src/test/resources/voice/itn.tsv`; every rule here is meant to
 * be conservative: converting a number that should stay is worse than leaving one unconverted.
 */
internal object LocalItn {
    private const val DIGITS = "零一二三四五六七八九"
    private const val NUMBER = "(?:[一二三四五六七八九两]?十[一二三四五六七八九两]?|[零一二三四五六七八九两])"
    private const val NUMERAL = "${DIGITS}〇两十百千万亿0-9"
    private const val CN = "零〇一二三四五六七八九两"
    private const val MAGNITUDE = "十百千万亿"
    private const val CN_NUMERAL = "[$CN$MAGNITUDE]+"
    private const val CN_DIGITS = "零〇一二三四五六七八九"
    private const val DIGIT_RUN = "[${CN_DIGITS}幺]{2,}"

    // Brand and proper names from the voice-corpus policy (protect.terms.txt); never rewritten.
    private val brandTerms = listOf(
        "一加手机", "一加", "三星", "九号公司", "九号", "五粮液", "二锅头", "三只松鼠", "七匹狼", "一汽", "万科", "百度",
        "三一重工", "十万火急", "五十度灰", "十字路口", "十字架", "十字绣",
    )
    private val protected = Regex(
        brandTerms.joinToString("|") { Regex.escape(it) } + "|" + ProtectedText.URL_OR_CODE,
    )
    private val year = Regex("(?<![$NUMERAL])(二[零〇一二三四五六七八九]{3}|一千九百$NUMBER|两千(?:零$NUMBER)?)年")
    private val date = Regex("(?<![$NUMERAL])($NUMBER)月($NUMBER)([日号])")
    private val time = Regex("(?<![$NUMERAL])($NUMBER)点(半|整|钟|((?:零[一二三四五六七八九]|$NUMBER))分(?!之))")
    private val percentage = Regex(
        "(?<![A-Za-z0-9_.:/@=+%`-])百分之($CN_NUMERAL)(?:点([$DIGITS]+))?(?![$NUMERAL.点分之几多余来到至~～—])",
    )
    private val decimalWithUnit = Regex("(?<![$NUMERAL])($NUMBER)点([$DIGITS]+)(公里|千米|厘米|毫米|米|倍|折)")
    private const val DURATION_NUMBER = "(?:(?:$NUMBER|两)(?:点[$DIGITS]+)?|[0-9]+(?:\\.[0-9]+)?)"
    private val durationValue = Regex(DURATION_NUMBER)
    private val duration = Regex(
        "(?<![$NUMERAL.点第])($DURATION_NUMBER(?:[ \\t]*(?:、|或者|或|以及|和)[ \\t]*$DURATION_NUMBER)*)[ \\t]*(秒钟|秒)(?![杀表])",
    )
    private val durationSeparator = Regex("[ \\t]*(?:、|或者|或|以及|和)[ \\t]*")

    // 三十六度五 -> 36.5度 (EITHER form of a decimal temperature).
    private val degreeDecimal = Regex("(?<![$NUMERAL])($CN_NUMERAL)度([$DIGITS])(?![$CN$MAGNITUDE])")
    // 三百二十五块五 -> 325块5 (EITHER form of a money amount).
    private val moneyBlock = Regex("(?<![$NUMERAL])($CN_NUMERAL)([块元])([$DIGITS])(?![$CN$MAGNITUDE])")
    // 零点五 -> 0.5.
    private val zeroDecimal = Regex("(?<![$NUMERAL])零点([$DIGITS])(?![$CN$MAGNITUDE])")
    // 三点一四 -> 3.14; 三点一线 stays because the digit run has length one.
    private val plainDecimal = Regex("(?<![$NUMERAL])($CN_NUMERAL)点([$DIGITS]{2,})(?![$CN$MAGNITUDE])")
    // Numbers with a unit only convert when the value is ten or more, so 三个孩子/两只猫 stay.
    private val unitAmount = Regex(
        "(?<![$CN$MAGNITUDE])($CN_NUMERAL)(${UNITS})(?![$CN$MAGNITUDE])",
    )
    // Bare numbers: only values >= 100 (一百二十三/两千) or magnitudes (十万/一百万/两亿三千万).
    private val bareNumeral = Regex("(?<![${CN}${MAGNITUDE}0-9])($CN_NUMERAL)(?![${CN}${MAGNITUDE}0-9])")
    // Long digit strings such as 幺二三四… / 六八二九四一.
    private val digitRun = Regex("(?<![${CN}${MAGNITUDE}点])$DIGIT_RUN(?![${CN}${MAGNITUDE}点])")

    fun normalize(text: String): String = ProtectedText.mapOutside(text, protected) { normalizeProse(it) }

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
            val value = if (integral == "零") 0L else parseNumeral(integral) ?: return@replace it.value
            val fraction = if (fractional.isEmpty()) "" else ".${digits(fractional)}"
            "$value$fraction%"
        }
        val withDecimals = decimalWithUnit.replace(withPercentage) {
            val (integral, fractional, unit) = it.destructured
            "${number(integral)}.${digits(fractional)}$unit"
        }
        val withDuration = duration.replace(withDecimals) {
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
        val withDegrees = degreeDecimal.replace(withDuration) {
            val (whole, fraction) = it.destructured
            val integer = parseNumeral(whole) ?: return@replace it.value
            "$integer.${digits(fraction)}度"
        }
        val withMoney = moneyBlock.replace(withDegrees) {
            val (whole, unit, fraction) = it.destructured
            val integer = parseNumeral(whole) ?: return@replace it.value
            "$integer$unit${digits(fraction)}"
        }
        val withZeroDecimal = zeroDecimal.replace(withMoney) {
            "0.${digits(it.groupValues[1])}"
        }
        val withPlainDecimal = plainDecimal.replace(withZeroDecimal) {
            val (whole, fraction) = it.destructured
            val integer = parseNumeral(whole) ?: return@replace it.value
            "$integer.${digits(fraction)}"
        }
        val withUnits = unitAmount.replace(withPlainDecimal) { match ->
            val whole = match.groupValues[1]
            val unit = match.groupValues[2]
            val integer = parseNumeral(whole) ?: return@replace match.value
            if (integer < 10) match.value else "$integer$unit"
        }
        val withBare = bareNumeral.replace(withUnits) { match ->
            val raw = match.groupValues[1]
            val integer = parseNumeral(raw) ?: return@replace match.value
            val previous = withUnits.getOrNull(match.range.first - 1)
            val next = withUnits.getOrNull(match.range.last + 1)
            when {
                // 百分之百 / 百分之一千 / 二比一: the numeral is part of an expression, not a value.
                previous != null && previous in "分之比" -> match.value
                next != null && next in "分之比" -> match.value
                // 千万/万一 are adverbs or idioms, not numbers.
                raw == "千万" -> match.value
                integer < 100 -> match.value
                // 三万五 / 一百五 are colloquial abbreviations; leave them for a later rule.
                raw.length > 1 && raw.last() in DIGITS && raw.last() != '零' && raw[raw.length - 2] in "百千万亿" -> match.value
                else -> integer.toString()
            }
        }
        return digitRun.replace(withBare) { match ->
            match.value.map { char ->
                when (char) {
                    '幺' -> '1'
                    '〇' -> '0'
                    else -> DIGITS.indexOf(char).takeIf { it >= 0 }?.digitToChar() ?: char
                }
            }.joinToString("")
        }
    }

    private fun digits(raw: String) = raw.map { if (it == '〇') 0 else DIGITS.indexOf(it) }.joinToString("")

    private fun number(raw: String): Int {
        if (raw == "两") return 2
        val tens = raw.indexOf('十')
        if (tens < 0) return DIGITS.indexOf(raw.single())
        val leading = raw.getOrNull(tens - 1)?.let(DIGITS::indexOf) ?: 1
        val ones = raw.getOrNull(tens + 1)?.let(DIGITS::indexOf) ?: 0
        return 10 * leading + ones
    }

    private val DIGIT_VALUE = mapOf(
        '零' to 0L, '〇' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
        '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    )

    /** Parses a Chinese numeral up to 亿. Returns null for malformed or colloquial forms. */
    private fun parseNumeral(raw: String): Long? {
        if (raw.isEmpty()) return null
        // A lone 百/千/万/亿 is normally part of an idiom or fixed phrase, not a value.
        if (raw.length == 1 && raw[0] in "百千万亿") return null
        var total = 0L
        var section = 0L
        var digit = -1L
        var sawMagnitude = false
        for (char in raw) {
            val value = DIGIT_VALUE[char]
            if (value != null) {
                if (digit > 0 && value > 0) return null
                digit = value
                continue
            }
            val unit = when (char) {
                '十' -> 10L
                '百' -> 100L
                '千' -> 1000L
                '万' -> 10_000L
                '亿' -> 100_000_000L
                else -> return null
            }
            if (char == '万' || char == '亿') {
                if (digit < 0 && section == 0L) return null
                section += if (digit >= 0) digit else 0L
                digit = -1
                total += section * unit
                section = 0
                sawMagnitude = true
            } else {
                section += (if (digit >= 0) digit else 1L) * unit
                digit = -1
            }
        }
        if (digit >= 0) section += digit
        val result = total + section
        if (result <= 0) return null
        // A trailing bare digit after a magnitude ("三万五") is ambiguous; reject it.
        if (sawMagnitude && raw.last() in DIGIT_VALUE && raw.last() != '零' &&
            raw[raw.length - 2] in "百千万亿"
        ) {
            return null
        }
        return result
    }

    private const val UNIT_ALTERNATION =
        "小时|分钟|厘米|毫米|公里|公斤|毫升|个|人|位|名|家|岁|天|元|块|毛|角|度|米|斤|克|升|秒|次|倍|折|吨|页|行|本|份|张|台|部|只|头|条|件|瓶|杯|盘|辆|字"
    private const val UNITS = "(?:$UNIT_ALTERNATION)"
}






