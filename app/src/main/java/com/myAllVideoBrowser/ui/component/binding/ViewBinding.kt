package com.myAllVideoBrowser.ui.component.binding

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

object ViewBinding {

    @JvmStatic
    @BindingAdapter("surfOnClick")
    fun setSurfOnClick(view: View, listener: View.OnClickListener?) {
        view.setOnClickListener(listener)
    }

    @JvmStatic
    @BindingAdapter("surfSrc")
    fun setSurfSrc(imageView: ImageView, drawable: Drawable?) {
        imageView.setImageDrawable(drawable)
    }

    /** MaterialCardView.setStrokeWidth takes px; data-binding dimensions are already px. */
    @JvmStatic
    @BindingAdapter("surfStrokeWidthPx")
    fun setSurfStrokeWidthPx(card: MaterialCardView, px: Float?) {
        card.strokeWidth = px?.roundToInt()?.coerceAtLeast(0) ?: 0
    }
}
