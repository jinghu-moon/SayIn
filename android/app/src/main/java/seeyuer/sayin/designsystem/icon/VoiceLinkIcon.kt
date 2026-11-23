package seeyuer.sayin.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** Feature UI references meaning; upstream icon names remain within the design system. */
enum class VoiceLinkIcon {
    Input, Computer, Send, Microphone, History, Search, Filter, Download, Settings, Scan, Wifi, Check, Alert, Shield, Close, Stop, Clear, ChevronRight, ChevronDown, Cloud, Phone, More, Undo, Redo, Translate, Photo, Copy, Automatic, Ai, ArrowUp, Retry, Clipboard, Plus, ArrowLeft, GripVertical, Share,
    DeviceDesktopCheck, DeviceDesktopMinus, DeviceDesktopPlus, DeviceDesktopPause,
    DeviceDesktopExclamation, DeviceDesktopX, ArrowsLeftRight, Loader2, Radar, Dots, Hourglass, Eye, EyeOff, ArrowsDiagonal,
    Database, Folder, File, ExternalLink;

    val vector: ImageVector
        get() = when (this) {
            Input -> TablerVectors.Keyboard
            Computer -> TablerVectors.Computer
            Send -> TablerVectors.Send
            Microphone -> TablerVectors.Microphone
            History -> TablerVectors.History
            Search -> TablerVectors.Search
            Filter -> TablerVectors.Filter
            Download -> TablerVectors.Download
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
            Ai -> TablerVectors.Ai
            ArrowUp -> TablerVectors.ArrowUp
            Retry -> TablerVectors.Retry
            Clipboard -> TablerVectors.Clipboard
            Plus -> TablerVectors.Plus
            ArrowLeft -> TablerVectors.ArrowLeft
            GripVertical -> TablerVectors.GripVertical
            Share -> TablerVectors.Share
            DeviceDesktopCheck -> TablerVectors.DeviceDesktopCheck
            DeviceDesktopMinus -> TablerVectors.DeviceDesktopMinus
            DeviceDesktopPlus -> TablerVectors.DeviceDesktopPlus
            DeviceDesktopPause -> TablerVectors.DeviceDesktopPause
            DeviceDesktopExclamation -> TablerVectors.DeviceDesktopExclamation
            DeviceDesktopX -> TablerVectors.DeviceDesktopX
            ArrowsLeftRight -> TablerVectors.ArrowsLeftRight
            Loader2 -> TablerVectors.Loader2
            Radar -> TablerVectors.Radar
            Dots -> TablerVectors.Dots
            Hourglass -> TablerVectors.Hourglass
            Eye -> TablerVectors.Eye
            EyeOff -> TablerVectors.EyeOff
            ArrowsDiagonal -> TablerVectors.ArrowsDiagonal
            Database -> TablerVectors.Database
            Folder -> TablerVectors.Folder
            File -> TablerVectors.File
            ExternalLink -> TablerVectors.ExternalLink
        }
}
