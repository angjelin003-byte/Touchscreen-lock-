package com.touchlock.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLockControllerTest {

    @Test
    fun singleTapTogglesLockOnAndOff() {
        val controller = TouchLockController(
            initialLocked = false,
            requireDoubleTapToUnlock = false
        )

        assertFalse(controller.isTouchLocked)

        val firstTap = controller.onEdgeButtonClick(1000L)
        assertEquals(TouchLockController.ToggleOutcome.LOCKED, firstTap)
        assertTrue(controller.isTouchLocked)

        val secondTap = controller.onEdgeButtonClick(2000L)
        assertEquals(TouchLockController.ToggleOutcome.UNLOCKED, secondTap)
        assertFalse(controller.isTouchLocked)
    }

    @Test
    fun doubleTapUnlockRequiresSecondTapWithinWindow() {
        val controller = TouchLockController(
            initialLocked = true,
            requireDoubleTapToUnlock = true
        )

        val firstUnlockTap = controller.onEdgeButtonClick(1000L)
        assertEquals(TouchLockController.ToggleOutcome.AWAITING_SECOND_TAP, firstUnlockTap)
        assertTrue(controller.isTouchLocked)

        // Tap too late (> 650ms): still locked and resets window
        val lateTap = controller.onEdgeButtonClick(2000L)
        assertEquals(TouchLockController.ToggleOutcome.AWAITING_SECOND_TAP, lateTap)
        assertTrue(controller.isTouchLocked)

        // Tap within 650ms: unlocks
        val quickSecondTap = controller.onEdgeButtonClick(2350L)
        assertEquals(TouchLockController.ToggleOutcome.UNLOCKED, quickSecondTap)
        assertFalse(controller.isTouchLocked)
    }

    @Test
    fun edgePositionClampsAndSnapsCorrectly() {
        val controller = TouchLockController()

        val clampedLow = controller.updateVerticalPosition(rawY = -50, screenHeightPx = 2400, buttonHeightPx = 140)
        assertEquals(64, clampedLow)

        val clampedHigh = controller.updateVerticalPosition(rawY = 3000, screenHeightPx = 2400, buttonHeightPx = 140)
        assertEquals(2400 - 140 - 64, clampedHigh)

        assertTrue(controller.updateEdgeSide(rawTouchX = 120f, screenWidthPx = 1080))
        assertFalse(controller.updateEdgeSide(rawTouchX = 880f, screenWidthPx = 1080))
    }

    @Test
    fun blockerLayoutCoversFullScreenWhenNoMouseOrCreatesApertureForUsbMouse() {
        val controller = TouchLockController()

        val fullLayout = controller.computeBlockerLayout(1080, 2400, null, null, 10)
        assertTrue(fullLayout.top.isVisible)
        assertEquals(1080, fullLayout.top.width)
        assertEquals(2400, fullLayout.top.height)
        assertFalse(fullLayout.bottom.isVisible)
        assertFalse(fullLayout.left.isVisible)
        assertFalse(fullLayout.right.isVisible)

        val mouseLayout = controller.computeBlockerLayout(1080, 2400, 500, 1000, 10)
        assertEquals(990, mouseLayout.top.height)
        assertEquals(1010, mouseLayout.bottom.y)
        assertEquals(1390, mouseLayout.bottom.height)
        assertEquals(490, mouseLayout.left.width)
        assertEquals(20, mouseLayout.left.height)
        assertEquals(510, mouseLayout.right.x)
        assertEquals(570, mouseLayout.right.width)
    }
}
