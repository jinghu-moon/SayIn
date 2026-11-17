package seeyuer.sayin

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import seeyuer.sayin.designsystem.Appearance
import seeyuer.sayin.designsystem.AppearanceMode
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.VoiceLinkApp

class MainActivity : AppCompatActivity() {
    private var settingsRequested by mutableStateOf(false)
    private var entryVersion by mutableStateOf(0)
    private var sharedImage by mutableStateOf<Uri?>(null)
    private var shareVersion by mutableStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRequested = intent.getBooleanExtra(OPEN_SETTINGS, false)
        sharedImage = intent.imageUri()
        setContent {
            val mode by Appearance.mode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
            }
            VoiceLinkTheme(dark = dark) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                VoiceLinkApp(openSettings = settingsRequested, entryVersion = entryVersion,
                    sharedImage = sharedImage, shareVersion = shareVersion)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as SayInApplication).model.speaking.cancelAutoSend()
        settingsRequested = intent.getBooleanExtra(OPEN_SETTINGS, false)
        sharedImage = intent.imageUri()
        shareVersion++
        entryVersion++
    }

    private fun Intent.imageUri(): Uri? {
        if (action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else getParcelableExtra(Intent.EXTRA_STREAM)
        val item = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val candidate = direct ?: data ?: item?.uri ?: item?.intent?.data ?: return null
        val mime = contentResolver.getType(candidate) ?: type
        return candidate.takeIf { mime?.startsWith("image/") == true }
    }

    companion object { const val OPEN_SETTINGS = "open_settings" }
}
