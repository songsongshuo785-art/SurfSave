package com.myAllVideoBrowser.util

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadedMediaValidatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validate_acceptsTinyIsoBaseMediaFile() {
        val file = temporaryFolder.newFile("tiny.mp4")
        file.writeBytes(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
            )
        )

        assertNull(DownloadedMediaValidator.validate(file))
    }

    @Test
    fun validate_acceptsTinyId3AudioFile() {
        val file = temporaryFolder.newFile("tiny.mp3")
        file.writeBytes("ID3tiny-audio".toByteArray(Charsets.US_ASCII))

        assertNull(DownloadedMediaValidator.validate(file))
    }

    @Test
    fun validate_rejectsHtmlAndJsonErrorResponses() {
        val html = temporaryFolder.newFile("blocked.mp4")
        html.writeText("  <!doctype html><html><body>Access denied</body></html>", Charsets.UTF_8)
        val json = temporaryFolder.newFile("error.mp4")
        json.writeText("{\"error\":\"forbidden\"}", Charsets.UTF_8)

        assertTrue(DownloadedMediaValidator.validate(html).orEmpty().contains("error response"))
        assertTrue(DownloadedMediaValidator.validate(json).orEmpty().contains("error response"))
    }

    @Test
    fun validate_rejectsOrdinaryTextAndUnknownReadableBinary() {
        val text = temporaryFolder.newFile("message.mp4")
        text.writeText("This is not media", Charsets.UTF_8)
        val playlist = temporaryFolder.newFile("unfinished.m3u8")
        playlist.writeText("#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts", Charsets.UTF_8)
        val binary = temporaryFolder.newFile("custom.bin")
        binary.writeBytes(byteArrayOf(0x00, 0x81.toByte(), 0x22, 0x93.toByte(), 0x44))

        assertTrue(DownloadedMediaValidator.validate(text).orEmpty().contains("is text"))
        assertTrue(DownloadedMediaValidator.validate(playlist).orEmpty().contains("is text"))
        assertTrue(DownloadedMediaValidator.validate(binary).orEmpty().contains("unsupported"))
    }

    @Test
    fun validate_rejectsTwoByteUnknownBinary() {
        val file = temporaryFolder.newFile("unknown.bin")
        file.writeBytes(byteArrayOf(0x00, 0x81.toByte()))

        assertTrue(DownloadedMediaValidator.validate(file).orEmpty().contains("unsupported"))
    }

    @Test
    fun validate_rejectsSingleTransportStreamPacket() {
        val file = temporaryFolder.newFile("single-packet.ts")
        file.writeBytes(transportStreamPackets(1))

        assertTrue(DownloadedMediaValidator.validate(file).orEmpty().contains("truncated"))
    }

    @Test
    fun validate_rejectsTransportStreamWithBadSecondSyncOrAdaptationControl() {
        val badSync = temporaryFolder.newFile("bad-sync.ts")
        badSync.writeBytes(transportStreamPackets(2).apply { this[188] = 0x46 })
        val badAdaptationControl = temporaryFolder.newFile("bad-afc.ts")
        badAdaptationControl.writeBytes(transportStreamPackets(2).apply { this[188 + 3] = 0x00 })

        assertTrue(DownloadedMediaValidator.validate(badSync).orEmpty().contains("malformed"))
        assertTrue(
            DownloadedMediaValidator.validate(badAdaptationControl).orEmpty().contains("malformed")
        )
    }

    @Test
    fun validate_acceptsTwoWellFormedTransportStreamPackets() {
        val file = temporaryFolder.newFile("two-packets.ts")
        file.writeBytes(transportStreamPackets(2))

        assertNull(DownloadedMediaValidator.validate(file))
    }

    @Test
    fun validate_fileUriUsesSameProbeAndUnknownContentLengthFails() {
        val context = RuntimeEnvironment.getApplication()
        val file = temporaryFolder.newFile("uri.webm")
        file.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))

        assertNull(DownloadedMediaValidator.validate(context, Uri.fromFile(file)))

        val error = DownloadedMediaValidator.validate(
            context,
            Uri.parse("content://missing-validator-provider/media")
        )
        assertTrue(error.orEmpty().contains("size is unavailable"))
    }

    private fun transportStreamPackets(count: Int): ByteArray {
        return ByteArray(188 * count).apply {
            repeat(count) { packetIndex ->
                val offset = packetIndex * 188
                this[offset] = 0x47
                this[offset + 3] = 0x10
            }
        }
    }
}
