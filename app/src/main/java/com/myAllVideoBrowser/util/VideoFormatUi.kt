package com.myAllVideoBrowser.util

import android.content.Context
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.util.Locale

object VideoFormatUi {
    fun selectionKey(format: VideoFormatEntity): String {
        return format.formatId?.takeIf { it.isNotBlank() }
            ?: format.format?.takeIf { it.isNotBlank() }
            ?: format.url?.takeIf { it.isNotBlank() }
            ?: format.videoOnlyUrl?.takeIf { it.isNotBlank() }
            ?: format.audioOnlyUrl?.takeIf { it.isNotBlank() }
            ?: format.id
    }

    fun defaultSelectionKey(info: VideoInfo): String {
        val format = info.formats.formats.maxWithOrNull(
            compareBy<VideoFormatEntity> { streamTypeScore(it) }
                .thenBy { inferredHeight(it) }
                .thenBy { bitrateBps(it) ?: 0L }
                .thenBy { knownSize(it) }
        ) ?: info.formats.formats.lastOrNull()

        return format?.let { selectionKey(it) } ?: "unknown"
    }

    fun findFormat(info: VideoInfo, key: String?): VideoFormatEntity? {
        if (key.isNullOrBlank()) {
            return null
        }

        return info.formats.formats.firstOrNull { selectionKey(it) == key }
            ?: info.formats.formats.firstOrNull { it.formatId == key || it.format == key }
            ?: info.formats.formats.firstOrNull { it.format?.contains(key) == true }
    }

    fun title(context: Context, format: VideoFormatEntity, position: Int): String {
        val quality = qualityLabel(format)
        if (quality.isNotBlank()) {
            return quality
        }

        val note = cleanMetadata(format.formatNote)
        if (note.isNotBlank()) {
            return note
        }

        val rawFormat = cleanMetadata(format.format)
        if (rawFormat.isNotBlank() && rawFormat.length <= 18) {
            return rawFormat.uppercase(Locale.US)
        }

        return context.getString(R.string.candidate_generic_title, position + 1)
    }

    fun details(context: Context, format: VideoFormatEntity, position: Int): String {
        val duration = formatDuration(format.duration ?: 0L)
        val bitrate = bitrateBps(format)
        val codec = codecSummary(format)
        val resolution = resolutionDetail(context, format)

        return listOfNotNull(
            context.getString(R.string.candidate_best_quality)
                .takeIf { position == 0 && inferredHeight(format) > 0 },
            streamType(format),
            format.ext?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
                ?: context.getString(R.string.candidate_format_mp4),
            resolution.takeIf { it.isNotBlank() },
            sizeLabel(context, format),
            duration.takeIf { it.isNotBlank() },
            bitrate?.takeIf { it > 0 }?.let { formatBitrate(it) },
            codec.takeIf { it.isNotBlank() },
            context.getString(R.string.candidate_generic_title, position + 1)
        ).joinToString(" | ")
    }

    fun sortFormats(formats: List<VideoFormatEntity>): List<VideoFormatEntity> {
        return formats.distinctBy { selectionKey(it) }
            .sortedWith(
                compareByDescending<VideoFormatEntity> { streamTypeScore(it) }
                    .thenByDescending { inferredHeight(it) }
                    .thenByDescending { bitrateBps(it) ?: 0L }
                    .thenByDescending { knownSize(it) }
                    .thenBy { selectionKey(it) }
            )
    }

    fun qualityLabel(format: VideoFormatEntity): String {
        val height = inferredHeight(format)
        if (height > 0) {
            return "${height}P"
        }

        val isAudioOnly = format.vcodec == "none" || format.format?.contains("audio", true) == true
        return if (isAudioOnly) "Audio" else ""
    }

