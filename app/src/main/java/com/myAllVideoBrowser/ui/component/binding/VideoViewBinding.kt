package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import android.widget.VideoView
import java.io.File

object VideoViewBinding {

    @BindingAdapter("videoURI")
    @JvmStatic
    fun VideoView.setVideoURI(videoPath: String?) {
        videoPath?.let { path ->
            val uri = if (path.startsWith("http")) {
                path.toUri()
            } else {
                FileProvider.getUriForFile(context, context.packageName + ".provider", File(path))
            }
            setVideoURI(uri)
        }
    }
}
