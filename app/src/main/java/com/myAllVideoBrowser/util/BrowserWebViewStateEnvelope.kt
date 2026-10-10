package com.myAllVideoBrowser.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.zip.CRC32

/**
 * One tab's persisted WebView navigation state.
 *
 * [payload] is an opaque, version-fragile blob produced by [WebViewStateCodec] (a Parcel
 * serialization of the output of `WebView.saveState()`). Everything outside [payload] uses a
 * stable, self-describing layout, so a snapshot written by one app version can be recognised,
 * validated or safely discarded by another one.
 */
data class BrowserWebViewStateSnapshot(
    val tabId: String,
    val savedUrl: String,
    val savedAtMillis: Long,
    val webViewPackageVersion: String?,
    val entryCount: Int,
    val currentIndex: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BrowserWebViewStateSnapshot) return false
        return tabId == other.tabId &&
            savedUrl == other.savedUrl &&
            savedAtMillis == other.savedAtMillis &&
            webViewPackageVersion == other.webViewPackageVersion &&
            entryCount == other.entryCount &&
            currentIndex == other.currentIndex &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = tabId.hashCode()
        result = 31 * result + savedUrl.hashCode()
        result = 31 * result + savedAtMillis.hashCode()
        result = 31 * result + (webViewPackageVersion?.hashCode() ?: 0)
        result = 31 * result + entryCount
        result = 31 * result + currentIndex
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Metadata of a snapshot without the opaque payload. */
data class BrowserWebViewStateMeta(
    val tabId: String,
    val savedUrl: String,
    val savedAtMillis: Long,
    val webViewPackageVersion: String?,
    val entryCount: Int,
    val currentIndex: Int
)

/** Why a stored snapshot was refused. Every value means "fall back to a plain URL load". */
enum class BrowserWebViewStateDecodeFailure {
    TRUNCATED,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    FIELD_TOO_LARGE,
    TAB_MISMATCH,
    INVALID_URL,
    INVALID_COUNTS,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    UNDECODABLE
}

sealed interface BrowserWebViewStateDecodeResult {
    data class Decoded(val snapshot: BrowserWebViewStateSnapshot) : BrowserWebViewStateDecodeResult

    data class Rejected(val failure: BrowserWebViewStateDecodeFailure) :
        BrowserWebViewStateDecodeResult
}

/**
 * Stable framing around the opaque WebView state payload.
 *
 * Layout (big endian):
 * `int magic | int version | int(tabIdLen) tabId | int(urlLen) url | long savedAtMillis |
 *  int(webViewPackageVersionLen) version | int entryCount | int currentIndex |
 *  int payloadLength | int payloadCrc32 | payload`
 *
 * The framing deliberately rejects anything it does not fully understand instead of guessing.
 */
object BrowserWebViewStateEnvelope {
    private const val MAGIC = 0x53535753 // "SSWS"

    const val VERSION = 1

    /** Upper bound for one text field (tab id / url / WebView version). */
    const val MAX_TEXT_BYTES = 32 * 1024

