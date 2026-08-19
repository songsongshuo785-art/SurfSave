package com.myAllVideoBrowser.util

/**
 * Display-only filename humanizer. Unlike [FileUtil.FileNameCleaner] (which keeps
 * the name filesystem-safe), this only affects how names are *shown* in lists and
 * the player title — the on-disk filename is never touched.
 */
object DisplayNameFormatter {

    fun clean(raw: String?): String {
        val original = raw.orEmpty().trim()
        if (original.isEmpty()) {
            return ""
        }

        // Strip a trailing extension like ".mp4" (keep the name if stripping empties it)
        val noExt = original.substringBeforeLast('.', original)

        val cleaned = noExt
            .replace(Regex("[._]{2,}"), " ")   // runs of dots/underscores
            .replace(Regex("[\\-_\\[\\](){}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return cleaned.ifBlank { original }
    }
}
