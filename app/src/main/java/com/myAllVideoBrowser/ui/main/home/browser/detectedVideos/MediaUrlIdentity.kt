package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import java.net.URI
import java.util.Locale

/**
 * Resource identity for media URLs.
 *
 * Only the parts that HTTP defines as case-insensitive are normalized: the scheme
 * and the host. The path and the query values keep their original casing and their
 * percent-encoding, because `https://cdn.example/A.jpg` and
 * `https://cdn.example/a.jpg` can be two different files, and so can
 * `?id=ABC123` and `?id=abc123`.
 *
 * The scheme and an explicit `www.` prefix are deliberately kept, so two URLs are
 * only treated as the same resource when they really are the same resource.
 * Keeping a duplicate is safer than silently dropping a distinct image.
 *
 * Only the query keys that carry per-request signatures are removed, and the keys
 * are compared case-insensitively while the surviving query text is left untouched.
 */
internal object MediaUrlIdentity {
    private val TEMPORARY_QUERY_KEYS = setOf(
        "x-amz-signature",
        "x-amz-credential",
        "x-amz-date",
        "x-amz-expires",
        "x-amz-security-token",
        "signature",
        "sig",
        "token",
        "expires",
        "expire",
        "e",
        "st",
        "se",
        "sp",
        "sv",
        "hash",
        "key",
        "auth",
        "policy",
        "range"
    )

    fun of(rawUrl: String?): String {
        val value = rawUrl?.trim().orEmpty()
        if (value.isBlank()) {
            return ""
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return loose(value)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            return loose(value)
        }

        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val stableQuery = uri.rawQuery
            ?.split("&")
            ?.filterNot(::isTemporaryQueryPart)
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()

        val base = "$scheme://$host$port$path"
        return if (stableQuery.isBlank()) base else "$base?$stableQuery"
    }

    private fun isTemporaryQueryPart(queryPart: String): Boolean {
        val key = queryPart.substringBefore("=").lowercase(Locale.US)
        return key in TEMPORARY_QUERY_KEYS ||
            key.startsWith("utm_") ||
            key.contains("token") ||
            key.contains("signature") ||
            key.contains("expires") ||
            key.contains("expire")
    }

    /** Best-effort identity for URLs that cannot be parsed or have no host. */
    private fun loose(value: String): String {
        return value.substringBefore("#").trimEnd('/')
    }
}
