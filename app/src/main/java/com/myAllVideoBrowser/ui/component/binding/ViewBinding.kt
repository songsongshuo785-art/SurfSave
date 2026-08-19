package com.myAllVideoBrowser.ui.component.binding

import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.google.android.material.card.MaterialCardView

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

    /** MaterialCardView.setStrokeWidth takes px; this adapter accepts a dp dimension value. */
    @JvmStatic
    @BindingAdapter("surfStrokeWidthDp")
    fun setSurfStrokeWidthDp(card: MaterialCardView, dp: Float?) {
        if (dp == null) {
            return
        }
        card.strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            card.resources.displayMetrics
        ).toInt()
    }
}
