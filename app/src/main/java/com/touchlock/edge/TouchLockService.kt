package com.touchlock.edge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Foreground Service that attaches two WindowManager overlay surfaces:
 * 1. [touchBlockerView] (added first, lower Z-order): Full-screen surface that intercepts
 *    and consumes 100% of screen touch input when locked, and becomes untouchable/hidden when unlocked.
 * 2. [edgeButtonView] (added second, higher Z-order): Compact button docked to the screen edge
 *    that always remains interactive on top of the blocker so the user can toggle the touch lock on/off.
 */
class TouchLockService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controller: TouchLockController
    private val mainHandler = Handler(Looper.getMainLooper())

    private var touchBlockerView: FrameLayout? = null
    private var blockerBorderView: View? = null
    private var blockerHintText: TextView? = null
    private var blockerParams: WindowManager.LayoutParams? = null

    private var edgeButtonView: LinearLayout? = null
    private var edgeIconView: ImageView? = null
    private var edgeStatusDot: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null

    private val hideBorderRunnable = Runnable {
        blockerBorderView?.alpha = 0f
        blockerHintText?.alpha = 0f
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        controller = TouchLockController(
            initialLocked = false,
            requireDoubleTapToUnlock = prefs.getBoolean(KEY_DOUBLE_TAP_UNLOCK, false),
            dockedLeftEdge = prefs.getBoolean(KEY_DOCKED_LEFT, false),
            yOffsetPx = prefs.getInt(KEY_Y_OFFSET_PX, dpToPx(220))
        )

        createNotificationChannel()
        startServiceForeground()

        if (Settings.canDrawOverlays(this)) {
            attachOverlays()
            setServiceRunningState(true)
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            setServiceRunningState(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        controller.requireDoubleTapToUnlock = prefs.getBoolean(KEY_DOUBLE_TAP_UNLOCK, false)
        val prefLeft = prefs.getBoolean(KEY_DOCKED_LEFT, controller.dockedLeftEdge)
        if (prefLeft != controller.dockedLeftEdge) {
            controller.dockedLeftEdge = prefLeft
            applyEdgeButtonLayout()
        }

        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> {
                controller.setLockedState(!controller.isTouchLocked)
                syncOverlayState()
            }
            ACTION_LOCK_NOW -> {
                controller.setLockedState(true)
                syncOverlayState()
            }
            ACTION_UNLOCK_NOW -> {
                controller.setLockedState(false)
                syncOverlayState()
            }
            ACTION_STOP_SERVICE -> {
                controller.setLockedState(false)
                setServiceRunningState(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                syncOverlayState()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideBorderRunnable)
        removeOverlays()
        setServiceRunningState(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServiceForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlays() {
        if (touchBlockerView != null || edgeButtonView != null) return

        // 1. Create and attach the full-screen Touch Blocker FIRST (lower Z-order)
        val blockerLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        val borderDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#18EF4444"))
            setStroke(dpToPx(3), Color.parseColor("#CCEF4444"))
        }

        val borderIndicator = View(this).apply {
            background = borderDrawable
            alpha = 0f
        }
        blockerLayout.addView(
            borderIndicator,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val hintLabel = TextView(this).apply {
            text = getString(R.string.overlay_locked_touch_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC111827"))
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#66EF4444"))
            }
            alpha = 0f
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(48)
        }
        blockerLayout.addView(hintLabel, hintParams)

        // Consume all touches when the blocker overlay is active
        blockerLayout.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                flashBlockedTouchFeedback(getString(R.string.overlay_locked_touch_hint))
            }
            true
        }

        val bParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            unlockedBlockerFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(blockerLayout, bParams)
        touchBlockerView = blockerLayout
        blockerBorderView = borderIndicator
        blockerHintText = hintLabel
        blockerParams = bParams

        // 2. Create and attach the Edge Toggle Button SECOND (higher Z-order, always above blocker)
        val edgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(12), dpToPx(10), dpToPx(12))
            elevation = dpToPx(8).toFloat()
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock_open)
            contentDescription = getString(R.string.edge_button_content_desc)
        }
        edgeContainer.addView(iconView, LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)))

        val statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        val dotParams = LinearLayout.LayoutParams(dpToPx(6), dpToPx(6)).apply {
            topMargin = dpToPx(6)
        }
        edgeContainer.addView(statusDot, dotParams)

        val eParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (controller.dockedLeftEdge) Gravity.START else Gravity.END
            x = 0
            y = controller.yOffsetPx
        }

        setupEdgeButtonTouchListener(edgeContainer)
        windowManager.addView(edgeContainer, eParams)

        edgeButtonView = edgeContainer
        edgeIconView = iconView
        edgeStatusDot = statusDot
        edgeParams = eParams

        syncOverlayState()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEdgeButtonTouchListener(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var initialY = 0
        var isDragging = false
        val touchSlopPx = dpToPx(10)

        view.setOnTouchListener { _, event ->
            val params = edgeParams ?: return@setOnTouchListener false
            val metrics = resources.displayMetrics

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val updatedY = controller.updateVerticalPosition(
                            rawY = (initialY + dy).toInt(),
                            screenHeightPx = metrics.heightPixels,
                            buttonHeightPx = view.height.coerceAtLeast(dpToPx(52))
                        )
                        params.y = updatedY
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val wasLeft = controller.dockedLeftEdge
                        val nowLeft = controller.updateEdgeSide(event.rawX, metrics.widthPixels)
                        persistEdgePreferences()
                        if (wasLeft != nowLeft) {
                            applyEdgeButtonLayout()
                        }
                    } else {
                        handleEdgeButtonTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleEdgeButtonTap() {
        val outcome = controller.onEdgeButtonClick(SystemClock.elapsedRealtime())
        when (outcome) {
            TouchLockController.ToggleOutcome.LOCKED -> {
                syncOverlayState()
                flashBlockedTouchFeedback(getString(R.string.overlay_locked_activated))
            }
            TouchLockController.ToggleOutcome.UNLOCKED -> {
                syncOverlayState()
            }
            TouchLockController.ToggleOutcome.AWAITING_SECOND_TAP -> {
                flashBlockedTouchFeedback(getString(R.string.overlay_double_tap_prompt))
            }
        }
    }

    private fun syncOverlayState() {
        val locked = controller.isTouchLocked
        val blocker = touchBlockerView
        val bParams = blockerParams

        if (blocker != null && bParams != null) {
            if (locked) {
                bParams.flags = lockedBlockerFlags()
                blocker.visibility = View.VISIBLE
            } else {
                bParams.flags = unlockedBlockerFlags()
                blocker.visibility = View.GONE
                blockerBorderView?.alpha = 0f
                blockerHintText?.alpha = 0f
            }
            windowManager.updateViewLayout(blocker, bParams)
        }

        updateEdgeButtonAppearance()
        setServiceRunningState(true)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun applyEdgeButtonLayout() {
        val edge = edgeButtonView ?: return
        val params = edgeParams ?: return
        params.gravity = Gravity.TOP or if (controller.dockedLeftEdge) Gravity.START else Gravity.END
        params.x = 0
        params.y = controller.yOffsetPx
        updateEdgeButtonAppearance()
        windowManager.updateViewLayout(edge, params)
    }

    private fun updateEdgeButtonAppearance() {
        val edge = edgeButtonView ?: return
        val locked = controller.isTouchLocked
        val onLeft = controller.dockedLeftEdge
        val r = dpToPx(16).toFloat()

        // Round the inner screen-facing corners while keeping the outer screen-edge flush
        val radii = if (onLeft) {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        } else {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }

        val bgDrawable = GradientDrawable().apply {
            cornerRadii = radii
            if (locked) {
                setColor(Color.parseColor("#E6111827"))
                setStroke(dpToPx(2), Color.parseColor("#EF4444"))
            } else {
                setColor(Color.parseColor("#CC1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#10B981"))
            }
        }
        edge.background = bgDrawable

        edgeIconView?.setImageResource(
            if (locked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (locked) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
        }
        edgeStatusDot?.background = dotDrawable
    }

    private fun flashBlockedTouchFeedback(message: String) {
        mainHandler.removeCallbacks(hideBorderRunnable)
        blockerHintText?.text = message
        blockerBorderView?.alpha = 1f
        blockerHintText?.alpha = 1f
        mainHandler.postDelayed(hideBorderRunnable, 950L)
    }

    private fun removeOverlays() {
        touchBlockerView?.let {
            runCatching { windowManager.removeView(it) }
        }
        edgeButtonView?.let {
            runCatching { windowManager.removeView(it) }
        }
        touchBlockerView = null
        edgeButtonView = null
    }

    private fun lockedBlockerFlags(): Int {
        // Intercepts and blocks all touch events across the entire screen
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun unlockedBlockerFlags(): Int {
        // Passes 100% of touch events straight through to underlying apps
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun persistEdgePreferences() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DOCKED_LEFT, controller.dockedLeftEdge)
            .putInt(KEY_Y_OFFSET_PX, controller.yOffsetPx)
            .apply()
    }

    private fun setServiceRunningState(running: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .putBoolean(KEY_IS_LOCKED, running && controller.isTouchLocked)
            .apply()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, TouchLockService::class.java).apply {
            action = ACTION_TOGGLE_LOCK
        }
        val togglePending = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TouchLockService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val locked = controller.isTouchLocked
        val title = if (locked) {
            getString(R.string.notif_title_locked)
        } else {
            getString(R.string.notif_title_unlocked)
        }
        val body = if (locked) {
            getString(R.string.notif_body_locked)
        } else {
            getString(R.string.notif_body_unlocked)
        }
        val toggleLabel = if (locked) {
            getString(R.string.action_unlock_touch)
        } else {
            getString(R.string.action_lock_touch)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (locked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, getString(R.string.action_stop_overlay), stopPending)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        const val CHANNEL_ID = "touch_lock_edge_channel"
        const val NOTIFICATION_ID = 4010

        const val PREFS_NAME = "touch_lock_prefs"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_IS_LOCKED = "is_locked"
        const val KEY_DOCKED_LEFT = "docked_left"
        const val KEY_Y_OFFSET_PX = "y_offset_px"
        const val KEY_DOUBLE_TAP_UNLOCK = "double_tap_unlock"

        const val ACTION_TOGGLE_LOCK = "com.touchlock.edge.ACTION_TOGGLE_LOCK"
        const val ACTION_LOCK_NOW = "com.touchlock.edge.ACTION_LOCK_NOW"
        const val ACTION_UNLOCK_NOW = "com.touchlock.edge.ACTION_UNLOCK_NOW"
        const val ACTION_STOP_SERVICE = "com.touchlock.edge.ACTION_STOP_SERVICE"
    }
}
