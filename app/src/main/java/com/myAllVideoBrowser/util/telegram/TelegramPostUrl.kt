package com.myAllVideoBrowser.util.telegram

import java.net.URI
import java.util.Locale

class TelegramPostUrl private constructor(
    val channel: String,
    val messageId: String
) {
    val canonicalUrl: String
        get() = "https://t.me/$channel/$messageId"

    val singleUrl: String
        get() = "$canonicalUrl?single=1"

    val embedUrl: String
        get() = "$canonicalUrl?embed=1&single=1"

    companion object {
        private val publicChannelRegex = Regex("^[A-Za-z0-9_]+$")
        private val supportedHosts = setOf("t.me", "telegram.me")

        fun parse(rawUrl: String?): TelegramPostUrl? {
            val value = rawUrl?.trim().orEmpty()
            if (value.isBlank()) return null

            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true) &&
                !uri.scheme.equals("https", ignoreCase = true)
            ) {
                return null
            }

            val host = uri.host
                ?.lowercase(Locale.US)
                ?.removePrefix("www.")
                ?: return null
            if (host !in supportedHosts) return null

            val segments = uri.path.orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
            val (channel, messageId) = when {
                segments.size == 2 -> segments[0] to segments[1]
                segments.size == 3 && segments[0].equals("s", ignoreCase = true) ->
                    segments[1] to segments[2]
                else -> return null
            }

            if (channel.equals("c", ignoreCase = true) ||
                channel.startsWith('+') ||
                !publicChannelRegex.matches(channel) ||
                messageId.any { !it.isDigit() }
            ) {
                return null
            }

            return TelegramPostUrl(channel, messageId)
        }
    }
}
