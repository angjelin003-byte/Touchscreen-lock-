package com.touchlock.edge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
 * Foreground Service that locks touchscreen finger input while allowing wired USB-C mouse input,
 * and renders a compact, translucent edge lock button whose size, transparency, corner radius,
 * status dot, and edge side update in real time from the in-app Overlay Editor.
 */
class TouchLockService : Service() {

    private data class BlockerPanel(
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var controller: TouchLockController

    private val blockerPanels = mutableListOf<BlockerPanel>()

    private var edgeButtonView: LinearLayout? = null
    private var edgeIconView: ImageView? = null
    private var edgeStatusDot: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null

    private var mouseCursorX: Int? = null
    private var mouseCursorY: Int? = null
    private var mouseLeadX: Int = 0
    private var mouseLeadY: Int = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == KEY_SERVICE_RUNNING || key == KEY_IS_LOCKED) return@OnSharedPreferenceChangeListener
        loadConfigFromPrefs(sharedPrefs)
        applyEdgeButtonLayout()
        if (controller.isTouchLocked) {
            applyBlockerPanelsLayout()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        controller = TouchLockController(
            initialLocked = false,
            yOffsetPx = prefs.getInt(KEY_Y_OFFSET_PX, dpToPx(220))
        )
        loadConfigFromPrefs(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

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

        loadConfigFromPrefs(prefs)
        applyEdgeButtonLayout()

        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> {
                controller.setLockedState(!controller.isTouchLocked)
                resetMouseApertureState()
                syncOverlayState()
            }
            ACTION_LOCK_NOW -> {
                controller.setLockedState(true)
                resetMouseApertureState()
                syncOverlayState()
            }
            ACTION_UNLOCK_NOW -> {
                controller.setLockedState(false)
                resetMouseApertureState()
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
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        removeOverlays()
        setServiceRunningState(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadConfigFromPrefs(sharedPrefs: SharedPreferences) {
        controller.requireDoubleTapToUnlock = sharedPrefs.getBoolean(KEY_DOUBLE_TAP_UNLOCK, false)
        controller.dockedLeftEdge = sharedPrefs.getBoolean(KEY_DOCKED_LEFT, false)
        controller.iconSizeDp = sharedPrefs.getInt(
            KEY_ICON_SIZE_DP,
            TouchLockController.DEFAULT_ICON_SIZE_DP
        )
        controller.overlayOpacityPercent = sharedPrefs.getInt(
            KEY_OPACITY_PERCENT,
            TouchLockController.DEFAULT_OPACITY_PERCENT
        )
        controller.cornerRadiusDp = sharedPrefs.getInt(
            KEY_CORNER_RADIUS_DP,
            TouchLockController.DEFAULT_CORNER_RADIUS_DP
        )
        controller.showStatusDot = sharedPrefs.getBoolean(
            KEY_SHOW_STATUS_DOT,
            TouchLockController.DEFAULT_SHOW_STATUS_DOT
        )
        controller.mouseApertureDp = sharedPrefs.getInt(
            KEY_MOUSE_APERTURE_DP,
            TouchLockController.DEFAULT_MOUSE_APERTURE_DP
        )
    }

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

        // 2. Create and attach the compact, translucent Edge Toggle Button SECOND (higher Z-order)
        val style = controller.computeOverlayStyle()
        val edgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dpToPx(style.horizontalPaddingDp),
                dpToPx(style.verticalPaddingDp),
                dpToPx(style.horizontalPaddingDp),
                dpToPx(style.verticalPaddingDp)
            )
            alpha = style.alpha
            elevation = dpToPx(4).toFloat()
        }

        val iconPx = dpToPx(style.iconSizeDp)
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock_open)
            contentDescription = getString(R.string.edge_button_content_desc)
        }
        edgeContainer.addView(iconView, LinearLayout.LayoutParams(iconPx, iconPx))

