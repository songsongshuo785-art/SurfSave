package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.DownloadRequestData
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.SharedPrefHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPrivacySanitizerTest {
    private val gson = Gson()
    private val sanitizer = MigrationPrivacySanitizer(gson)

    @Test
    fun settings_dropCredentialsButPreserveOrdinaryNonUrlStrings() {
        val proxyJson = gson.toJson(
            arrayOf(
                Proxy(
                    host = "proxy.example",
                    port = "8080",
                    user = "alice",
                    password = "secret"
                )
            )
        )
        val sanitized = sanitizer.sanitizeSettingsPreferences(
            listOf(
                PreferenceEntry("GENERATED_CREDENTIALS", "string", stringValue = "secret"),
                PreferenceEntry("USER_PROXY_CHAIN", "string", stringValue = proxyJson),
                PreferenceEntry("DOWNLOAD_FILENAME_TEMPLATE", "string", stringValue = "#title# - #id#"),
                PreferenceEntry(
                    "CUSTOM_DNS_URL",
                    "string",
                    stringValue = "https://dns.example/query?token=secret&mode=wire#private"
                )
            )
        )

        assertFalse(sanitized.any { it.key == "GENERATED_CREDENTIALS" })
        assertEquals(
            "#title# - #id#",
            sanitized.single { it.key == "DOWNLOAD_FILENAME_TEMPLATE" }.stringValue
        )
        assertEquals(
            "https://dns.example/query?mode=wire",
            sanitized.single { it.key == "CUSTOM_DNS_URL" }.stringValue
        )
        val proxies = gson.fromJson(
            sanitized.single { it.key == "USER_PROXY_CHAIN" }.stringValue,
            Array<Proxy>::class.java
        )
        assertEquals("", proxies.single().user)
        assertEquals("", proxies.single().password)
        assertEquals("proxy.example", proxies.single().host)
    }

    @Test
    fun videos_removeRequestSecretsBodiesAndSensitiveUrlParts() {
        val source = VideoInfo(
            id = "video-1",
            downloadUrls = listOf(
                DownloadRequestData(
                    url = "https://user:pass@media.example/video.mp4?id=7&token=secret#fragment",
                    method = "POST",
                    headers = linkedMapOf(
                        "Authorization" to "Bearer secret",
                        "Cookie" to "session=secret",
                        "Set-Cookie" to "leak=secret",
                        "X-Signature" to "signed",
                        "X-Authorization" to "Bearer secret",
                        "api_key" to "secret",
                        "X-ApiKey" to "secret",
                        "Referer" to "https://page.example/watch?id=7&auth=secret#fragment",
                        "User-Agent" to "SurfSave-Test"
                    ),
                    body = "password=secret"
                )
            ),
            thumbnail = "https://img.example/thumb.jpg?signature=secret&size=large",
            originalUrl = "https://page.example/watch?session=secret&api_key=secret&access-key=secret&id=7",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        id = "format-1",
                        url = "https://cdn.example/stream?id=7&x-amz-signature=secret",
                        httpHeaders = mapOf(
                            "Proxy-Authorization" to "Basic secret",
                            "Accept" to "video/*"
                        )
                    )
                )
            )
        )

        val sanitized = sanitizer.sanitizeVideos(listOf(source)).single()
        val request = sanitized.downloadUrls.single()

        assertEquals("https://media.example/video.mp4?id=7", request.url)
        assertNull(request.body)
        assertEquals(setOf("Referer", "User-Agent"), request.headers.keys)
        assertEquals("https://page.example/watch?id=7", request.headers.getValue("Referer"))
        assertEquals("https://img.example/thumb.jpg?size=large", sanitized.thumbnail)
        assertEquals("https://page.example/watch?id=7", sanitized.originalUrl)
        assertEquals(
            "https://cdn.example/stream?id=7",
            sanitized.formats.formats.single().url
        )
        assertEquals(
            mapOf("Accept" to "video/*"),
            sanitized.formats.formats.single().httpHeaders
        )
    }

    @Test
    fun malformedProxyAndSensitivePlaybackPreferences_areDropped() {
        val settings = sanitizer.sanitizeSettingsPreferences(
            listOf(
                PreferenceEntry("USER_PROXY_CHAIN", "string", stringValue = "[null]"),
                PreferenceEntry("ordinary", "string", stringValue = "alpha#beta")
            )
        )
        assertFalse(settings.any { it.key == "USER_PROXY_CHAIN" })
        assertEquals("alpha#beta", settings.single().stringValue)

        val playback = sanitizer.sanitizePlaybackPreferences(
            listOf(
                PreferenceEntry("PLAYBACK_API_KEY", "string", stringValue = "secret"),
                PreferenceEntry(
                    "last_url",
                    "string",
                    stringValue = "https://media.example/watch?id=7&api_key=secret"
                )
            )
        )
        assertFalse(playback.any { it.key == "PLAYBACK_API_KEY" })
        assertEquals("https://media.example/watch?id=7", playback.single().stringValue)
    }

    @Test
    fun cookieContentsRequireExplicitOptInAndSessionDropsLocalThumbnailPath() {
        val profile = CookieProfileStore.CookieProfileBackup(
            id = "profile-1",
            name = "Example",
            domains = listOf("example.com"),
            createdAt = 1,
            updatedAt = 2,
            content = "# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t0\tsession\tsecret\n"
        )
        assertNull(sanitizer.sanitizeCookieProfiles(listOf(profile), false).single().content)
        assertTrue(
            sanitizer.sanitizeCookieProfiles(listOf(profile), true)
                .single()
                .content
                .orEmpty()
                .contains("session")
        )

        val session = BrowserSessionSnapshot(
            tabs = listOf(
                SharedPrefHelper.BrowserSessionTab(
                    id = "tab-1",
                    url = "https://example.com/watch?token=secret&id=7",
                    title = "Example",
                    faviconBase64 = null,
                    thumbnailPath = "C:/private/thumb.jpg"
                )
            ),
            currentIndex = 1
        )
        val sanitizedSession = sanitizer.sanitizeBrowserSession(session)
        assertEquals(1, sanitizedSession.currentIndex)
        assertEquals("https://example.com/watch?id=7", sanitizedSession.tabs.single().url)
        assertNull(sanitizedSession.tabs.single().thumbnailPath)
    }
}
