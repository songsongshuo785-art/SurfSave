package com.myAllVideoBrowser.data.local.model

import android.net.Uri
import java.net.URI

data class LocalVideo(
    var id: Long,
    var uri: Uri,
    var name: String
) {

    var size: String = ""
    var quality: String = ""
    var sourceUrl: String = ""
    var thumbnailFrameMicros: Long = 1_000_000L

    val thumbnailPath: Uri
        get() = uri

    val hasQuality: Boolean
        get() = quality.isNotBlank()

    val hasSourceUrl: Boolean
        get() = sourceUrl.isNotBlank()

    val sourceHost: String
        get() = try {
            URI(sourceUrl).host?.removePrefix("www.").orEmpty()
        } catch (_: Throwable) {
            ""
        }

    val sourceLabel: String
        get() = sourceHost.ifBlank { sourceUrl }

    val hasSource: Boolean
        get() = sourceLabel.isNotBlank()

}