        val statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34C759"))
            }
            visibility = if (style.showStatusDot) View.VISIBLE else View.GONE
        }
        val dotParams = LinearLayout.LayoutParams(dpToPx(4), dpToPx(4)).apply {
            topMargin = dpToPx(4)
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
        // IMPORTANT:
        // 1. Only process ACTION_HOVER_ENTER and ACTION_HOVER_MOVE. Never react to ACTION_HOVER_EXIT,
        //    which Android synthesizes when the blocker panel moves out from under the cursor.
        // 2. Always use event.rawX and event.rawY (absolute screen coordinates) so layout updates
        //    never offset subsequent event coordinates.
        // 3. Return true when handled so the event does not fall through to onGenericMotionListener.
        panel.view.setOnHoverListener { _, event ->
            if (!controller.isTouchLocked || !isUsbMouseEvent(event)) {
                return@setOnHoverListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    updateMouseApertureFromRaw(event.rawX.toInt(), event.rawY.toInt())
                    true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // Ignore synthetic hover exit when the panel moves away to expose the app below
                    true
                }
                else -> false
            }
        }

        panel.view.setOnGenericMotionListener { _, event ->
            if (!controller.isTouchLocked || !isUsbMouseEvent(event)) {
                return@setOnGenericMotionListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL,
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    updateMouseApertureFromRaw(event.rawX.toInt(), event.rawY.toInt())
                    false
                }
                else -> false
            }
        }

        // Touch / button events:
        // - If from a USB-C mouse, open the aperture at rawX/rawY so clicks reach the app below.
        // - If from a finger (touchscreen), close the aperture and consume the touch silently.
        panel.view.setOnTouchListener { _, event ->
            if (!controller.isTouchLocked) {
                return@setOnTouchListener false
            }

            if (isUsbMouseEvent(event)) {
                updateMouseApertureFromRaw(event.rawX.toInt(), event.rawY.toInt())
                return@setOnTouchListener false
            }

            // Finger touch: close any open mouse aperture and consume the touch event silently
            if (mouseCursorX != null || mouseCursorY != null) {
                resetMouseApertureState()
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

    private fun updateMouseApertureFromRaw(rawX: Int, rawY: Int) {
        val prevX = mouseCursorX
        val prevY = mouseCursorY

        if (prevX != null && prevY != null) {
            val dx = rawX - prevX
            val dy = rawY - prevY
            if (abs(dx) < 2 && abs(dy) < 2) {
                return
            }
            mouseLeadX = dx
            mouseLeadY = dy
        } else {
            mouseLeadX = 0
            mouseLeadY = 0
        }

        mouseCursorX = rawX
        mouseCursorY = rawY
        applyBlockerPanelsLayout()
    }

    private fun resetMouseApertureState() {
        mouseCursorX = null
        mouseCursorY = null
        mouseLeadX = 0
        mouseLeadY = 0
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
                            buttonHeightPx = view.height.coerceAtLeast(dpToPx(32))
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
            resetMouseApertureState()
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
        val radiusPx = dpToPx(
            controller.mouseApertureDp.coerceIn(
                TouchLockController.MIN_MOUSE_APERTURE_DP,
                TouchLockController.MAX_MOUSE_APERTURE_DP
            )
        ).coerceAtLeast(48)

        val layout = controller.computeBlockerLayout(
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            mouseX = mouseCursorX,
            mouseY = mouseCursorY,
            apertureRadiusPx = radiusPx,
            leadX = mouseLeadX,
            leadY = mouseLeadY
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
        val style = controller.computeOverlayStyle()

        edge.setPadding(
            dpToPx(style.horizontalPaddingDp),
            dpToPx(style.verticalPaddingDp),
            dpToPx(style.horizontalPaddingDp),
            dpToPx(style.verticalPaddingDp)
        )
        edge.alpha = style.alpha

        val iconPx = dpToPx(style.iconSizeDp)
        edgeIconView?.let { icon ->
            val lp = icon.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(iconPx, iconPx)
            lp.width = iconPx
            lp.height = iconPx
            icon.layoutParams = lp
            icon.setImageResource(
                if (locked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
            )
        }

        val r = dpToPx(style.cornerRadiusDp).toFloat()
        val radii = if (onLeft) {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        } else {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }

        val bgDrawable = GradientDrawable().apply {
            cornerRadii = radii
            if (locked) {
                setColor(Color.parseColor("#CC1C1C1E"))
                setStroke(dpToPx(1), Color.parseColor("#FF453A"))
            } else {
                setColor(Color.parseColor("#B31C1C1E"))
                setStroke(dpToPx(1), Color.parseColor("#4DFFFFFF"))
            }
        }
        edge.background = bgDrawable

        edgeStatusDot?.let { dot ->
            dot.visibility = if (style.showStatusDot) View.VISIBLE else View.GONE
            val dotDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (locked) Color.parseColor("#FF453A") else Color.parseColor("#34C759"))
            }
            dot.background = dotDrawable
        }
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
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun unlockedBlockerFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun persistEdgePreferences() {
        prefs.edit()
            .putBoolean(KEY_DOCKED_LEFT, controller.dockedLeftEdge)
            .putInt(KEY_Y_OFFSET_PX, controller.yOffsetPx)
            .apply()
    }

    private fun setServiceRunningState(running: Boolean) {
        prefs.edit()
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

        const val KEY_ICON_SIZE_DP = "icon_size_dp"
        const val KEY_OPACITY_PERCENT = "opacity_percent"
        const val KEY_CORNER_RADIUS_DP = "corner_radius_dp"
        const val KEY_SHOW_STATUS_DOT = "show_status_dot"
        const val KEY_MOUSE_APERTURE_DP = "mouse_aperture_dp"

        const val ACTION_TOGGLE_LOCK = "com.touchlock.edge.ACTION_TOGGLE_LOCK"
        const val ACTION_LOCK_NOW = "com.touchlock.edge.ACTION_LOCK_NOW"
        const val ACTION_UNLOCK_NOW = "com.touchlock.edge.ACTION_UNLOCK_NOW"
        const val ACTION_STOP_SERVICE = "com.touchlock.edge.ACTION_STOP_SERVICE"
    }
}
