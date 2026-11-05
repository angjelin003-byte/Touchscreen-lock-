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
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Foreground Service that locks touchscreen finger input while allowing wired USB-C mouse input:
 * 1. Attaches 4 invisible touch-blocker panels (Top, Bottom, Left, Right) at lower Z-order.
 *    - Totally transparent (no red border or popup when touched).
 *    - Blocks 100% of finger touches (TOOL_TYPE_FINGER).
 *    - When a wired USB-C mouse moves (SOURCE_MOUSE / TOOL_TYPE_MOUSE), dynamically positions
 *      a small pass-through aperture directly under the mouse cursor so mouse clicks, drags,
 *      and scroll wheel events pass straight through to the underlying app.
 * 2. Attaches [edgeButtonView] at higher Z-order on the screen edge so only the edge button
 *    is visible on screen to switch the touch lock ON and OFF.
 */
class TouchLockService : Service() {

    private data class BlockerPanel(
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private lateinit var windowManager: WindowManager
    private lateinit var controller: TouchLockController

    private val blockerPanels = mutableListOf<BlockerPanel>()

    private var edgeButtonView: LinearLayout? = null
    private var edgeIconView: ImageView? = null
    private var edgeStatusDot: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null

    private var mouseCursorX: Int? = null
    private var mouseCursorY: Int? = null
    private var lastMouseLayoutUpdateMs: Long = 0L

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
                mouseCursorX = null
                mouseCursorY = null
                syncOverlayState()
            }
            ACTION_LOCK_NOW -> {
                controller.setLockedState(true)
                mouseCursorX = null
                mouseCursorY = null
                syncOverlayState()
            }
            ACTION_UNLOCK_NOW -> {
                controller.setLockedState(false)
                mouseCursorX = null
                mouseCursorY = null
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

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun attachOverlays() {
        if (blockerPanels.isNotEmpty() || edgeButtonView != null) return

        // 1. Create and attach 4 completely invisible touch-blocker panels FIRST (lower Z-order)
        //    so they block touchscreen fingers while allowing a pass-through aperture for a USB-C mouse.
        repeat(4) {
            val panelView = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }

            val panelParams = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                unlockedBlockerFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val panel = BlockerPanel(panelView, panelParams)
            setupBlockerPanelListeners(panel)
            windowManager.addView(panelView, panelParams)
            blockerPanels.add(panel)
        }

        // 2. Create and attach the Edge Toggle Button SECOND (higher Z-order, always above blocker panels)
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
    private fun setupBlockerPanelListeners(panel: BlockerPanel) {
        // USB-C mouse hover / scroll events update the pass-through aperture around the mouse cursor
        panel.view.setOnHoverListener { _, event ->
            if (controller.isTouchLocked && isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = false)
            }
            false
        }

        panel.view.setOnGenericMotionListener { _, event ->
            if (controller.isTouchLocked && isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = true)
            }
            false
        }

        // Touch / button events:
        // - If from a USB-C mouse, open the aperture at the mouse pointer so clicks/drags reach the app below.
        // - If from a finger (touchscreen), silently consume and block the touch with zero visual flash.
        panel.view.setOnTouchListener { _, event ->
            if (!controller.isTouchLocked) {
                return@setOnTouchListener false
            }

            if (isUsbMouseEvent(event)) {
                val mx = (panel.params.x + event.x).toInt()
                val my = (panel.params.y + event.y).toInt()
                updateMouseAperture(mx, my, force = true)
                return@setOnTouchListener false
            }

            // Finger touch: close any open mouse aperture and consume the touch event silently
            if (mouseCursorX != null || mouseCursorY != null) {
                mouseCursorX = null
                mouseCursorY = null
                applyBlockerPanelsLayout()
            }
            true
        }
    }

    private fun isUsbMouseEvent(event: MotionEvent): Boolean {
        val source = event.source
        if ((source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        ) {
            return true
        }
        for (i in 0 until event.pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                return true
            }
        }
        return false
    }

    private fun updateMouseAperture(mx: Int, my: Int, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        val prevX = mouseCursorX
        val prevY = mouseCursorY

        if (!force && prevX != null && prevY != null) {
            if (abs(mx - prevX) < 3 && abs(my - prevY) < 3) {
                return
            }
            if (now - lastMouseLayoutUpdateMs < 14L) {
                return
            }
        }

        mouseCursorX = mx
        mouseCursorY = my
        lastMouseLayoutUpdateMs = now
        applyBlockerPanelsLayout()
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
            val (screenW, screenH) = getFullScreenSize()

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
                            screenHeightPx = screenH,
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
                        val nowLeft = controller.updateEdgeSide(event.rawX, screenW)
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
        if (outcome == TouchLockController.ToggleOutcome.LOCKED ||
            outcome == TouchLockController.ToggleOutcome.UNLOCKED
        ) {
            mouseCursorX = null
            mouseCursorY = null
            syncOverlayState()
        }
    }

    private fun syncOverlayState() {
        applyBlockerPanelsLayout()
        updateEdgeButtonAppearance()
        setServiceRunningState(true)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun applyBlockerPanelsLayout() {
        if (blockerPanels.size < 4) return

        val locked = controller.isTouchLocked
        if (!locked) {
            for (panel in blockerPanels) {
                panel.params.flags = unlockedBlockerFlags()
                panel.params.width = 1
                panel.params.height = 1
                panel.view.visibility = View.GONE
                windowManager.updateViewLayout(panel.view, panel.params)
            }
            return
        }

        val (screenW, screenH) = getFullScreenSize()
        val layout = controller.computeBlockerLayout(
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            mouseX = mouseCursorX,
            mouseY = mouseCursorY,
            apertureRadiusPx = dpToPx(8).coerceAtLeast(8)
        )

        val rects = listOf(layout.top, layout.bottom, layout.left, layout.right)
        for (i in 0 until 4) {
            val panel = blockerPanels[i]
            val rect = rects[i]
            if (rect.isVisible) {
                panel.params.x = rect.x
                panel.params.y = rect.y
                panel.params.width = rect.width
                panel.params.height = rect.height
                panel.params.flags = lockedBlockerFlags()
                panel.view.visibility = View.VISIBLE
            } else {
                panel.params.x = 0
                panel.params.y = 0
                panel.params.width = 1
                panel.params.height = 1
                panel.params.flags = unlockedBlockerFlags()
                panel.view.visibility = View.GONE
            }
            windowManager.updateViewLayout(panel.view, panel.params)
        }
    }

    private fun getFullScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        }
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

    private fun removeOverlays() {
        for (panel in blockerPanels) {
            runCatching { windowManager.removeView(panel.view) }
        }
        blockerPanels.clear()

        edgeButtonView?.let {
            runCatching { windowManager.removeView(it) }
        }
        edgeButtonView = null
    }

    private fun lockedBlockerFlags(): Int {
        // Intercepts touch events while keeping the blocker completely invisible
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun unlockedBlockerFlags(): Int {
        // Passes 100% of events straight through to underlying apps
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
