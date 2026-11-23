package seeyuer.sayin.quick

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Foreground-app detection backed by Usage Access.
 *
 * Callers must obtain the user's Usage Access permission first; without it
 * [currentPackage] returns null and the floating ball must stay visible.
 */
internal object ForegroundApp {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun currentPackage(context: Context): String? {
        if (!hasAccess(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = manager.queryEvents(end - 10_000L, end) ?: return null
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foreground = event.packageName
            }
        }
        return foreground?.takeIf { it.isNotBlank() }
    }
}
