package com.myAllVideoBrowser.util.telegram

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

object TelegramPostPreviewParser {
    private val cssUrlRegex = Regex(
        """url\(\s*(['\"]?)(.*?)\1\s*\)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(html: String, post: TelegramPostUrl): TelegramPostPreview {
        val document = Jsoup.parse(html, post.embedUrl)
        val channel = document.selectFirst(".tgme_widget_message_author")
            ?.text()
            ?.trim()
            .orEmpty()
        val description = document.selectFirst(".tgme_widget_message_text")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        val items = document.select("a.tgme_widget_message_video_player")
            .mapNotNull { player -> parsePlayer(player, post) }
        val thumbnail = items.firstOrNull { it.thumbnail.isNotBlank() }?.thumbnail.orEmpty()

        return TelegramPostPreview(
            postUrl = post.canonicalUrl,
            channel = channel,
            description = description,
            thumbnail = thumbnail,
            items = items
        )
    }

    private fun parsePlayer(
        player: Element,
        fallbackPost: TelegramPostUrl
    ): TelegramPostPreviewItem? {
        val itemPost = TelegramPostUrl.parse(player.absUrl("href")) ?: fallbackPost
        val mediaUrl = player.selectFirst("video[src]")
            ?.absUrl("src")
            ?.takeIf(::isHttpUrl)
            .orEmpty()
        val thumbnail = player.selectFirst(".tgme_widget_message_video_thumb")
            ?.attr("style")
            ?.let(::extractCssUrl)
            ?.let { resolveUrl(fallbackPost.embedUrl, it) }
            ?.takeIf(::isHttpUrl)
            .orEmpty()
        val durationMs = player.selectFirst("time")
            ?.text()
            ?.let(::parseDurationMs)
            ?: 0L

        val availability = when {
            mediaUrl.isNotBlank() -> TelegramMediaAvailability.PLAYABLE
            thumbnail.isNotBlank() -> TelegramMediaAvailability.POSTER_ONLY
            else -> return null
        }
        return TelegramPostPreviewItem(
            availability = availability,
            originalUrl = itemPost.singleUrl,
            mediaUrl = mediaUrl,
            thumbnail = thumbnail,
            durationMs = durationMs
        )
    }

    private fun extractCssUrl(style: String): String? {
        return cssUrlRegex.find(style)?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String): String {
        return runCatching { URI(baseUrl).resolve(rawUrl).toString() }.getOrDefault(rawUrl)
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }

    private fun parseDurationMs(value: String): Long {
        val parts = value.trim().split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty() || parts.size > 3) return 0L
        val seconds = when (parts.size) {
            3 -> parts[0] * 3_600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> parts[0]
        }
        return seconds.coerceAtLeast(0L) * 1_000L
    }
}
