package com.myAllVideoBrowser.util.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramPostUrlTest {
    @Test
    fun parsesSupportedPublicPostForms() {
        val direct = TelegramPostUrl.parse("https://t.me/cctv1/17551")
        val legacy = TelegramPostUrl.parse("http://telegram.me/cctv1/17551?foo=bar")
        val reader = TelegramPostUrl.parse("https://t.me/s/cctv1/17551#post")

        listOf(direct, legacy, reader).forEach { parsed ->
            requireNotNull(parsed)
            assertEquals("cctv1", parsed.channel)
            assertEquals("17551", parsed.messageId)
            assertEquals("https://t.me/cctv1/17551", parsed.canonicalUrl)
            assertEquals("https://t.me/cctv1/17551?single=1", parsed.singleUrl)
        }
    }

    @Test
    fun rejectsPrivateInviteChannelAndInvalidPostUrls() {
        listOf(
            "https://t.me/c/123456/99",
            "https://t.me/+abcdef",
            "https://t.me/cctv1",
            "https://t.me/s/cctv1",
            "https://t.me/cctv1/not-a-number",
            "https://example.com/cctv1/17551",
            "tg://resolve?domain=cctv1"
        ).forEach { value ->
            assertNull(value, TelegramPostUrl.parse(value))
        }
    }
}
