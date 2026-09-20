package com.myAllVideoBrowser.ui.component.widget

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class MovableContainerTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var parent: Viewport
    private lateinit var button: MovableContainer
    private lateinit var toolbar: View
    private var clicks = 0
    private val positions = mutableListOf<Pair<Float, Float>>()

    private class Viewport(context: Context) : FrameLayout(context) {
        val visibleWindow = Rect()
        override fun getWindowVisibleDisplayFrame(outRect: Rect) { outRect.set(visibleWindow) }
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        parent = Viewport(activity.get())
        toolbar = View(activity.get())
        parent.addView(toolbar, FrameLayout.LayoutParams(-1, 56))
        button = MovableContainer(activity.get())
        parent.addView(button, FrameLayout.LayoutParams(48, 48, Gravity.BOTTOM or Gravity.RIGHT).apply {
            setMargins(8, 8, 8, 8)
        })
        button.setTopBoundaryView(toolbar)
        button.setOnClickListener { clicks++ }
        button.setOnPositionChangeListener { side, height -> positions += side to height }
        activity.get().setContentView(parent)
        shadowOf(Looper.getMainLooper()).idle()
        resize(400, 800)
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    private fun resize(width: Int, height: Int) {
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, width, height)
        parent.viewTreeObserver.dispatchOnGlobalLayout()
    }

    private fun event(action: Int, rawX: Float, rawY: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, rawX, rawY, 0)
        // Keep the raw (screen) coordinates stable as the view itself moves under the finger.
        event.offsetLocation(-button.x, -button.y)
        button.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun hold() {
        event(MotionEvent.ACTION_DOWN, button.x + 24, button.y + 24)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
    }

    private fun releaseAt(left: Float, top: Float) {
        event(MotionEvent.ACTION_MOVE, left + 24, top + 24)
        event(MotionEvent.ACTION_UP, left + 24, top + 24)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
    }

    @Test fun shortTapOpensOnceWithoutWritingPosition() {
        event(MotionEvent.ACTION_DOWN, button.x + 24, button.y + 24)
        event(MotionEvent.ACTION_UP, button.x + 24, button.y + 24)
        assertEquals(1, clicks)
        assertTrue(positions.isEmpty())
    }

    @Test fun moveBeforeLongPressThenReturnDoesNotClickOrMove() {
        val x = button.x + 24
        val y = button.y + 24
        event(MotionEvent.ACTION_DOWN, x, y)
        event(MotionEvent.ACTION_MOVE, x - 80, y - 80)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        event(MotionEvent.ACTION_MOVE, x, y)
        event(MotionEvent.ACTION_UP, x, y)
        assertEquals(0, clicks)
        assertTrue(positions.isEmpty())
        assertEquals(parent.width - 56f, button.x, 0.1f)
    }

    @Test fun holdDragsAndSnapsLeftWithoutClicking() {
        hold()
        releaseAt(110f, 240f)
        assertEquals(8f, button.x, 0.1f)
        assertEquals(240f, button.y, 0.1f)
        assertEquals(0, clicks)
        assertEquals(1, positions.size)
        assertEquals(0f, positions.single().first, 0f)
        assertTrue(positions.single().second in 0f..1f)
    }

    @Test fun canDragFromLeftToRightAndClampsBelowToolbar() {
        button.restorePosition(0f, 0.5f)
        hold()
        releaseAt(500f, -200f)
        assertEquals(parent.width - 56f, button.x, 0.1f)
        assertEquals(64f, button.y, 0.1f)
        assertEquals(1f to 0f, positions.single())
        assertEquals(0, clicks)
    }

    @Test fun clampsAtBottomAndLongPressWithoutMovementDoesNotClick() {
        hold()
        releaseAt(300f, 2000f)
        assertEquals(parent.height - 56f, button.y, 0.1f)
        assertEquals(1f, positions.last().second, 0f)
        hold()
        event(MotionEvent.ACTION_UP, button.x + 24, button.y + 24)
        assertEquals(0, clicks)
    }

    @Test fun restoreLegacyPositionAndResizePreservesSideAndHeightRatio() {
        button.restorePosition(0.2f, 0.25f)
        assertEquals(8f, button.x, 0.1f)
        assertEquals(64 + (800 - 56 - 64) * 0.25f, button.y, 0.1f)
        resize(800, 360)
        assertEquals(8f, button.x, 0.1f)
        assertEquals(64 + (360 - 56 - 64) * 0.25f, button.y, 0.1f)
        resize(400, 800)
        assertEquals(234f, button.y, 0.1f)
        assertTrue(positions.isEmpty())
    }

    @Test fun keyboardOcclusionMovesInsideViewportThenRestoresWithoutSaving() {
        button.restorePosition(1f, 0.8f)
        val originalY = button.y
        val location = IntArray(2)
        parent.getLocationOnScreen(location)
        parent.visibleWindow.set(location[0], location[1], location[0] + 400, location[1] + 430)
        parent.viewTreeObserver.dispatchOnGlobalLayout()
        assertTrue(button.y + button.height <= 430 - 8)
        assertEquals(64 + (430 - 56 - 64) * 0.8f, button.y, 0.1f)
        parent.visibleWindow.setEmpty()
        parent.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(originalY, button.y, 0.1f)
        assertTrue(positions.isEmpty())
    }

    @Test fun resizeForKeyboardDoesNotOverwriteRestoredHeight() {
        button.restorePosition(1f, 0.9f)
        val originalY = button.y
        resize(400, 420)
        assertTrue(button.y + 48 <= 412)
        resize(400, 800)
        assertEquals(originalY, button.y, 0.1f)
        assertTrue(positions.isEmpty())
    }

    @Test fun cancellingOrAddingPointerDoesNotClickOrSave() {
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            hold()
            event(MotionEvent.ACTION_MOVE, 120f, 240f)
            event(action, 120f, 240f)
            event(MotionEvent.ACTION_UP, 120f, 240f)
        }
        assertEquals(0, clicks)
        assertTrue(positions.isEmpty())
        assertEquals(parent.width - 56f, button.x, 0.1f)
    }

    @Test fun detachingCancelsPendingLongPressAndResetsTouchFeedback() {
        event(MotionEvent.ACTION_DOWN, button.x + 24, button.y + 24)
        parent.removeView(button)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals(1f, button.scaleX, 0f)
        assertFalse(button.isPressed)
        assertTrue(positions.isEmpty())
        assertEquals(0, clicks)
    }

    @Test fun coordinatorRelayoutAndInterruptedSnapKeepDocking() {
        hold()
        event(MotionEvent.ACTION_MOVE, 130f, 250f)
        event(MotionEvent.ACTION_UP, 130f, 250f)
        // A stationary finger keeps the same screen position when the button finishes docking.
        val rawX = button.x + 24
        val rawY = button.y + 24
        event(MotionEvent.ACTION_DOWN, rawX, rawY)
        event(MotionEvent.ACTION_UP, rawX, rawY)
        assertEquals(8f, button.x, 0.1f)
        resize(400, 800)
        assertEquals(8f, button.x, 0.1f)
        assertEquals(1, positions.size)
        assertEquals(1, clicks)
    }

    @Test fun restoredPositionWorksBeforeFirstLayoutAndOnRecreation() {
        val restored = MovableContainer(activity.get())
        restored.restorePosition(0.8f, 0.35f)
        restored.setTopBoundaryView(toolbar)
        parent.removeView(button)
        parent.addView(restored, FrameLayout.LayoutParams(48, 48, Gravity.BOTTOM or Gravity.RIGHT).apply {
            setMargins(8, 8, 8, 8)
        })
        resize(400, 800)
        assertEquals(344f, restored.x, 0.1f)
        assertEquals(302f, restored.y, 0.1f)
    }
}
