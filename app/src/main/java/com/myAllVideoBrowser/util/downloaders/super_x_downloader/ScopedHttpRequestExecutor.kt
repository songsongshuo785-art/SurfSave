package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.myAllVideoBrowser.util.MediaRequestHeaderPolicy
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Executes GET requests while re-evaluating credential headers at every redirect hop. */
internal object ScopedHttpRequestExecutor {
    private const val MAX_REDIRECTS = 10
    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

    @Throws(IOException::class)
    fun execute(
        client: OkHttpClient,
        targetUrl: String,
        headers: Map<String, String>,
        credentialOriginUrl: String,
        onCallCreated: (Call) -> Unit = {},
        configure: Request.Builder.() -> Unit = {}
    ): Response {
        val redirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var currentUrl = targetUrl

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(currentUrl)
                .headers(
                    MediaRequestHeaderPolicy.forTarget(
                        headers,
                        credentialOriginUrl,
                        currentUrl
                    ).toHeaders()
                )
                .apply(configure)
                .build()
            val call = redirectClient.newCall(request)
            onCallCreated(call)
            val response = call.execute()
            if (response.code !in REDIRECT_CODES) return response

            val nextUrl = response.header("Location")?.let(response.request.url::resolve)
            if (nextUrl == null) return response
            response.close()
            if (redirectCount == MAX_REDIRECTS) {
                throw IOException("Too many redirects while downloading media resource.")
            }
            currentUrl = nextUrl.toString()
        }
        throw IOException("Too many redirects while downloading media resource.")
    }
}
