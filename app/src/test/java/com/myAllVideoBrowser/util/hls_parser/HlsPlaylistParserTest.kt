package com.myAllVideoBrowser.util.hls_parser

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HlsPlaylistParserTest {

    @Test
    fun mediaPlaylist_preservesKeyRotationExplicitIvAndNonZeroSequence() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:43
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key-a.bin"
            #EXTINF:6.0,
            chunks/../segment-43.ts?token=one
            #EXT-X-KEY:METHOD=AES-128,URI="/keys/key-b.bin",IV=0x000000000000000000000000000000ff
            #EXTINF:6.0,
            /media/segment-44.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            ../plain/segment-45.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylistParser.parse(
            playlist,
            "https://example.com/live/variant/index.m3u8"
        ) as HlsPlaylistParser.MediaPlaylist
        val segments = parsed.segments.map { it as HlsPlaylistParser.UrlMediaSegment }

        assertEquals(43L, parsed.mediaSequence)
        assertEquals(listOf(43L, 44L, 45L), segments.map { it.mediaSequence })
        assertEquals("https://example.com/live/variant/segment-43.ts?token=one", segments[0].url)
        assertEquals("https://example.com/live/keys/key-a.bin", segments[0].encryptionKey?.uri)
        assertEquals("https://example.com/keys/key-b.bin", segments[1].encryptionKey?.uri)
        assertEquals(
            "0x000000000000000000000000000000ff",
            segments[1].encryptionKey?.iv?.lowercase()
        )
        assertNull(segments[2].encryptionKey)
        assertEquals("https://example.com/live/plain/segment-45.ts", segments[2].url)
        assertTrue(parsed.hasEndList)
    }

    @Test
    fun masterPlaylist_resolvesRelativeAndNetworkPathReferencesWithUriSemantics() {
        val relativePlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            ../media/./video.m3u8?quality=360
        """.trimIndent()
        val networkPathPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
            //cdn.example.net/live/video.m3u8
        """.trimIndent()

        val relative = HlsPlaylistParser.parse(
            relativePlaylist,
            "https://example.com/root/master/index.m3u8?old=1"
        ) as HlsPlaylistParser.MasterPlaylist
        val networkPath = HlsPlaylistParser.parse(
            networkPathPlaylist,
            "https://example.com/root/master/index.m3u8"
        ) as HlsPlaylistParser.MasterPlaylist

        assertEquals(
            "https://example.com/root/media/video.m3u8?quality=360",
            relative.variants.single().url
        )
        assertEquals(
            "https://cdn.example.net/live/video.m3u8",
            networkPath.variants.single().url
        )
    }
}
