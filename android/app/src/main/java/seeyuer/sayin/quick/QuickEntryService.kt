package seeyuer.sayin.quick

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import seeyuer.sayin.R
import kotlin.math.abs

/** Explicitly enabled optional overlay; idle service never records or sends. */
class QuickEntryService : Service() {
    private var ball: ImageView? = null
    private lateinit var windows: WindowManager
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("quick_entry", getString(R.string.quick_entry_title), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "quick_entry").setSmallIcon(R.drawable.ic_stat_microphone)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.quick_entry_notification))
            .setContentIntent(QuickActivity.pendingIntent(this)).setOngoing(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.quick_disable), PendingIntent.getService(this, 44,
                Intent(this, QuickEntryService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        try { ServiceCompat.startForeground(this, 44, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { stopSelf(); return }
        windows = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val ballSize = (52 * dp).toInt()
        val peek = (24 * dp).toInt()
        val params = WindowManager.LayoutParams(ballSize, ballSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = resources.displayMetrics.widthPixels - peek
                y = (300 * dp).toInt()
            }
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_microphone); setColorFilter(Color.WHITE)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1F75DB.toInt()) }
            contentDescription = getString(R.string.quick_open)
            setOnClickListener { startActivity(QuickActivity.intent(this@QuickEntryService)) }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (startX + dx.toInt()).coerceIn(-(params.width - peek), resources.displayMetrics.widthPixels - peek)
                        params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - params.height)
                        windows.updateViewLayout(v, params)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val width = resources.displayMetrics.widthPixels
                        params.x = if (params.x + params.width / 2 < width / 2) -(params.width - peek) else width - peek
                        windows.updateViewLayout(v, params)
                    } else v.performClick()
                    true
                }
                else -> true
            }
        }
        try { windows.addView(view, params); ball = view; active = this; enabled(this, true) }
        catch (_: Exception) { stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (active === this) active = null
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }; ball = null
        enabled(this, false); super.onDestroy()
    }
    companion object {
        private const val STOP = "seeyuer.sayin.STOP_QUICK_ENTRY"
        private var active: QuickEntryService? = null

        /** Hide the overlay while the quick card owns the screen; show it again on close. */
        fun setQuickWindowVisible(visible: Boolean) {
            active?.ball?.visibility = if (visible) View.GONE else View.VISIBLE
        }
        fun enabled(context: Context, value: Boolean) { context.getSharedPreferences("presentation", 0).edit().putBoolean("quick_ball", value).apply() }
        fun startIfEnabled(context: Context) {
            val enabled = context.getSharedPreferences("presentation", 0).getBoolean("quick_ball", false)
            if (enabled && Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(context, Intent(context, QuickEntryService::class.java))
            }
        }
    }
}
