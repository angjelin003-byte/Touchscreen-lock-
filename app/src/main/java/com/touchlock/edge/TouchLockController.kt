package com.touchlock.edge

/**
 * Pure controller logic for managing the screen touch lock state,
 * single/double-tap unlock guard, edge button docking coordinates,
 * overlay editor appearance settings (size, opacity, corner radius, status dot),
 * and 4-panel touch-blocker geometry that blocks finger touch while leaving
 * a stable, flicker-free pass-through aperture under a wired USB-C mouse cursor.
 */
class TouchLockController(
    initialLocked: Boolean = false,
    var requireDoubleTapToUnlock: Boolean = false,
    var dockedLeftEdge: Boolean = false,
    var yOffsetPx: Int = 420,
    var iconSizeDp: Int = DEFAULT_ICON_SIZE_DP,
    var overlayOpacityPercent: Int = DEFAULT_OPACITY_PERCENT,
    var cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    var showStatusDot: Boolean = DEFAULT_SHOW_STATUS_DOT,
    var mouseApertureDp: Int = DEFAULT_MOUSE_APERTURE_DP
) {
    enum class ToggleOutcome {
        LOCKED,
        UNLOCKED,
        AWAITING_SECOND_TAP
    }

    data class PanelRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        val isVisible: Boolean
            get() = width > 0 && height > 0
    }

    data class BlockerOverlayLayout(
        val top: PanelRect,
        val bottom: PanelRect,
        val left: PanelRect,
        val right: PanelRect
    )

    data class OverlayStyleMetrics(
        val iconSizeDp: Int,
        val horizontalPaddingDp: Int,
        val verticalPaddingDp: Int,
        val cornerRadiusDp: Int,
        val alpha: Float,
        val showStatusDot: Boolean
    )

    var isTouchLocked: Boolean = initialLocked
        private set

    private var lastUnlockAttemptMs: Long = 0L

    fun onEdgeButtonClick(nowMs: Long): ToggleOutcome {
        if (!isTouchLocked) {
            isTouchLocked = true
            lastUnlockAttemptMs = 0L
            return ToggleOutcome.LOCKED
        }

        if (requireDoubleTapToUnlock) {
            val delta = nowMs - lastUnlockAttemptMs
            if (delta in 1..DOUBLE_TAP_WINDOW_MS) {
                isTouchLocked = false
                lastUnlockAttemptMs = 0L
                return ToggleOutcome.UNLOCKED
            } else {
                lastUnlockAttemptMs = nowMs
                return ToggleOutcome.AWAITING_SECOND_TAP
            }
        }

        isTouchLocked = false
        lastUnlockAttemptMs = 0L
        return ToggleOutcome.UNLOCKED
    }

    fun setLockedState(locked: Boolean) {
        isTouchLocked = locked
        lastUnlockAttemptMs = 0L
    }

    fun updateVerticalPosition(rawY: Int, screenHeightPx: Int, buttonHeightPx: Int): Int {
        val minY = 64
        val maxY = (screenHeightPx - buttonHeightPx - 64).coerceAtLeast(minY)
        yOffsetPx = rawY.coerceIn(minY, maxY)
        return yOffsetPx
    }

    fun updateEdgeSide(rawTouchX: Float, screenWidthPx: Int): Boolean {
        if (screenWidthPx > 0) {
            dockedLeftEdge = rawTouchX < (screenWidthPx / 2f)
        }
        return dockedLeftEdge
    }

    fun computeOverlayStyle(): OverlayStyleMetrics {
        val clampedIconDp = iconSizeDp.coerceIn(MIN_ICON_SIZE_DP, MAX_ICON_SIZE_DP)
        val clampedOpacity = overlayOpacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
        val clampedCornerDp = cornerRadiusDp.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
        val hPadDp = (clampedIconDp * 0.42f).toInt().coerceAtLeast(5)
        val vPadDp = (clampedIconDp * 0.50f).toInt().coerceAtLeast(6)

        return OverlayStyleMetrics(
            iconSizeDp = clampedIconDp,
            horizontalPaddingDp = hPadDp,
            verticalPaddingDp = vPadDp,
            cornerRadiusDp = clampedCornerDp,
            alpha = clampedOpacity / 100f,
            showStatusDot = showStatusDot
        )
    }

    /**
     * Computes 4 non-overlapping touch-blocking rectangles covering the screen
     * except for a generous pass-through aperture around the USB-C mouse cursor (mouseX, mouseY),
     * optionally biased by (leadX, leadY) in the direction of mouse movement so the cursor
     * stays well inside the open zone without hover-exit flicker or dropped clicks.
     */
    fun computeBlockerLayout(
        screenWidthPx: Int,
        screenHeightPx: Int,
        mouseX: Int?,
        mouseY: Int?,
        apertureRadiusPx: Int = DEFAULT_MOUSE_APERTURE_RADIUS_PX,
        leadX: Int = 0,
        leadY: Int = 0
    ): BlockerOverlayLayout {
        val w = screenWidthPx.coerceAtLeast(1)
        val h = screenHeightPx.coerceAtLeast(1)

        if (mouseX == null || mouseY == null || apertureRadiusPx <= 0) {
            return BlockerOverlayLayout(
                top = PanelRect(0, 0, w, h),
                bottom = PanelRect(0, 0, 0, 0),
                left = PanelRect(0, 0, 0, 0),
                right = PanelRect(0, 0, 0, 0)
            )
        }

        val maxLead = (apertureRadiusPx / 2).coerceAtLeast(0)
        val cx = (mouseX + leadX.coerceIn(-maxLead, maxLead)).coerceIn(0, w)
        val cy = (mouseY + leadY.coerceIn(-maxLead, maxLead)).coerceIn(0, h)

        val holeLeft = (cx - apertureRadiusPx).coerceIn(0, w)
        val holeRight = (cx + apertureRadiusPx).coerceIn(0, w)
        val holeTop = (cy - apertureRadiusPx).coerceIn(0, h)
        val holeBottom = (cy + apertureRadiusPx).coerceIn(0, h)
        val middleHeight = (holeBottom - holeTop).coerceAtLeast(0)

        return BlockerOverlayLayout(
            top = PanelRect(x = 0, y = 0, width = w, height = holeTop),
            bottom = PanelRect(x = 0, y = holeBottom, width = w, height = (h - holeBottom).coerceAtLeast(0)),
            left = PanelRect(x = 0, y = holeTop, width = holeLeft, height = middleHeight),
            right = PanelRect(x = holeRight, y = holeTop, width = (w - holeRight).coerceAtLeast(0), height = middleHeight)
        )
    }

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 650L
        const val DEFAULT_MOUSE_APERTURE_RADIUS_PX = 96

        const val MIN_ICON_SIZE_DP = 10
        const val MAX_ICON_SIZE_DP = 28
        const val DEFAULT_ICON_SIZE_DP = 14

        const val MIN_OPACITY_PERCENT = 15
        const val MAX_OPACITY_PERCENT = 100
        const val DEFAULT_OPACITY_PERCENT = 45

        const val MIN_CORNER_RADIUS_DP = 4
        const val MAX_CORNER_RADIUS_DP = 24
        const val DEFAULT_CORNER_RADIUS_DP = 12

        const val DEFAULT_SHOW_STATUS_DOT = false

        const val MIN_MOUSE_APERTURE_DP = 24
        const val MAX_MOUSE_APERTURE_DP = 80
        const val DEFAULT_MOUSE_APERTURE_DP = 44
    }
}
