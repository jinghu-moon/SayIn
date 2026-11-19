package seeyuer.sayin.quick

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
import seeyuer.sayin.ui.VoiceLinkViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Explicitly enabled optional overlay; idle service never records or sends. */
class QuickEntryService : Service() {
    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var edgeLeft = false
    private var expanded = false
    private var latestState: ClientState? = null
    private var model: VoiceLinkViewModel? = null
    private lateinit var windows: WindowManager
    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private var tapRunnable: Runnable? = null
    private var menu: PopupWindow? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressed = false
    private var dragging = false
    private var moved = false
    private var lastTapAt = 0L
    private val preferences: SharedPreferences by lazy { getSharedPreferences("presentation", 0) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in BALL_KEYS) applyBallSettings()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.deleteNotificationChannel("quick_entry")
        notificationManager.createNotificationChannel(NotificationChannel("quick_entry_visible", getString(R.string.quick_entry_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        model = (application as SayInApplication).model
        val repository = model!!.repository
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
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        showBall()
    }

    private fun showBall() {
        if (ball != null || !Settings.canDrawOverlays(this)) return
        val dp = resources.displayMetrics.density
        val sizeDp = size(this)
        val size = (sizeDp * dp).toInt()
        val p = params ?: WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (300 * dp).toInt()
        }.also { params = it }
        p.width = size
        p.height = size
        p.x = peekX()
        val view = BallView(this, R.drawable.ic_sayin_brand_fyan_mark, sizeDp).apply {
            contentDescription = getString(R.string.quick_open)
            setPeekAtLeft(edgeLeft)
            alpha = peekAlpha(this@QuickEntryService)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = params?.x ?: 0; startY = params?.y ?: 0
                    longPressed = false; dragging = false; moved = false
                    longPress = Runnable {
                        longPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showMenu(v)
                    }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!longPressed) {
                        if (!moved && (abs(dx) > ViewConfiguration.get(this).scaledTouchSlop ||
                                abs(dy) > ViewConfiguration.get(this).scaledTouchSlop)) {
                            moved = true
                            longPress?.let { handler.removeCallbacks(it) }
                            longPress = null
                            dragging = true
                        }
                        if (dragging) {
                            val layout = params ?: return@setOnTouchListener true
                            layout.x = (startX + dx.toInt()).coerceIn(0,
                                resources.displayMetrics.widthPixels - layout.width)
                            layout.y = (startY + dy.toInt()).coerceIn(0,
                                resources.displayMetrics.heightPixels - layout.height)
                            windows.updateViewLayout(v, layout)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress ?: Runnable {})
                    longPress = null
                    if (longPressed) {
                        // The menu is modal; releasing the finger must not open or expand the ball.
                    } else if (dragging) {
                        snapToEdge(if ((params?.x ?: 0) + (params?.width ?: 0) / 2 < resources.displayMetrics.widthPixels / 2) 1 else -1)
                    } else if (!moved) {
                        onTap()
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
            model?.foreground(this, true)
            enabled(this, true)
            updateNotification()
        } catch (_: Exception) {
            enabled(this, false)
            stopSelf()
        }
    }

    /** Single tap expands at the edge; a second tap (or a tap on the expanded ball) opens the card. */
    private fun onTap() {
        ball?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val now = SystemClock.uptimeMillis()
        if (tapRunnable != null && now - lastTapAt <= DOUBLE_TAP_MILLIS) {
            tapRunnable?.let(handler::removeCallbacks)
            tapRunnable = null
            lastTapAt = 0
            openQuick(autoRecord = doubleAction(this) == DOUBLE_RECORD)
            return
        }
        lastTapAt = now
        val action = Runnable {
            tapRunnable = null
            lastTapAt = 0
            if (expanded) openQuick() else expandBall()
        }
        tapRunnable = action
        handler.postDelayed(action, DOUBLE_TAP_MILLIS)
    }

    private fun hideBall() {
        handler.removeCallbacksAndMessages(null)
        tapRunnable = null
        longPress = null
        menu?.dismiss()
        menu = null
        expanded = false
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }
        ball = null
        model?.foreground(this, false)
        enabled(this, false)
        updateNotification()
    }

    private fun updateNotification() {
        val state = latestState ?: return
        notificationManager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun peekWidth(): Int = (size(this) * PEEK_FRACTION * resources.displayMetrics.density).toInt()

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
        ball?.alpha = peekAlpha(this)
        val p = params ?: return
        p.x = peekX()
        windows.updateViewLayout(ball ?: return, p)
    }

    private fun snapToEdge(side: Int) {
        edgeLeft = side > 0
        ball?.setPeekAtLeft(edgeLeft)
        collapseToPeek()
    }

    private fun applyBallSettings() {
        val view = ball ?: return
        val p = params ?: return
        val density = resources.displayMetrics.density
        val sizeDp = size(this)
        val size = (sizeDp * density).toInt()
        view.setMetrics(sizeDp)
        p.width = size
        p.height = size
        p.x = if (expanded) fullX() else peekX()
        if (!expanded) view.alpha = peekAlpha(this)
        windows.updateViewLayout(view, p)
    }

    private fun openQuick(autoRecord: Boolean = false) {
        startActivity(QuickActivity.intent(this, autoRecord))
    }

    private fun showMenu(anchor: View) {
        menu?.dismiss()
        val state = latestState
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF2F3F3F1.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        val popup = PopupWindow(column, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(null)
        }
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) {
            val row = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(if (enabled) 0xFF1A1A1A.toInt() else 0xFF8A8A8A.toInt())
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isEnabled = enabled
                isClickable = enabled
                setOnClickListener {
                    if (!enabled) return@setOnClickListener
                    popup.dismiss()
                    action()
                }
            }
            column.addView(row)
        }
        item(getString(R.string.quick_menu_open)) { openQuick() }
        val networkState = state
        val canControl = networkState?.connection == Connection.ONLINE && networkState.active
        if (networkState?.paused == true) {
            val resumable = canControl && networkState.pauseReason != "outcome_review_required" &&
                !networkState.target.isNullOrBlank()
            item(getString(R.string.quick_menu_resume), resumable) { model?.repository?.resumeReception() }
        } else {
            item(getString(R.string.quick_menu_pause), canControl) { model?.repository?.pauseReception() }
        }
        item(getString(R.string.quick_menu_close)) { stopSelf() }
        menu = popup
        popup.setOnDismissListener { if (menu === popup) menu = null }
        popup.showAsDropDown(anchor, -dp(80), -dp(12))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val expanded = computer + "\n" + getString(R.string.quick_notification_expanded)
        val public = NotificationCompat.Builder(this, "quick_entry_visible")
            .setSmallIcon(R.drawable.ic_stat_sayin)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.quick_notification_public))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return NotificationCompat.Builder(this, "quick_entry_visible")
            .setSmallIcon(R.drawable.ic_stat_sayin)
            .setContentTitle(title).setContentText(computer)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
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
            APPLY -> applyBallSettings()
            STOP -> stopSelf()
            else -> showBall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel()
        if (active === this) active = null
        handler.removeCallbacksAndMessages(null)
        menu?.dismiss(); menu = null
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }; ball = null
        model?.foreground(this, false)
        enabled(this, false); super.onDestroy()
    }

    private class BallView(context: Context, private val iconRes: Int, private var sizeDp: Int) : View(context) {
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
        private val peekWidth get() = (sizeDp * PEEK_FRACTION * density).toInt()
        private var peekAtLeft = false
        private var expanded = false

        fun setMetrics(value: Int) {
            if (value != sizeDp) { sizeDp = value; invalidate() }
        }

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
            val iconSize = ((if (expanded) sizeDp * 0.64f else sizeDp * 0.58f) * density).toInt()
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
        private const val APPLY = "seeyuer.sayin.APPLY_QUICK_ENTRY"
        private const val NOTIFICATION_ID = 44
        private const val PEEK_FRACTION = 0.41f
        private const val DOUBLE_TAP_MILLIS = 240L
        const val DOUBLE_CARD = "card"
        const val DOUBLE_RECORD = "record"
        private val BALL_KEYS = setOf("quick_ball_size", "quick_ball_alpha", "quick_ball_double")
        private var active: QuickEntryService? = null

        fun size(context: Context): Int = context.getSharedPreferences("presentation", 0)
            .getInt("quick_ball_size", 56).coerceIn(44, 72)
        fun peekAlpha(context: Context): Float = context.getSharedPreferences("presentation", 0)
            .getFloat("quick_ball_alpha", 0.5f).coerceIn(0.35f, 1f)
        fun doubleAction(context: Context): String = context.getSharedPreferences("presentation", 0)
            .getString("quick_ball_double", DOUBLE_CARD)?.takeIf { it in setOf(DOUBLE_CARD, DOUBLE_RECORD) } ?: DOUBLE_CARD

        fun setSize(context: Context, value: Int) {
            context.getSharedPreferences("presentation", 0).edit().putInt("quick_ball_size", value.coerceIn(44, 72)).apply()
            refresh(context)
        }
        fun setPeekAlpha(context: Context, value: Float) {
            context.getSharedPreferences("presentation", 0).edit().putFloat("quick_ball_alpha", value.coerceIn(0.35f, 1f)).apply()
            refresh(context)
        }
        fun setDoubleAction(context: Context, value: String) {
            context.getSharedPreferences("presentation", 0).edit().putString("quick_ball_double", value).apply()
        }
        private fun refresh(context: Context) {
            if (active == null) return
            runCatching { context.startService(Intent(context, QuickEntryService::class.java).setAction(APPLY)) }
        }

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
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, QuickEntryService::class.java))
                }
            }
        }
    }
}
