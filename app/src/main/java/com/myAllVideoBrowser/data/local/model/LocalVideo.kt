package com.myAllVideoBrowser.data.local.model

import android.net.Uri
import com.myAllVideoBrowser.util.DisplayNameFormatter
import java.net.URI
import java.util.Locale

data class LocalVideo(
    var id: Long,
    var uri: Uri,
    var name: String
) {

    var size: String = ""
    var mimeType: String = ""
    var quality: String = ""
    var sourceUrl: String = ""
    /** Source-provided cover URL, if the completed download still has one. */
    var originalThumbnailUrl: String = ""
    var thumbnailFrameMicros: Long = 1_000_000L
    var sortTimeMillis: Long = 0L
    /** Raw byte size; non-positive means the provider could not report it. */
    var sizeBytes: Long = -1L
    /** Media duration in milliseconds; non-positive means unknown. */
    var durationMillis: Long = 0L

    /** Humanized display name (extension/separator cleanup); raw name untouched */
    val displayName: String
        get() = DisplayNameFormatter.clean(name).ifBlank { name }

    val thumbnailPath: Uri
        get() = uri

    val usableOriginalThumbnailUrl: String?
        get() = originalThumbnailUrl.trim().takeIf { it.isNotBlank() }

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

    val isImage: Boolean
        get() = mimeType.startsWith("image/", ignoreCase = true) ||
            imageExtensions.contains(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/", ignoreCase = true) ||
            audioExtensions.contains(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

    companion object {
        private val imageExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif", "bmp", "svg"
        )
        private val audioExtensions = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "amr"
        )
    }

}
