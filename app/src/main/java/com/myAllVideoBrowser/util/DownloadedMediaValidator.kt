package com.myAllVideoBrowser.util

import java.io.File
import java.util.Locale

object DownloadedMediaValidator {
    private const val MIN_REASONABLE_MEDIA_BYTES = 32 * 1024L

    fun validate(file: File, isLive: Boolean = false): String? {
        if (!file.exists()) {
            return "Downloaded file is missing"
        }

        if (file.length() <= 0L) {
            return "Downloaded file is empty"
        }

        if (!isLive && file.length() < MIN_REASONABLE_MEDIA_BYTES) {
            return "Downloaded file is too small; it may be an ad or error page"
        }

        val header = readHeader(file).lowercase(Locale.ROOT)
        if (looksLikeWebPage(header)) {
            return "Downloaded content looks like a web page, not a media file"
        }

        return null
    }

    private fun readHeader(file: File): String {
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(1024)
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun looksLikeWebPage(header: String): Boolean {
        return header.contains("<!doctype html") ||
            header.contains("<html") ||
            header.contains("<head") ||
            header.contains("access denied") ||
            header.contains("cloudflare") ||
            header.contains("captcha")
    }
}
