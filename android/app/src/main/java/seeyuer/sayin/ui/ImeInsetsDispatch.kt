package seeyuer.sayin.ui

import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** API29 computes IME insets from visible bounds, which can settle after the
 * original insets dispatch. Redispatch only when those platform bounds change;
 * Compose remains the sole source of layout padding and visibility. */
@Composable
internal fun RefreshImeInsetsAfterLayout() {
    if (Build.VERSION.SDK_INT != 29) return
    val view = LocalView.current
    DisposableEffect(view) {
        var lastBottom = -1
        val observer = ViewTreeObserver.OnGlobalLayoutListener {
            val bottom = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
            if (bottom != null && bottom != lastBottom) {
                lastBottom = bottom
                ViewCompat.requestApplyInsets(view)
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(observer)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(observer) }
    }
}
