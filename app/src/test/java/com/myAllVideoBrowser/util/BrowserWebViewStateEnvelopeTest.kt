package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

class BrowserWebViewStateEnvelopeTest {

    private val tabId = "11111111-2222-3333-4444-555555555555"

    private fun snapshot(
        tab: String = tabId,
        url: String = "https://example.com/c",
        entries: Int = 3,
        index: Int = 2,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4)
    ) = BrowserWebViewStateSnapshot(
        tabId = tab,
        savedUrl = url,
        savedAtMillis = 1_700_000_000_000L,
        webViewPackageVersion = "120.0.6099.230",
        entryCount = entries,
        currentIndex = index,
        payload = payload
    )

    private fun decode(bytes: ByteArray, expectedTabId: String = tabId) =
        BrowserWebViewStateEnvelope.decode(bytes, expectedTabId)

    private fun decoded(bytes: ByteArray, expectedTabId: String = tabId) =
        decode(bytes, expectedTabId) as BrowserWebViewStateDecodeResult.Decoded

    private fun rejected(bytes: ByteArray, expectedTabId: String = tabId) =
        decode(bytes, expectedTabId) as BrowserWebViewStateDecodeResult.Rejected

    @Test
    fun `round trip keeps every field`() {
        val original = snapshot()

        val result = decoded(BrowserWebViewStateEnvelope.encode(original))

        assertEquals(original, result.snapshot)
    }

    @Test
    fun `blank web view package version round trips as null`() {
        val original = snapshot().copy(webViewPackageVersion = null)

        val result = decoded(BrowserWebViewStateEnvelope.encode(original))

        assertEquals(null, result.snapshot.webViewPackageVersion)
        assertEquals(original, result.snapshot)
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())
        bytes[0] = 0

        assertEquals(
            BrowserWebViewStateDecodeFailure.BAD_MAGIC,
            rejected(bytes).failure
        )
    }

    @Test
    fun `unknown envelope version is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())
        // Version sits right after the 4 byte magic.
        bytes[7] = 99

        assertEquals(
            BrowserWebViewStateDecodeFailure.UNSUPPORTED_VERSION,
            rejected(bytes).failure
        )
    }

    @Test
    fun `snapshot of another tab is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot(tab = "other-tab"))

        assertEquals(
            BrowserWebViewStateDecodeFailure.TAB_MISMATCH,
            rejected(bytes).failure
        )
    }

    @Test
    fun `file truncated inside the payload is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())

        assertEquals(
            BrowserWebViewStateDecodeFailure.LENGTH_MISMATCH,
            rejected(bytes.copyOf(bytes.size - 3)).failure
        )
    }

    @Test
    fun `file truncated inside the header is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())

        assertEquals(
            BrowserWebViewStateDecodeFailure.TRUNCATED,
            rejected(bytes.copyOf(10)).failure
        )
    }

    @Test
    fun `tampered payload fails the checksum`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertEquals(
            BrowserWebViewStateDecodeFailure.CHECKSUM_MISMATCH,
            rejected(bytes).failure
        )
    }

    @Test
    fun `trailing garbage is rejected`() {
        val bytes = BrowserWebViewStateEnvelope.encode(snapshot())

        assertEquals(
            BrowserWebViewStateDecodeFailure.LENGTH_MISMATCH,
            rejected(bytes + byteArrayOf(7)).failure
        )
    }

    @Test
    fun `non http saved url is rejected`() {
        val bytes = craft(
            tabId = tabId,
            url = "about:blank",
            entryCount = 3,
            currentIndex = 1,
            payload = byteArrayOf(9)
        )

        assertEquals(
            BrowserWebViewStateDecodeFailure.INVALID_URL,
            rejected(bytes).failure
        )
    }

    @Test
    fun `out of range current index is rejected`() {
        val bytes = craft(
            tabId = tabId,
            url = "https://example.com/a",
            entryCount = 1,
            currentIndex = 5,
            payload = byteArrayOf(9)
        )

        assertEquals(
            BrowserWebViewStateDecodeFailure.INVALID_COUNTS,
            rejected(bytes).failure
        )
    }

    @Test
    fun `oversized text field is rejected without allocating`() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(0x53535753)
            stream.writeInt(BrowserWebViewStateEnvelope.VERSION)
            stream.writeInt(BrowserWebViewStateEnvelope.MAX_TEXT_BYTES + 1)
        }

        assertEquals(
            BrowserWebViewStateDecodeFailure.FIELD_TOO_LARGE,
            rejected(output.toByteArray()).failure
        )
    }

    @Test
    fun `empty byte array is rejected`() {
        assertEquals(
            BrowserWebViewStateDecodeFailure.TRUNCATED,
            rejected(ByteArray(0)).failure
        )
    }

    @Test
    fun `encode refuses unusable snapshots`() {
        val invalid = listOf(
            snapshot(entries = 0, index = 0),
            snapshot(entries = 2, index = 2),
            snapshot(payload = ByteArray(0)),
            snapshot(url = " ")
        )

        invalid.forEach { candidate ->
            val failed = runCatching { BrowserWebViewStateEnvelope.encode(candidate) }.isFailure
            assertTrue("expected encode to refuse $candidate", failed)
        }
    }

    private fun craft(
        tabId: String,
        url: String,
        entryCount: Int,
        currentIndex: Int,
        payload: ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(0x53535753)
            stream.writeInt(BrowserWebViewStateEnvelope.VERSION)
            stream.writeSizedText(tabId)
            stream.writeSizedText(url)
            stream.writeLong(1L)
            stream.writeSizedText("120.0.0.0")
            stream.writeInt(entryCount)
            stream.writeInt(currentIndex)
            stream.writeInt(payload.size)
            val crc = CRC32().apply { update(payload) }
            stream.writeInt(crc.value.toInt())
            stream.write(payload)
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeSizedText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
