package com.myAllVideoBrowser.util

import java.util.Locale

/**
 * Display-only filename humanizer. Unlike [FileUtil.FileNameCleaner] (which keeps
 * the name filesystem-safe), this only affects how names are *shown* in lists and
 * the player title — the on-disk filename is never touched.
 */
object DisplayNameFormatter {

    private val mediaExtensions = setOf(
        "3g2", "3gp", "aac", "avi", "flac", "flv", "m4a", "m4s", "m4v", "mkv",
        "mov", "mp3", "mp4", "mpeg", "mpg", "oga", "ogg", "ogv", "opus", "ts", "wav",
        "webm", "wmv"
    )

    fun clean(raw: String?): String {
        val original = raw.orEmpty().trim()
        if (original.isEmpty()) {
            return ""
        }

        // Strip one or more known media extensions, including yt-dlp's occasional .mp4.mp4.
        var noExt = original
        while (true) {
            val dot = noExt.lastIndexOf('.')
            if (dot <= 0 || noExt.substring(dot + 1).lowercase(Locale.ROOT) !in mediaExtensions) {
                break
            }
            noExt = noExt.substring(0, dot)
        }

        val cleaned = noExt
            .replace(Regex("[._]{2,}"), " ")   // runs of dots/underscores
            .replace(Regex("[\\-_\\[\\](){}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return cleaned.ifBlank { original }
    }
}
