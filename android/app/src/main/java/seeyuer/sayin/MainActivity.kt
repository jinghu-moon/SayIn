package seeyuer.sayin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.VoiceLinkApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceLinkTheme { VoiceLinkApp() }
        }
    }
}
