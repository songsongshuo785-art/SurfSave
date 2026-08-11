package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger

private const val MAX_YT_DLP_ERROR_LENGTH = 2_000

internal fun sanitizeYoutubeDlError(raw: String, fallback: String): String {
    return DownloadTaskLogger.redact(raw.replace(Regex("WARNING:.+\\n"), ""))
        .take(MAX_YT_DLP_ERROR_LENGTH)
        .ifBlank { fallback }
}
