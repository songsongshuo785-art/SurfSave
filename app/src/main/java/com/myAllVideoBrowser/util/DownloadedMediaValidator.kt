package com.myAllVideoBrowser.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale

object DownloadedMediaValidator {
    private const val PROBE_BYTES = 8 * 1024

    @Suppress("UNUSED_PARAMETER")
    fun validate(file: File, isLive: Boolean = false): String? {
        if (!file.exists()) {
            return "Downloaded file is missing"
        }

        if (!file.isFile) {
            return "Downloaded media path is not a file"
        }

        if (!file.canRead()) {
            return "Downloaded file is not readable"
        }

        return validateSource(file.length()) { file.inputStream() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun validate(context: Context, uri: Uri, isLive: Boolean = false): String? {
        if (uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            val path = uri.path ?: return "Downloaded file URI has no path"
            return validate(File(path), isLive)
        }

        val resolver = context.contentResolver
        val length = ContentLengthResolver.resolve(context, uri).length
            ?: return "Downloaded media size is unavailable"
        return validateSource(length) { resolver.openInputStream(uri) }
    }

    private fun validateSource(length: Long, openStream: () -> InputStream?): String? {
        if (length < 0L) {
            return "Downloaded media size is unavailable"
        }
        if (length == 0L) {
            return "Downloaded file is empty"
        }

        val probe = try {
            openStream()?.use(::readProbe)
                ?: return "Downloaded media could not be opened"
        } catch (_: SecurityException) {
            return "Downloaded media is not readable"
        } catch (_: Throwable) {
            return "Downloaded media could not be read"
        }

        if (probe.isEmpty()) {
            return "Downloaded media could not be read"
        }

        when (inspectMpegTransportStream(probe)) {
            TransportStreamProbe.VALID -> return null
            TransportStreamProbe.INVALID -> {
                return "Downloaded MPEG-TS content is truncated or malformed"
            }
            TransportStreamProbe.NOT_TS -> Unit
        }

        if (hasKnownMediaSignature(probe)) {
            return null
        }

        val text = decodeTextProbe(probe)
        if (text != null) {
            return if (looksLikeErrorResponse(text)) {
                "Downloaded content looks like a web or error response, not a media file"
            } else {
                "Downloaded content is text, not a media file"
            }
        }

        return "Downloaded media container is unsupported or could not be identified"
    }

    private fun readProbe(input: InputStream): ByteArray {
        val buffer = ByteArray(PROBE_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) {
                break
            }
            if (read == 0) {
                val singleByte = input.read()
                if (singleByte < 0) {
                    break
                }
                buffer[total++] = singleByte.toByte()
            } else {
                total += read
            }
        }
        return buffer.copyOf(total)
    }

    private fun hasKnownMediaSignature(bytes: ByteArray): Boolean {
        return isIsoBaseMedia(bytes) ||
            startsWith(bytes, 0x1A, 0x45, 0xDF, 0xA3) || // Matroska/WebM
            startsWithAscii(bytes, "OggS") ||
            startsWithAscii(bytes, "fLaC") ||
            isRiffMedia(bytes) ||
            startsWithAscii(bytes, "ID3") ||
            isMpegAudio(bytes) ||
            isAacAdts(bytes) ||
            startsWith(bytes, 0x00, 0x00, 0x01, 0xBA) // MPEG program stream
    }

    private fun isIsoBaseMedia(bytes: ByteArray): Boolean {
        return bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    private fun isRiffMedia(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !startsWithAscii(bytes, "RIFF")) {
            return false
        }
        return asciiAt(bytes, 8, "WAVE") || asciiAt(bytes, 8, "AVI ")
    }

    private fun isMpegAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xFF) != 0xFF) {
            return false
        }
        return (bytes[1].toInt() and 0xE0) == 0xE0
    }

    private fun isAacAdts(bytes: ByteArray): Boolean {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xFF) != 0xFF) {
            return false
        }
        return (bytes[1].toInt() and 0xF6) == 0xF0
    }

    private fun inspectMpegTransportStream(bytes: ByteArray): TransportStreamProbe {
        if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != 0x47) {
            return TransportStreamProbe.NOT_TS
        }
        if (bytes.size < MPEG_TS_PACKET_BYTES * 2) {
            return TransportStreamProbe.INVALID
        }

        val completePackets = bytes.size / MPEG_TS_PACKET_BYTES
        for (packetIndex in 0 until completePackets) {
            val offset = packetIndex * MPEG_TS_PACKET_BYTES
            if ((bytes[offset].toInt() and 0xFF) != MPEG_TS_SYNC_BYTE) {
                return TransportStreamProbe.INVALID
            }
            val adaptationFieldControl = (bytes[offset + 3].toInt() ushr 4) and 0x03
            if (adaptationFieldControl == 0) {
                return TransportStreamProbe.INVALID
            }
        }
        return TransportStreamProbe.VALID
    }

    private fun decodeTextProbe(bytes: ByteArray): String? {
        if (!isMostlyText(bytes)) {
            return null
        }
        return normalizedText(bytes)
    }

    private fun normalizedText(bytes: ByteArray): String? {
        return runCatching {
            String(bytes, Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trimStart()
        }.getOrNull()
    }

    private fun isMostlyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) {
            return false
        }
        val printable = bytes.count { byte ->
            val value = byte.toInt() and 0xFF
            value == 0x09 || value == 0x0A || value == 0x0D || value in 0x20..0x7E || value >= 0xC2
        }
        return printable * 100 / bytes.size >= 85
    }

    private fun looksLikeErrorResponse(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.startsWith("<head") ||
            lower.startsWith("<body") ||
            lower.startsWith("<error") ||
            lower.startsWith("<?xml") ||
            lower.startsWith("http/1.") ||
            lower.startsWith("http/2") ||
            lower.startsWith("{") ||
            lower.startsWith("[") ||
            lower.contains("access denied") ||
            lower.contains("accessdenied") ||
            lower.contains("cloudflare") ||
            lower.contains("captcha") ||
            lower.contains("forbidden") ||
            lower.contains("unauthorized") ||
            lower.contains("not found") ||
            lower.contains("rate limit")
    }

    private fun startsWithAscii(bytes: ByteArray, value: String): Boolean =
        asciiAt(bytes, 0, value)

    private fun asciiAt(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset < 0 || bytes.size < offset + value.length) {
            return false
        }
        return value.indices.all { index -> bytes[offset + index] == value[index].code.toByte() }
    }

    private fun startsWith(bytes: ByteArray, vararg values: Int): Boolean {
        if (bytes.size < values.size) {
            return false
        }
        return values.indices.all { index -> bytes[index].toInt() and 0xFF == values[index] }
    }

    private enum class TransportStreamProbe {
        NOT_TS,
        VALID,
        INVALID
    }

    private const val MPEG_TS_PACKET_BYTES = 188
    private const val MPEG_TS_SYNC_BYTE = 0x47
}
