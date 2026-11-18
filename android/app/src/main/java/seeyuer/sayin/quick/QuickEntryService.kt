package seeyuer.sayin.quick

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import seeyuer.sayin.R
import seeyuer.sayin.SayInApplication
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import kotlin.math.abs

/** Explicitly enabled optional overlay; idle service never records or sends. */
class QuickEntryService : Service() {
    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var edgeLeft = false
    private var expanded = false
    private var latestState: ClientState? = null
    private lateinit var windows: WindowManager
    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressed = false
    private var dragging = false
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("quick_entry", getString(R.string.quick_entry_title), NotificationManager.IMPORTANCE_LOW))
        val repository = (application as SayInApplication).model.repository
        latestState = repository.state.value
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(repository.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { stopSelf(); return }
        scope.launch {
            repository.state.collect { state ->
                latestState = state
                updateNotification()
            }
        }
        windows = getSystemService(WindowManager::class.java)
        showBall()
    }

    private fun showBall() {
        if (ball != null || !Settings.canDrawOverlays(this)) return
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val p = params ?: WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (300 * dp).toInt()
        }.also { params = it }
        p.x = peekX()
        val view = BallView(this, R.drawable.ic_sayin_brand_fyan_mark).apply {
            contentDescription = getString(R.string.quick_open)
            setPeekAtLeft(edgeLeft)
            alpha = PEEK_ALPHA
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = params?.x ?: 0; startY = params?.y ?: 0
                    longPressed = false; dragging = false; moved = false
                    longPress = Runnable {
                        longPressed = true
                        expandBall()
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!longPressed) {
                        if (abs(dx) > ViewConfiguration.get(this).scaledTouchSlop ||
                            abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) {
                            moved = true
                            longPress?.let { handler.removeCallbacks(it) }
                            longPress = null
                        }
                    } else {
                        dragging = true
                        val layout = params ?: return@setOnTouchListener true
                        layout.x = (startX + dx.toInt()).coerceIn(0,
                            resources.displayMetrics.widthPixels - layout.width)
                        layout.y = (startY + dy.toInt()).coerceIn(0,
                            resources.displayMetrics.heightPixels - layout.height)
                        windows.updateViewLayout(v, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress ?: Runnable {})
                    longPress = null
                    if (longPressed) {
                        snapToEdge(if ((params?.x ?: 0) + size / 2 < resources.displayMetrics.widthPixels / 2) 1 else -1)
                    } else if (!dragging && !moved) {
                        if (expanded) openQuick() else expandBall()
                    }
                    longPressed = false; dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress ?: Runnable {})
                    longPress = null
                    longPressed = false; dragging = false
                    true
                }
                else -> false
            }
        }
        try {
            windows.addView(view, p)
            ball = view
            active = this
            enabled(this, true)
            updateNotification()
        } catch (_: Exception) {
            enabled(this, false)
            stopSelf()
        }
    }

    private fun hideBall() {
        handler.removeCallbacksAndMessages(null)
        longPress = null
        expanded = false
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }
        ball = null
        enabled(this, false)
        updateNotification()
    }

    private fun updateNotification() {
        val state = latestState ?: return
        notificationManager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun peekWidth(): Int = (23 * resources.displayMetrics.density).toInt()

    private fun fullX(): Int = if (edgeLeft) 0 else resources.displayMetrics.widthPixels - (ball?.width ?: 0)

    private fun peekX(): Int = if (edgeLeft) -peekWidth() else resources.displayMetrics.widthPixels - peekWidth()

    private fun expandBall() {
        expanded = true
        ball?.setExpanded(true)
        ball?.alpha = 1f
        val p = params ?: return
        p.x = fullX()
        windows.updateViewLayout(ball ?: return, p)
    }

    private fun collapseToPeek() {
        expanded = false
        ball?.setExpanded(false)
        ball?.alpha = PEEK_ALPHA
        val p = params ?: return
        p.x = peekX()
        windows.updateViewLayout(ball ?: return, p)
    }

    private fun snapToEdge(side: Int) {
        edgeLeft = side > 0
        ball?.setPeekAtLeft(edgeLeft)
        collapseToPeek()
    }

    private fun openQuick() {
        startActivity(QuickActivity.intent(this))
    }

    private fun notification(state: ClientState): Notification {
        val target = state.foregroundApplication?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.speak_computer_unknown)
        val computer = state.address.takeIf { it.isNotBlank() }?.let {
            getString(R.string.quick_notification_computer, it)
        } ?: getString(R.string.quick_notification_public)
        val title = when {
            state.ready -> getString(R.string.quick_notification_connected, target)
            state.paused -> getString(R.string.quick_notification_paused)
            state.connection == Connection.ONLINE -> getString(R.string.quick_notification_connecting)
            else -> getString(R.string.quick_notification_offline)
        }
        val open = QuickActivity.pendingIntent(this)
        val toggle = PendingIntent.getService(this, 45,
            Intent(this, QuickEntryService::class.java).setAction(if (ball == null) SHOW_BALL else HIDE_BALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(this, "quick_entry")
            .setSmallIcon(R.drawable.ic_stat_microphone)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.quick_notification_public))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return NotificationCompat.Builder(this, "quick_entry")
            .setSmallIcon(R.drawable.ic_stat_microphone)
            .setContentTitle(title).setContentText(computer)
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.quick_notification_open), open)
            .addAction(0, getString(if (ball == null) R.string.quick_show else R.string.quick_disable), toggle)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HIDE_BALL -> hideBall()
            SHOW_BALL -> showBall()
            STOP -> stopSelf()
            else -> showBall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (active === this) active = null
        handler.removeCallbacksAndMessages(null)
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }; ball = null
        enabled(this, false); super.onDestroy()
    }

    private class BallView(context: Context, private val iconRes: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2F3F3F1.toInt()
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val icon = context.getDrawable(iconRes)
        private val peekWidth = (23 * density).toInt()
        private var peekAtLeft = false
        private var expanded = false

        fun setPeekAtLeft(value: Boolean) {
            peekAtLeft = value
            invalidate()
        }

        fun setExpanded(value: Boolean) {
            expanded = value
            fill.color = 0xF2F3F3F1.toInt()
            icon?.alpha = 255
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawCircle(centerX, centerY, radius, fill)
            canvas.drawCircle(centerX, centerY, radius, edge)
            val iconSize = ((if (expanded) 36f else 32.4f) * density).toInt()
            val iconCenterX = when {
                expanded -> centerX
                peekAtLeft -> width - peekWidth / 2f
                else -> peekWidth / 2f
            }
            icon?.setBounds((iconCenterX - iconSize / 2).toInt(), (centerY - iconSize / 2).toInt(),
                (iconCenterX + iconSize / 2).toInt(), (centerY + iconSize / 2).toInt())
            icon?.draw(canvas)
        }
    }

    companion object {
        private const val STOP = "seeyuer.sayin.STOP_QUICK_ENTRY"
        private const val HIDE_BALL = "seeyuer.sayin.HIDE_QUICK_ENTRY"
        private const val SHOW_BALL = "seeyuer.sayin.SHOW_QUICK_ENTRY"
        private const val NOTIFICATION_ID = 44
        private const val PEEK_ALPHA = 0.5f
        private var active: QuickEntryService? = null

        /** Hide the overlay while the quick card owns the screen; show it again on close. */
        fun setQuickWindowVisible(visible: Boolean) {
            active?.let { service ->
                if (visible) {
                    service.ball?.visibility = View.GONE
                } else if (service.ball != null) {
                    service.collapseToPeek()
                    service.ball?.visibility = View.VISIBLE
                }
            }
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
