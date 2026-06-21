package com.myAllVideoBrowser.util

import android.content.Context
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object UserFacingError {
    enum class Category {
        NETWORK,
        AUTH_OR_COOKIE,
        NO_FORMAT,
        YTDLP_OUTDATED,
        PROXY_OR_REGION,
        STORAGE,
        DUPLICATE,
        PLAYLIST,
        UNKNOWN
    }

    fun classify(error: Throwable?): Category {
        if (error == null) {
            return Category.UNKNOWN
        }

        return when (error) {
            is UnknownHostException,
            is SocketTimeoutException,
            is SSLException,
            is IOException -> classify(rawMessage(error)).takeIf { it != Category.UNKNOWN }
                ?: Category.NETWORK
            else -> classify(rawMessage(error))
        }
    }

    fun classify(rawMessage: String?): Category {
        val text = rawMessage.orEmpty().lowercase()
        if (text.isBlank()) {
            return Category.UNKNOWN
        }

        return when {
            containsAny(text, "duplicate", "already exists", "already in the download") ->
                Category.DUPLICATE
            containsAny(text, "enospc", "no space", "not enough free space", "permission denied", "eacces", "storage", "error moving file") ->
                Category.STORAGE
            containsAny(text, "401", "403", "unauthorized", "forbidden", "login", "sign in", "cookie", "authenticated") ->
                Category.AUTH_OR_COOKIE
            containsAny(text, "geo", "region", "country", "blocked in", "not available in your") ->
                Category.PROXY_OR_REGION
            containsAny(text, "proxy", "vpn", "socks", "connect tunnel") ->
                Category.PROXY_OR_REGION
            containsAny(text, "yt-dlp", "youtube-dl", "extractor", "unsupported url", "signature extraction", "please update") ->
                Category.YTDLP_OUTDATED
            containsAny(text, "playlist") ->
                Category.PLAYLIST
            containsAny(text, "no video", "no media", "no downloadable", "no formats", "format is not available", "requested format") ->
                Category.NO_FORMAT
            containsAny(text, "timeout", "timed out", "unable to resolve", "unknownhost", "connection", "network", "socket", "ssl", "http 5") ->
                Category.NETWORK
            else -> Category.UNKNOWN
        }
    }

    fun compactMessage(context: Context, error: Throwable?): String {
        return compactMessage(context, rawMessage(error))
    }

    fun compactMessage(context: Context, rawMessage: String?): String {
        val category = classify(rawMessage)
        return context.getString(
            R.string.error_compact_format,
            context.getString(titleRes(category)),
            context.getString(suggestionRes(category))
        )
    }

    fun panelMessage(context: Context, error: Throwable?): String {
        return panelMessage(context, rawMessage(error))
    }

    fun panelMessage(context: Context, rawMessage: String?): String {
        val summary = compactMessage(context, rawMessage)
        val detail = cleanDetail(rawMessage)
        return if (detail.isBlank()) {
            summary
        } else {
            context.getString(R.string.error_explanation_with_detail, summary, detail)
        }
    }

    fun detectionMessage(context: Context, error: Throwable?): String {
        return detectionMessage(context, rawMessage(error))
    }

    fun detectionMessage(context: Context, rawMessage: String?): String {
        return when (classify(rawMessage)) {
            Category.AUTH_OR_COOKIE -> context.getString(R.string.detection_failed_auth)
            Category.NETWORK,
            Category.PROXY_OR_REGION -> context.getString(R.string.detection_failed_network)
            Category.PLAYLIST -> context.getString(R.string.detection_failed_playlist)
            else -> context.getString(R.string.detection_failed_generic)
        }
    }

    fun cleanDetail(rawMessage: String?): String {
        return DownloadTaskLogger.redact(rawMessage.orEmpty())
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
    }

    private fun rawMessage(error: Throwable?): String {
        if (error == null) {
            return ""
        }

        val messages = generateSequence(error) { it.cause }
            .map { throwable ->
                listOfNotNull(
                    throwable::class.java.simpleName,
                    throwable.message
                ).joinToString(": ")
            }
            .filter { it.isNotBlank() }
            .toList()

        return messages.joinToString(" | ")
    }

    private fun titleRes(category: Category): Int {
        return when (category) {
            Category.NETWORK -> R.string.error_network_title
            Category.AUTH_OR_COOKIE -> R.string.error_auth_title
            Category.NO_FORMAT -> R.string.error_no_format_title
            Category.YTDLP_OUTDATED -> R.string.error_ytdlp_title
            Category.PROXY_OR_REGION -> R.string.error_proxy_region_title
            Category.STORAGE -> R.string.error_storage_title
            Category.DUPLICATE -> R.string.error_duplicate_title
            Category.PLAYLIST -> R.string.error_playlist_title
            Category.UNKNOWN -> R.string.error_unknown_title
        }
    }

    private fun suggestionRes(category: Category): Int {
        return when (category) {
            Category.NETWORK -> R.string.error_network_suggestion
            Category.AUTH_OR_COOKIE -> R.string.error_auth_suggestion
            Category.NO_FORMAT -> R.string.error_no_format_suggestion
            Category.YTDLP_OUTDATED -> R.string.error_ytdlp_suggestion
            Category.PROXY_OR_REGION -> R.string.error_proxy_region_suggestion
            Category.STORAGE -> R.string.error_storage_suggestion
            Category.DUPLICATE -> R.string.error_duplicate_suggestion
            Category.PLAYLIST -> R.string.error_playlist_suggestion
            Category.UNKNOWN -> R.string.error_unknown_suggestion
        }
    }

    private fun containsAny(text: String, vararg needles: String): Boolean {
        return needles.any { text.contains(it) }
    }
}
