package seeyuer.sayin.designsystem

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.sayin.R

enum class AppearanceMode(@StringRes val labelRes: Int) {
    LIGHT(R.string.appearance_light),
    DARK(R.string.appearance_dark),
    SYSTEM(R.string.appearance_system),
}

/** Process-wide appearance selection, persisted independently of UI state. */
object Appearance {
    private const val PREFS = "appearance"
    private const val KEY = "mode"
    private val state = MutableStateFlow(AppearanceMode.SYSTEM)
    val mode = state.asStateFlow()

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, 0).getString(KEY, AppearanceMode.SYSTEM.name)
        state.value = AppearanceMode.entries.firstOrNull { it.name == saved } ?: AppearanceMode.SYSTEM
    }

    fun set(context: Context, mode: AppearanceMode) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, mode.name).apply()
        state.value = mode
    }
}
