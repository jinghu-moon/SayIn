package seeyuer.sayin.ui.localization

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Stable BCP 47 tags. An empty list means system, not an explicit English preference. */
enum class AppLanguage(val tag: String, @param:StringRes val labelRes: Int) {
    System("", seeyuer.sayin.R.string.language_system),
    SimplifiedChinese("zh-Hans", seeyuer.sayin.R.string.language_simplified_chinese),
    English("en", seeyuer.sayin.R.string.language_english);
}

object AppLanguages {
    /** Never infer the saved preference from Locale.getDefault() or a translated label. */
    @MainThread
    fun current(): AppLanguage? {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AppLanguage.System
        val first = locales[0] ?: return null
        return when {
            first.language == "en" -> AppLanguage.English
            first.language == "zh" && (first.script == "Hans" ||
                (first.script.isEmpty() && first.country in setOf("", "CN", "SG"))) ->
                AppLanguage.SimplifiedChinese
            else -> null
        }
    }

    @MainThread
    fun select(language: AppLanguage) {
        val locales = if (language == AppLanguage.System) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language.tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
