package seeyuer.sayin.quick

import android.os.Build
import android.annotation.SuppressLint
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import seeyuer.sayin.R
import seeyuer.sayin.SayInApplication

class SpeakTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.app_name)
            state = if ((application as SayInApplication).model.speaking.busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(R.string.quick_tile_summary)
            updateTile()
        }
    }
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // The PendingIntent overload does not exist below API 34.
    override fun onClick() {
        super.onClick()
        // Unlock before showing private text. A tile click is never microphone consent.
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(QuickActivity.pendingIntent(this))
            else startActivityAndCollapse(QuickActivity.intent(this))
        }
    }
}
