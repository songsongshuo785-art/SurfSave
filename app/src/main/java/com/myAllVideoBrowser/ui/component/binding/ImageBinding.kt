package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.myAllVideoBrowser.R
import java.util.Locale

object ImageBinding {

    @BindingAdapter(value = ["imageUrl", "imageHeaders"], requireAll = false)
    @JvmStatic
    fun ImageView.loadImage(url: String?, headers: Map<String, String>?) {
        if (url.isNullOrBlank()) {
            setImageResource(R.drawable.noimage_24px)
            return
        }

        val model = if (headers.isNullOrEmpty()) {
            url
        } else {
            val lazyHeaders = LazyHeaders.Builder()
            safeImageHeaders(headers).forEach { (name, value) ->
                lazyHeaders.addHeader(name, value)
            }
            GlideUrl(url, lazyHeaders.build())
        }

        Glide.with(this)
            .load(model)
            .placeholder(R.drawable.noimage_24px)
            .error(R.drawable.noimage_24px)
            .centerCrop()
            .into(this)
    }

    internal fun safeImageHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.mapNotNull { (name, value) ->
            val canonicalName = when (name.lowercase(Locale.US)) {
                "user-agent" -> "User-Agent"
                "referer" -> "Referer"
                "cookie" -> "Cookie"
                else -> null
            } ?: return@mapNotNull null
            val safeValue = value.trim().takeIf {
                it.isNotBlank() && !it.contains('\r') && !it.contains('\n')
            } ?: return@mapNotNull null
            canonicalName to safeValue
        }.toMap(linkedMapOf())
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
