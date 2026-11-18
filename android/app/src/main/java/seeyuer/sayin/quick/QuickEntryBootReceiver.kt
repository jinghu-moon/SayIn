package seeyuer.sayin.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the explicitly enabled floating entry after boot or an app update. */
class QuickEntryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                QuickEntryService.startIfEnabled(context)
        }
    }
}
