package seeyuer.sayin.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import seeyuer.sayin.MainActivity
import seeyuer.sayin.SayInApplication
import seeyuer.sayin.designsystem.Appearance
import seeyuer.sayin.designsystem.AppearanceMode
import seeyuer.sayin.designsystem.VoiceLinkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import seeyuer.sayin.ui.VoiceLinkApp

/** A real translucent native card. It presents the existing session and never starts recording itself. */
class QuickActivity : AppCompatActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as SayInApplication).model.speaking.cancelAutoSend()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.22f }
        setFinishOnTouchOutside(true)
        val density = resources.displayMetrics.density
        val cardHeight = (resources.displayMetrics.heightPixels / density * 0.66f).coerceAtMost(500f).dp
        val windowWidth = (resources.displayMetrics.widthPixels - 24 * density).toInt()
        setContent {
            val mode by Appearance.mode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
            }
            VoiceLinkTheme(dark = dark) {
                Box(Modifier.fillMaxWidth().height(cardHeight)
                    .clip(RoundedCornerShape(28.dp)).background(MiuixTheme.colorScheme.background)) {
                    VoiceLinkApp(quick = true, onExpand = { settings ->
                        startActivity(Intent(this@QuickActivity, MainActivity::class.java)
                            .putExtra(MainActivity.OPEN_SETTINGS, settings)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }, onDismiss = ::finish)
                }
            }
        }
        // AppCompat installs its floating-window decor during setContent and
        // resets its dimensions. Configure the actual decor after installation.
        // Keep native height flexible: a fixed height can measure beyond the
        // available IME frame, clipping the bottom controls on floating windows.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { y = (14 * density).toInt() }
        window.setLayout(windowWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    companion object {
        fun intent(context: Context) = Intent(context, QuickActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 43, intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
