package com.touchlock.edge

/**
 * Pure controller logic for managing the screen touch lock state,
 * single/double-tap unlock guard, edge button docking coordinates,
 * and 4-panel touch-blocker geometry that blocks finger touch while leaving
 * a pass-through aperture under a wired USB-C mouse cursor.
 */
class TouchLockController(
    initialLocked: Boolean = false,
    var requireDoubleTapToUnlock: Boolean = false,
    var dockedLeftEdge: Boolean = false,
    var yOffsetPx: Int = 420
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

    /**
     * Computes 4 non-overlapping touch-blocking rectangles covering the entire screen
     * except for a small pass-through aperture centered at the USB-C mouse cursor (mouseX, mouseY).
     * When mouseX or mouseY is null (e.g., no mouse active or finger touched screen), the top panel
     * covers 100% of the screen.
     */
    fun computeBlockerLayout(
        screenWidthPx: Int,
        screenHeightPx: Int,
        mouseX: Int?,
        mouseY: Int?,
        apertureRadiusPx: Int = DEFAULT_MOUSE_APERTURE_RADIUS_PX
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

        val mx = mouseX.coerceIn(0, w)
        val my = mouseY.coerceIn(0, h)

        val holeLeft = (mx - apertureRadiusPx).coerceIn(0, w)
        val holeRight = (mx + apertureRadiusPx).coerceIn(0, w)
        val holeTop = (my - apertureRadiusPx).coerceIn(0, h)
        val holeBottom = (my + apertureRadiusPx).coerceIn(0, h)
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
        const val DEFAULT_MOUSE_APERTURE_RADIUS_PX = 10
    }
}
