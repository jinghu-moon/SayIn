package seeyuer.sayin.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** Feature UI references meaning; upstream icon names remain within the design system. */
enum class VoiceLinkIcon {
    Input, Computer, Send, Microphone, History, Settings, Scan, Wifi, Check, Alert, Shield;

    val vector: ImageVector
        get() = when (this) {
            Input -> TablerVectors.Keyboard
            Computer -> TablerVectors.Computer
            Send -> TablerVectors.Send
            Microphone -> TablerVectors.Microphone
            History -> TablerVectors.History
            Settings -> TablerVectors.Settings
            Scan -> TablerVectors.Scan
            Wifi -> TablerVectors.Wifi
            Check -> TablerVectors.Check
            Alert -> TablerVectors.Alert
            Shield -> TablerVectors.Shield
        }
}
