package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal class MigrationPrivacySanitizer(
    private val gson: Gson = Gson()
) {
    companion object {
        private const val PREF_USER_PROXY_CHAIN = "USER_PROXY_CHAIN"
        private const val PREF_GENERATED_CREDENTIALS = "GENERATED_CREDENTIALS"
        private const val PREF_BROWSER_SESSION_TABS = "BROWSER_SESSION_TABS"
        private const val PREF_BROWSER_SESSION_CURRENT_INDEX = "BROWSER_SESSION_CURRENT_INDEX"

        private val SENSITIVE_HEADER_NAMES = setOf(
            "authorization",
            "authentication",
            "cookie",
            "cookie2",
            "proxy-authorization",
            "set-cookie",
            "set-cookie2",
            "x-api-key",
            "api-key",
            "apikey"
        )
        private val URL_HEADER_NAMES = setOf("referer", "referrer", "location", "content-location")
        private val SENSITIVE_QUERY_NAMES = setOf(
            "access_key",
            "api_key",
            "auth",
            "authorization",
            "client_secret",
            "cookie",
            "credential",
            "expires",
            "jwt",
            "key",
            "password",
            "passwd",
            "policy",
            "secret",
            "session",
            "sig",
            "signature"
        )
    }

    fun sanitizeSettingsPreferences(entries: List<PreferenceEntry>): List<PreferenceEntry> {
        return entries.mapNotNull { entry ->
            when {
                entry.key == PREF_BROWSER_SESSION_TABS ||
                    entry.key == PREF_BROWSER_SESSION_CURRENT_INDEX -> null

                entry.key == PREF_GENERATED_CREDENTIALS -> null
                entry.key == PREF_USER_PROXY_CHAIN -> sanitizeProxyPreference(entry)
                isSensitivePreferenceKey(entry.key) -> null
                else -> sanitizePreferenceValue(entry)
            }
        }
    }

    fun sanitizePlaybackPreferences(entries: List<PreferenceEntry>): List<PreferenceEntry> =
        entries.mapNotNull { entry ->
            if (isSensitivePreferenceKey(entry.key)) null else sanitizePreferenceValue(entry)
        }

    fun sanitizeBookmarks(bookmarks: List<PageInfo>): List<PageInfo> =
        bookmarks.map { bookmark ->
            bookmark.copy(
                link = sanitizeUrl(bookmark.link),
                icon = sanitizeUrl(bookmark.icon)
            )
        }

    fun sanitizeHistory(history: List<HistoryItem>): List<HistoryItem> =
        history.map { item -> item.copy(url = sanitizeUrl(item.url)) }

    fun sanitizeVideos(videos: List<VideoInfo>): List<VideoInfo> =
        videos.map(::sanitizeVideo)

    fun sanitizeProgress(progress: List<ProgressInfo>): List<ProgressInfo> =
        progress.map { item ->
            item.copy(
                videoInfo = sanitizeVideo(item.videoInfo),
                infoLine = "",
                lastError = "",
                logPath = "",
                stopReason = 0,
                executionToken = "",
                removePartialOnCancel = false,
                finalizationSource = "",
                finalizationTarget = ""
            )
        }

    fun sanitizeBrowserSession(session: BrowserSessionSnapshot): BrowserSessionSnapshot =
        session.copy(
            tabs = session.tabs.map { tab ->
                tab.copy(
                    url = sanitizeUrl(tab.url),
                    thumbnailPath = null
                )
            }
        )

    fun sanitizeCookieProfiles(
        profiles: List<CookieProfileStore.CookieProfileBackup>,
        includeContents: Boolean
    ): List<CookieProfileStore.CookieProfileBackup> {
        return profiles.map { profile ->
            profile.copy(
                content = if (includeContents) {
                    profile.content?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            )
        }
    }

    fun sanitizeUrl(value: String): String {
        if (value.isBlank()) {
            return value
        }
        val parsed = value.toHttpUrlOrNull()
        if (parsed != null) {
            val builder = parsed.newBuilder()
                .username("")
                .password("")
                .fragment(null)
            parsed.queryParameterNames
                .filter(::isSensitiveQueryName)
                .forEach(builder::removeAllQueryParameters)
            return builder.build().toString()
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
                .substringBefore('#')
                .substringBefore('?')
                .replace(Regex("^(https?://)[^/@]+@", RegexOption.IGNORE_CASE), "$1")
        }
        return value.substringBefore('#')
    }

    private fun sanitizeVideo(video: VideoInfo): VideoInfo {
        return video.copy(
            downloadUrls = video.downloadUrls.map(::sanitizeRequest),
            thumbnail = sanitizeUrl(video.thumbnail),
            originalUrl = sanitizeUrl(video.originalUrl),
            formats = VideFormatEntityList(video.formats.formats.map(::sanitizeFormat))
        )
    }

    private fun sanitizeRequest(request: DownloadRequestData): DownloadRequestData =
        request.copy(
            url = sanitizeUrl(request.url),
            headers = sanitizeHeaders(request.headers),
            body = null
        )

    private fun sanitizeFormat(format: VideoFormatEntity): VideoFormatEntity =
        format.copy(
            url = format.url?.let(::sanitizeUrl),
            manifestUrl = format.manifestUrl?.let(::sanitizeUrl),
            httpHeaders = format.httpHeaders?.let(::sanitizeHeaders),
            videoOnlyUrl = format.videoOnlyUrl?.let(::sanitizeUrl),
            audioOnlyUrl = format.audioOnlyUrl?.let(::sanitizeUrl)
        )

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.entries
            .filterNot { (name, _) -> isSensitiveHeaderName(name) }
            .associate { (name, value) ->
                name to if (name.lowercase(Locale.US) in URL_HEADER_NAMES) {
                    sanitizeUrl(value)
                } else {
                    value
                }
            }
    }

    private fun sanitizeProxyPreference(entry: PreferenceEntry): PreferenceEntry? {
        val raw = entry.stringValue ?: return null
        val proxies = runCatching { gson.fromJson(raw, Array<Proxy?>::class.java) }
            .getOrNull()
            ?: return null
        if (proxies.any { it == null }) return null
        val sanitized = proxies.map { proxy ->
            requireNotNull(proxy).copy(user = "", password = "")
        }
        return entry.copy(stringValue = gson.toJson(sanitized))
    }

    private fun sanitizePreferenceValue(entry: PreferenceEntry): PreferenceEntry {
        return entry.copy(
            stringValue = entry.stringValue?.let(::sanitizePreferenceString),
            stringSetValue = entry.stringSetValue?.map(::sanitizePreferenceString)?.toSet()
        )
    }

    private fun sanitizePreferenceString(value: String): String {
        return if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            sanitizeUrl(value)
        } else {
            value
        }
    }

    private fun isSensitivePreferenceKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return normalized.contains("password") ||
            normalized.contains("passwd") ||
            normalized.contains("api_key") ||
            normalized.contains("api-key") ||
            normalized.contains("apikey") ||
            normalized.contains("access_key") ||
            normalized.contains("access-key") ||
            normalized.contains("credential") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("authorization") ||
            normalized.contains("cookie") ||
            normalized.endsWith("_header") ||
            normalized.endsWith("_body")
    }

    private fun isSensitiveHeaderName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.US)
        return normalized in SENSITIVE_HEADER_NAMES ||
            normalized.contains("authorization") ||
            normalized.contains("api-key") ||
            normalized.contains("api_key") ||
            normalized.contains("apikey") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("credential") ||
            normalized.contains("signature")
    }

    private fun isSensitiveQueryName(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.US).replace('-', '_')
        return normalized in SENSITIVE_QUERY_NAMES ||
            normalized.contains("token") ||
            normalized.contains("signature") ||
            normalized.contains("credential") ||
            normalized.startsWith("x_amz_") ||
            normalized.startsWith("x_goog_")
    }
}
