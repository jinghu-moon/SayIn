package seeyuer.sayin

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.VoiceLinkApp

class MainActivity : AppCompatActivity() {
    private var settingsRequested by mutableStateOf(false)
    private var entryVersion by mutableStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRequested = intent.getBooleanExtra(OPEN_SETTINGS, false)
        setContent {
            VoiceLinkTheme { VoiceLinkApp(openSettings = settingsRequested, entryVersion = entryVersion) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as SayInApplication).model.speaking.cancelAutoSend()
        settingsRequested = intent.getBooleanExtra(OPEN_SETTINGS, false)
        entryVersion++
    }
    companion object { const val OPEN_SETTINGS = "open_settings" }
}
