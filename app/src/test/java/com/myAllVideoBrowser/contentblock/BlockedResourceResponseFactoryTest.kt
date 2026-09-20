package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedResourceResponseFactoryTest {
    @Test
    fun executableAndVisualResourcesReceiveMatchingNeutralPayloads() {
        val script = BlockedResourceResponseFactory.payloadFor(BrowserResourceType.SCRIPT)
        val stylesheet = BlockedResourceResponseFactory.payloadFor(BrowserResourceType.STYLESHEET)
        val image = BlockedResourceResponseFactory.payloadFor(BrowserResourceType.IMAGE)
        val subdocument =
            BlockedResourceResponseFactory.payloadFor(BrowserResourceType.SUBDOCUMENT)

        assertEquals("application/javascript", script.mimeType)
        assertEquals(200, script.statusCode)
        assertTrue(script.body.toString(Charsets.UTF_8).contains("blocked by SurfSave"))
        assertEquals("text/css", stylesheet.mimeType)
        assertEquals(200, stylesheet.statusCode)
        assertEquals("image/svg+xml", image.mimeType)
        assertEquals(200, image.statusCode)
        assertTrue(image.body.toString(Charsets.UTF_8).contains("width=\"1\""))
        assertEquals("text/html", subdocument.mimeType)
        assertEquals(200, subdocument.statusCode)
    }

    @Test
    fun dataAndBinaryBlocksUseNoContentInsteadOfFakeTextSuccess() {
        val noContentTypes = listOf(
            BrowserResourceType.FONT,
            BrowserResourceType.MEDIA,
            BrowserResourceType.XML_HTTP_REQUEST,
            BrowserResourceType.WEBSOCKET,
            BrowserResourceType.PING,
            BrowserResourceType.OTHER,
            BrowserResourceType.UNKNOWN
        )

        noContentTypes.forEach { type ->
            val payload = BlockedResourceResponseFactory.payloadFor(type)
            assertEquals(type.name, 204, payload.statusCode)
            assertEquals(type.name, "No Content", payload.reasonPhrase)
            assertTrue(type.name, payload.body.isEmpty())
        }
    }
}
