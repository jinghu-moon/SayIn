package seeyuer.sayin.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** Feature UI references meaning; upstream icon names remain within the design system. */
enum class VoiceLinkIcon {
    Input, Computer, Send, Microphone, History, Settings, Scan, Wifi, Check, Alert, Shield, Close, Stop, Clear, ChevronRight, ChevronDown, Cloud, Phone, More, Undo, Redo, Translate, Photo, Copy, Automatic, ArrowUp, Retry;

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
            Close -> TablerVectors.Close
            Stop -> TablerVectors.Stop
            Clear -> TablerVectors.Clear
            ChevronRight -> TablerVectors.ChevronRight
            ChevronDown -> TablerVectors.ChevronDown
            Cloud -> TablerVectors.Cloud
            Phone -> TablerVectors.Phone
            More -> TablerVectors.More
            Undo -> TablerVectors.Undo
            Redo -> TablerVectors.Redo
            Translate -> TablerVectors.Translate
            Photo -> TablerVectors.Photo
            Copy -> TablerVectors.Copy
            Automatic -> TablerVectors.Automatic
            ArrowUp -> TablerVectors.ArrowUp
            Retry -> TablerVectors.Retry
        }
}
