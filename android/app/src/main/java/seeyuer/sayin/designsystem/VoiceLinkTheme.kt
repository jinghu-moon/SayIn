package seeyuer.sayin.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VoiceLinkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = remember(dark) { voiceLinkColors(dark) }
    MiuixTheme(colors = colors, content = content)
}

/** Every Miuix color role is explicit: no Monet or implicit red/green defaults. */
private fun voiceLinkColors(dark: Boolean): Colors = with(YuePalette) {
    val canvas = if (dark) Neutral950 else Neutral100
    val surface = if (dark) Neutral900 else Neutral0
    val raised = if (dark) Neutral850 else Neutral50
    val ink = if (dark) Neutral100 else Neutral900
    val secondaryInk = if (dark) Neutral400 else Neutral700
    val muted = if (dark) Neutral600 else Neutral400
    val line = if (dark) Neutral800 else Neutral300
    Colors(
        primary = Brand,
        onPrimary = Neutral0,
        primaryVariant = Brand,
        onPrimaryVariant = Neutral0,
        error = ink,
        onError = surface,
        errorContainer = raised,
        onErrorContainer = ink,
        disabledPrimary = line,
        disabledOnPrimary = muted,
        disabledPrimaryButton = line,
        disabledOnPrimaryButton = secondaryInk,
        disabledPrimarySlider = muted,
        primaryContainer = Brand,
        onPrimaryContainer = Neutral0,
        secondary = ink,
        onSecondary = surface,
        secondaryVariant = secondaryInk,
        onSecondaryVariant = surface,
        disabledSecondary = line,
        disabledOnSecondary = muted,
        disabledSecondaryVariant = line,
        disabledOnSecondaryVariant = muted,
        secondaryContainer = raised,
        onSecondaryContainer = ink,
        secondaryContainerVariant = raised,
        onSecondaryContainerVariant = secondaryInk,
        tertiaryContainer = canvas,
        onTertiaryContainer = ink,
        tertiaryContainerVariant = raised,
        background = canvas,
        onBackground = ink,
        onBackgroundVariant = secondaryInk,
        surface = surface,
        onSurface = ink,
        surfaceVariant = raised,
        onSurfaceSecondary = secondaryInk,
        onSurfaceVariantSummary = secondaryInk,
        onSurfaceVariantActions = ink,
        disabledOnSurface = muted,
        surfaceContainer = surface,
        onSurfaceContainer = ink,
        onSurfaceContainerVariant = secondaryInk,
        surfaceContainerHigh = raised,
        onSurfaceContainerHigh = ink,
        surfaceContainerHighest = raised,
        onSurfaceContainerHighest = ink,
        outline = line,
        dividerLine = line,
        windowDimming = Neutral1000.copy(alpha = 0.5f),
        sliderKeyPoint = Neutral0,
        sliderKeyPointForeground = Brand,
        sliderBackground = line,
    )
}
