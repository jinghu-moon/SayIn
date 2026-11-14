package seeyuer.sayin.quick

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import seeyuer.sayin.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun QuickEntrySettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("presentation", 0) }
    val appName = stringResource(R.string.app_name)
    var enabled by remember { mutableStateOf(prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context)) }
    var denied by remember { mutableStateOf(false) }
    fun enable() {
        if (!Settings.canDrawOverlays(context)) { denied = true; return }
        try { ContextCompat.startForegroundService(context, Intent(context, QuickEntryService::class.java)); enabled = true; denied = false }
        catch (_: Exception) { enabled = false; denied = true }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enable() }
    fun overlay() {
        if (Settings.canDrawOverlays(context)) enable()
        else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { overlay() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) enabled = prefs.getBoolean("quick_ball", false) && Settings.canDrawOverlays(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.quick_entry_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.quick_entry_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        TextButton(stringResource(R.string.quick_open), { context.startActivity(QuickActivity.intent(context)) },
            Modifier.fillMaxWidth().testTag("open-quick-card"), minHeight = 48.dp)
        if (Build.VERSION.SDK_INT >= 33) TextButton(stringResource(R.string.quick_add_tile), {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, SpeakTileService::class.java), appName,
                AndroidIcon.createWithResource(context, R.drawable.ic_stat_microphone), ContextCompat.getMainExecutor(context)) { }
        }, Modifier.fillMaxWidth(), minHeight = 48.dp)
        else Text(stringResource(R.string.quick_tile_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.quick_ball), Modifier.weight(1f))
            Switch(enabled, { checked ->
                if (!checked) { context.stopService(Intent(context, QuickEntryService::class.java)); QuickEntryService.enabled(context, false); enabled = false }
                else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else overlay()
            }, Modifier.testTag("quick-ball"))
        }
        Text(stringResource(if (denied) R.string.quick_overlay_denied else R.string.quick_overlay_help), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}
