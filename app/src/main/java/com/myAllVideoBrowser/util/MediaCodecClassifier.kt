package com.myAllVideoBrowser.util

import java.util.Locale

/** 解析 HLS/DASH CODECS 列表，并集中决定哪些 HEVC/Dolby Vision 变体需转为 H.264。 */
object MediaCodecClassifier {
    private val videoPrefixes = listOf(
        "avc1",
        "avc3",
        "h264",
        "hvc1",
        "hev1",
        "hevc",
        "dvh1",
        "dvhe",
        "av01",
        "vp09",
        "vp9"
    )
    private val audioPrefixes = listOf(
        "mp4a",
        "aac",
        "ac-3",
        "ec-3",
        "opus",
        "vorbis",
        "flac"
    )
    private val h264TranscodePrefixes = listOf("hvc1", "hev1", "hevc", "dvh1", "dvhe")

    fun firstVideoCodec(descriptor: String?): String? =
        tokens(descriptor).firstOrNull { token -> videoPrefixes.any(token::startsWith) }

    fun firstAudioCodec(descriptor: String?): String? =
        tokens(descriptor).firstOrNull { token -> audioPrefixes.any(token::startsWith) }

    fun requiresH264Transcode(descriptor: String?): Boolean {
        return tokens(descriptor).any { token -> h264TranscodePrefixes.any(token::startsWith) }
    }

    private fun tokens(descriptor: String?): List<String> {
        return descriptor.orEmpty()
            .split(',', ';', ' ')
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() && it != "unknown" && it != "none" }
    }
}
