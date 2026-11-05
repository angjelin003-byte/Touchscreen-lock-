package com.touchlock.edge

/**
 * Pure controller logic for managing the screen touch lock state,
 * single/double-tap unlock guard, and edge button docking coordinates.
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

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 650L
    }
}
