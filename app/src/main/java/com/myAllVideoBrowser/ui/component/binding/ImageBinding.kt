package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.myAllVideoBrowser.R

object ImageBinding {

    @BindingAdapter("imageUrl")
    @JvmStatic
    fun ImageView.loadImage(url: String?) {
        if (url.isNullOrBlank()) {
            setImageResource(R.drawable.noimage_24px)
            return
        }

        Glide.with(this)
            .load(url)
            .placeholder(R.drawable.noimage_24px)
            .error(R.drawable.noimage_24px)
            .centerCrop()
            .into(this)
    }

    @BindingAdapter("bitmap")
    @JvmStatic
    fun ImageView.setImageBitmap(bitmap: Bitmap?) {
        bitmap?.let { setImageBitmap(it) }
    }

    @BindingAdapter("imageResource")
    @JvmStatic
    fun ImageView.setImageResourceCompat(@DrawableRes resId: Int) {
        if (resId != 0) {
            setImageResource(resId)
        } else {
            setImageDrawable(null)
        }
    }

}