    fun encode(snapshot: BrowserWebViewStateSnapshot): ByteArray {
        require(snapshot.tabId.isNotBlank()) { "tabId must not be blank" }
        require(snapshot.savedUrl.isNotBlank()) { "savedUrl must not be blank" }
        require(snapshot.tabId.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES)
        require(snapshot.savedUrl.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES)
        require(snapshot.entryCount >= 1) { "entryCount must be >= 1" }
        require(snapshot.currentIndex in 0 until snapshot.entryCount) { "currentIndex out of range" }
        require(snapshot.payload.isNotEmpty()) { "payload must not be empty" }

        val output = ByteArrayOutputStream(snapshot.payload.size + 256)
        DataOutputStream(output).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(VERSION)
            stream.writeSizedText(snapshot.tabId)
            stream.writeSizedText(snapshot.savedUrl)
            stream.writeLong(snapshot.savedAtMillis)
            stream.writeSizedText(snapshot.webViewPackageVersion.orEmpty())
            stream.writeInt(snapshot.entryCount)
            stream.writeInt(snapshot.currentIndex)
            stream.writeInt(snapshot.payload.size)
            stream.writeInt(crc32(snapshot.payload))
            stream.write(snapshot.payload)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedTabId: String): BrowserWebViewStateDecodeResult {
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        var fieldFailure: BrowserWebViewStateDecodeFailure? =
            BrowserWebViewStateDecodeFailure.TRUNCATED

        fun readText(): String? {
            val length = stream.readInt()
            if (length < 0 || length > MAX_TEXT_BYTES) {
                fieldFailure = BrowserWebViewStateDecodeFailure.FIELD_TOO_LARGE
                return null
            }
            if (length > stream.available()) {
                fieldFailure = BrowserWebViewStateDecodeFailure.TRUNCATED
                return null
            }
            val textBytes = ByteArray(length)
            stream.readFully(textBytes)
            fieldFailure = BrowserWebViewStateDecodeFailure.TRUNCATED
            return String(textBytes, Charsets.UTF_8)
        }

        return try {
            if (stream.readInt() != MAGIC) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.BAD_MAGIC
                )
            }
            if (stream.readInt() != VERSION) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.UNSUPPORTED_VERSION
                )
            }

            val tabId = readText() ?: return BrowserWebViewStateDecodeResult.Rejected(
                fieldFailure ?: BrowserWebViewStateDecodeFailure.TRUNCATED
            )
            if (tabId != expectedTabId) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.TAB_MISMATCH
                )
            }

            val savedUrl = readText() ?: return BrowserWebViewStateDecodeResult.Rejected(
                fieldFailure ?: BrowserWebViewStateDecodeFailure.TRUNCATED
            )
            if (!savedUrl.startsWith("http")) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.INVALID_URL
                )
            }

            val savedAtMillis = stream.readLong()
            val webViewPackageVersion = readText() ?: return BrowserWebViewStateDecodeResult
                .Rejected(fieldFailure ?: BrowserWebViewStateDecodeFailure.TRUNCATED)

            val entryCount = stream.readInt()
            val currentIndex = stream.readInt()
            if (entryCount < 1 || currentIndex !in 0 until entryCount) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.INVALID_COUNTS
                )
            }

            val payloadLength = stream.readInt()
            // The stream still holds the 4 byte checksum plus the payload.
            if (payloadLength <= 0 || payloadLength + 4 != stream.available()) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.LENGTH_MISMATCH
                )
            }
            val payloadCrc32 = stream.readInt()
            val payload = ByteArray(payloadLength)
            stream.readFully(payload)
            if (stream.available() != 0) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.LENGTH_MISMATCH
                )
            }
            if (crc32(payload) != payloadCrc32) {
                return BrowserWebViewStateDecodeResult.Rejected(
                    BrowserWebViewStateDecodeFailure.CHECKSUM_MISMATCH
                )
            }

            BrowserWebViewStateDecodeResult.Decoded(
                BrowserWebViewStateSnapshot(
                    tabId = tabId,
                    savedUrl = savedUrl,
                    savedAtMillis = savedAtMillis,
                    webViewPackageVersion = webViewPackageVersion.takeIf { it.isNotBlank() },
                    entryCount = entryCount,
                    currentIndex = currentIndex,
                    payload = payload
                )
            )
        } catch (error: EOFException) {
            BrowserWebViewStateDecodeResult.Rejected(BrowserWebViewStateDecodeFailure.TRUNCATED)
        } catch (error: Throwable) {
            // A Parcel/Bundle written by a different Android version may fail while decoding the
            // stored object graph instead of the framing; never propagate that to the UI.
            BrowserWebViewStateDecodeResult.Rejected(BrowserWebViewStateDecodeFailure.UNDECODABLE)
        }
    }

    private fun DataOutputStream.writeSizedText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun crc32(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }
}