    private fun inferredHeight(format: VideoFormatEntity): Int {
        if (format.height > 0) {
            return format.height
        }

        val fields = listOfNotNull(format.format, format.formatId, format.formatNote, format.url)
        for (field in fields) {
            Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(field)?.let {
                return it.groupValues[1].toIntOrNull() ?: 0
            }

            Regex("""\d{3,5}x(\d{3,5})""").find(field)?.let {
                return it.groupValues[1].toIntOrNull() ?: 0
            }
        }

        return 0
    }

    private fun resolutionDetail(context: Context, format: VideoFormatEntity): String {
        return when {
            format.width > 0 && format.height > 0 -> {
                context.getString(R.string.candidate_resolution_detail, format.width, format.height)
            }

            inferredHeight(format) > 0 -> "${inferredHeight(format)}P"
            else -> ""
        }
    }

    private fun streamType(format: VideoFormatEntity): String {
        return when {
            format.isMpd || format.formatId?.startsWith("mpd", true) == true -> "MPD"
            format.isM3u8 || format.formatId?.startsWith("hls", true) == true -> "HLS"
            format.vcodec == "none" -> "Audio"
            else -> "Video"
        }
    }

    private fun streamTypeScore(format: VideoFormatEntity): Int {
        val hasVideo = format.vcodec != null && format.vcodec != "none"
        val hasAudio = format.acodec != null && format.acodec != "none"
        return when {
            hasVideo && hasAudio -> 2
            hasVideo -> 1
            else -> 0
        }
    }

    private fun codecSummary(format: VideoFormatEntity): String {
        return listOf(format.vcodec, format.acodec)
            .mapNotNull { cleanCodec(it) }
            .distinct()
            .joinToString(" / ")
    }

    private fun cleanCodec(codec: String?): String? {
        val value = codec?.trim().orEmpty()
        return value.takeIf {
            it.isNotBlank() && !it.equals("unknown", true) && !it.equals("none", true)
        }
    }

    private fun cleanMetadata(value: String?): String {
        val cleaned = value?.trim().orEmpty()
        return cleaned.takeIf {
            it.isNotBlank() && !it.equals("unknown", true) && !it.equals("null", true)
        }.orEmpty()
    }

    private fun knownSize(format: VideoFormatEntity): Long {
        return when {
            format.fileSize > 0 -> format.fileSize
            format.fileSizeApproximate > 0 -> format.fileSizeApproximate
            estimatedSize(format) > 0 -> estimatedSize(format)
            else -> 0L
        }
    }

    private fun sizeLabel(context: Context, format: VideoFormatEntity): String {
        return when {
            format.fileSize > 0 -> FileUtil.getFileSizeReadable(format.fileSize.toDouble())
            format.fileSizeApproximate > 0 -> context.getString(
                R.string.candidate_estimated_size,
                FileUtil.getFileSizeReadable(format.fileSizeApproximate.toDouble())
            )

            estimatedSize(format) > 0 -> context.getString(
                R.string.candidate_estimated_size,
                FileUtil.getFileSizeReadable(estimatedSize(format).toDouble())
            )

            else -> context.getString(R.string.candidate_unknown_size)
        }
    }

    private fun estimatedSize(format: VideoFormatEntity): Long {
        val durationMs = format.duration ?: 0L
        val bitrate = bitrateBps(format) ?: 0L
        if (durationMs <= 0 || bitrate <= 0) {
            return 0L
        }

        return ((durationMs / 1000.0) * bitrate / 8.0).toLong()
    }

    private fun bitrateBps(format: VideoFormatEntity): Long? {
        return when {
            format.bitrate != null && format.bitrate > 0 -> format.bitrate
            format.tbr > 0 -> format.tbr.toLong() * 1000L
            format.abr > 0 -> format.abr.toLong() * 1000L
            else -> null
        }
    }

    private fun formatBitrate(bitsPerSecond: Long): String {
        return if (bitsPerSecond >= 1_000_000) {
            String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        } else {
            "${bitsPerSecond / 1000} kbps"
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds <= 0) {
            return ""
        }

        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
