package seeyuer.sayin.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import seeyuer.sayin.MainActivity
import seeyuer.sayin.SayInApplication
import seeyuer.sayin.designsystem.VoiceLinkTheme
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
        val cardHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.70f).dp
        setContent {
            Box(Modifier.fillMaxWidth().heightIn(max = cardHeight)) {
                VoiceLinkTheme {
                    VoiceLinkApp(quick = true, onExpand = { settings ->
                        startActivity(Intent(this@QuickActivity, MainActivity::class.java)
                            .putExtra(MainActivity.OPEN_SETTINGS, settings)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    companion object {
        fun intent(context: Context) = Intent(context, QuickActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 43, intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
