package com.myAllVideoBrowser.util

import okhttp3.Response

/**
 * Total size of the resource behind a probe response.
 *
 * A `Range: bytes=0-0` probe answers with `206 Partial Content` and a one-byte
 * body, so [Response.body]'s content length only describes the partial slice.
 * Prefer the total advertised by `Content-Range` so callers record the real
 * resource size instead of `1` byte. Plain `200` responses have no
 * `Content-Range`, so the body/`Content-Length` fallbacks keep working.
 */
internal fun Response.contentLengthOrUnknown(): Long {
    header("Content-Range")
        ?.substringAfterLast("/", "")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { return it }

    body.contentLength().takeIf { it > 0L }?.let { return it }

    header("Content-Length")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { return it }

    return 0L
}
