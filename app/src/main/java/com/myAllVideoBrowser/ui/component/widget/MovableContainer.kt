package com.myAllVideoBrowser.ui.component.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A single media entry: tap to open, hold to drag, release to dock at a physical edge. */
class MovableContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var gestureActive = false
    private var tapCancelled = false
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var positionChangeListener: ((Float, Float) -> Unit)? = null
    private var topBoundaryView: View? = null
    private var dockRight = true
    private var heightRatio = 1f
    private var lastBounds: MovementBounds? = null
    private var observedTree: ViewTreeObserver? = null
    private val visibleFrame = Rect()
    private val parentLocation = IntArray(2)
    private val boundaryLocation = IntArray(2)
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshPosition() }

    private val longPressRunnable = Runnable {
        if (gestureActive && !tapCancelled && isEnabled && isShown) {
            isDragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            animateFeedback(1.06f, 0.95f, 120)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
        refreshPosition(force = true)
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        observedTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observedTree = null
        lastBounds = null
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // CoordinatorLayout can change the base left/top even when translation is unchanged.
        refreshPosition(force = true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            cancelGesture()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                applySavedPosition()
                downRawX = event.rawX
                downRawY = event.rawY
                dragOffsetX = x - downRawX
                dragOffsetY = y - downRawY
                gestureActive = true
                tapCancelled = false
                isPressed = true
                animateFeedback(0.96f, 0.88f, 90)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return true
                if (!isDragging) {
                    if (movedBeyondSlop(event)) {
                        // Remains cancelled even if the finger later returns to the start point.
                        tapCancelled = true
                        removeCallbacks(longPressRunnable)
                        isPressed = false
                        animateFeedback(1f, 1f, 120)
                    }
                    return true
                }
                val bounds = movementBounds() ?: return true
                x = (event.rawX + dragOffsetX).coerceIn(bounds.minX, bounds.maxX)
                y = (event.rawY + dragOffsetY).coerceIn(bounds.minY, bounds.maxY)
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return true
                val shouldClick = !isDragging && !tapCancelled && !movedBeyondSlop(event)
                val shouldDock = isDragging
                endTouch()
                if (shouldDock) dockAtNearestEdge() else animateFeedback(1f, 1f, 120)
                if (shouldClick) performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                applySavedPosition()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnPositionChangeListener(listener: ((Float, Float) -> Unit)?) {
        positionChangeListener = listener
    }

    fun setTopBoundaryView(view: View?) {
        topBoundaryView = view
        refreshPosition(force = true)
    }

    fun restorePosition(xRatio: Float, yRatio: Float) {
        // Existing free-position preferences remain compatible: map x to the nearest edge.
        dockRight = !xRatio.isFinite() || xRatio >= 0.5f
        heightRatio = if (yRatio.isFinite()) yRatio.coerceIn(0f, 1f) else 1f
        cancelGesture()
        refreshPosition(force = true)
    }

    private fun movedBeyondSlop(event: MotionEvent): Boolean =
        abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop

    private fun endTouch() {
        removeCallbacks(longPressRunnable)
        parent?.requestDisallowInterceptTouchEvent(false)
        gestureActive = false
        isDragging = false
        isPressed = false
    }

    private fun cancelGesture() {
        endTouch()
        tapCancelled = true
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    private fun animateFeedback(scale: Float, opacity: Float, duration: Long) {
        animate().cancel()
        animate().scaleX(scale).scaleY(scale).alpha(opacity)
            .setDuration(animationDuration(duration)).start()
    }

    private fun animationDuration(duration: Long): Long =
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) 0 else duration

    private fun dockAtNearestEdge() {
        val bounds = movementBounds() ?: return
        dockRight = x >= (bounds.minX + bounds.maxX) / 2f
        val targetY = y.coerceIn(bounds.minY, bounds.maxY)
        if (bounds.maxY > bounds.minY) {
            heightRatio = (targetY - bounds.minY) / (bounds.maxY - bounds.minY)
        }
        // Persist the intended destination now, even if another touch interrupts the animation.
        positionChangeListener?.invoke(if (dockRight) 1f else 0f, heightRatio)
        animate().cancel()
        animate().x(if (dockRight) bounds.maxX else bounds.minX).y(targetY)
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(animationDuration(180)).start()
    }

    private fun refreshPosition(force: Boolean = false) {
        val bounds = movementBounds() ?: return
        val boundsChanged = bounds != lastBounds
        lastBounds = bounds
        if (boundsChanged) cancelGesture()
        if ((boundsChanged || force) && !gestureActive) applySavedPosition(bounds)
    }

    private fun applySavedPosition(bounds: MovementBounds? = movementBounds()) {
        bounds ?: return
        animate().cancel()
        x = if (dockRight) bounds.maxX else bounds.minX
        y = bounds.minY + (bounds.maxY - bounds.minY) * heightRatio
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    private fun movementBounds(): MovementBounds? {
        val container = parent as? View ?: return null
        val margins = layoutParams as? MarginLayoutParams ?: return null
        if (width <= 0 || height <= 0 || container.width <= 0 || container.height <= 0) return null

        var left = container.paddingLeft
        var right = container.width - container.paddingRight
        var top = container.paddingTop
        var bottom = container.height - container.paddingBottom
        container.getLocationOnScreen(parentLocation)
        container.getWindowVisibleDisplayFrame(visibleFrame)
        // Intersect in screen coordinates: avoids double-counting system bars / adjustResize,
        // and also handles a keyboard which overlays or pans an otherwise unchanged parent.
        if (!visibleFrame.isEmpty) {
            left = max(left, visibleFrame.left - parentLocation[0])
            right = min(right, visibleFrame.right - parentLocation[0])
            top = max(top, visibleFrame.top - parentLocation[1])
            bottom = min(bottom, visibleFrame.bottom - parentLocation[1])
        }
        topBoundaryView?.takeIf { it.isShown && it.height > 0 }?.let { toolbar ->
            toolbar.getLocationOnScreen(boundaryLocation)
            top = max(top, boundaryLocation[1] + toolbar.height - parentLocation[1])
        }
        val minX = (left + margins.leftMargin).toFloat()
        val maxX = (right - width - margins.rightMargin).toFloat().coerceAtLeast(minX)
        val minY = (top + margins.topMargin).toFloat()
        val maxY = (bottom - height - margins.bottomMargin).toFloat().coerceAtLeast(minY)
        return MovementBounds(minX, maxX, minY, maxY)
    }

    private data class MovementBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )
}
