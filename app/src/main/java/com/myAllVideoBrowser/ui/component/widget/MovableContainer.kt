package com.myAllVideoBrowser.ui.component.widget

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.max

class MovableContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private var downRawX = 0f
    private var downRawY = 0f
    private var dX = 0f
    private var dY = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private var positionChangeListener: ((Float, Float) -> Unit)? = null
    private val clickDragTolerance = max(
        ViewConfiguration.get(context).scaledTouchSlop * 2.5f,
        24f
    )

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isDragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        showDraggingFeedback()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                dX = x - downRawX
                dY = y - downRawY
                isDragging = false
                longPressTriggered = false
                removeCallbacks(longPressRunnable)
                showPressedFeedback()
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val moveDX = event.rawX - downRawX
                val moveDY = event.rawY - downRawY
                if (!longPressTriggered && (abs(moveDX) > clickDragTolerance || abs(moveDY) > clickDragTolerance)) {
                    removeCallbacks(longPressRunnable)
                    clearTouchFeedback()
                    return true
                }

                if (!isDragging) {
                    return true
                }

                val bounds = movementBounds() ?: return true
                x = (event.rawX + dX).coerceIn(bounds.minX, bounds.maxX)
                y = (event.rawY + dY).coerceIn(bounds.minY, bounds.maxY)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                val upDX = event.rawX - downRawX
                val upDY = event.rawY - downRawY
                val isClick = abs(upDX) < clickDragTolerance &&
                    abs(upDY) < clickDragTolerance &&
                    !isDragging

                if (isDragging || longPressTriggered) {
                    settleWithinBounds()
                } else {
                    clearTouchFeedback()
                }

                val handled = if (isClick) performClick() else true
                isDragging = false
                longPressTriggered = false
                return handled
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isDragging || longPressTriggered) {
                    settleWithinBounds()
                } else {
                    clearTouchFeedback()
                }
                isDragging = false
                longPressTriggered = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnPositionChangeListener(listener: ((Float, Float) -> Unit)?) {
        positionChangeListener = listener
    }

    fun restorePosition(xRatio: Float, yRatio: Float) {
        post {
            val bounds = movementBounds() ?: return@post
            x = bounds.minX + ((bounds.maxX - bounds.minX) * xRatio.coerceIn(0f, 1f))
            y = bounds.minY + ((bounds.maxY - bounds.minY) * yRatio.coerceIn(0f, 1f))
        }
    }

    private fun showPressedFeedback() {
        animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .alpha(0.88f)
            .setDuration(90)
            .start()
    }

    private fun showDraggingFeedback() {
        animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .alpha(0.95f)
            .setDuration(120)
            .start()
    }

    private fun clearTouchFeedback() {
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(120)
            .start()
    }

    private fun notifyPositionChanged() {
        val bounds = movementBounds() ?: return
        positionChangeListener?.invoke(
            ((x - bounds.minX) / (bounds.maxX - bounds.minX).coerceAtLeast(1f)).coerceIn(0f, 1f),
            ((y - bounds.minY) / (bounds.maxY - bounds.minY).coerceAtLeast(1f)).coerceIn(0f, 1f)
        )
    }

    private fun settleWithinBounds() {
        val bounds = movementBounds()
        if (bounds == null) {
            notifyPositionChanged()
            clearTouchFeedback()
            return
        }

        val targetX = x.coerceIn(bounds.minX, bounds.maxX)
        val targetY = y.coerceIn(bounds.minY, bounds.maxY)

        animate()
            .x(targetX)
            .y(targetY)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                notifyPositionChanged()
            }
            .start()
    }

    private fun movementBounds(): MovementBounds? {
        val viewParent = parent as? View ?: return null
        val layoutParams = layoutParams as? MarginLayoutParams ?: return null
        if (viewParent.width <= 0 || viewParent.height <= 0) {
            return null
        }

        val minX = layoutParams.leftMargin.toFloat()
        val maxX = (viewParent.width - width - layoutParams.rightMargin)
            .coerceAtLeast(layoutParams.leftMargin)
            .toFloat()
        val minY = layoutParams.topMargin.toFloat()
        val maxY = (viewParent.height - height - layoutParams.bottomMargin)
            .coerceAtLeast(layoutParams.topMargin)
            .toFloat()

        return MovementBounds(minX, maxX, minY, maxY)
    }

    private data class MovementBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )
}
