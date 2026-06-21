package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.util.Locale

object DownloadFilenameTemplate {
    const val DEFAULT_TEMPLATE = "%(title)s-%(resolution)s.%(ext)s"

    data class Context(
        val playlistIndex: Int? = null,
        val playlistTitle: String? = null
    )

    fun apply(
        videoInfo: VideoInfo,
        template: String,
        selectedFormat: VideoFormatEntity?,
        context: Context = Context()
    ): VideoInfo {
        val rendered = render(videoInfo, template, selectedFormat, context)
        return videoInfo.copy(
            title = rendered.baseName,
            ext = rendered.extension
        )
    }

    fun render(
        videoInfo: VideoInfo,
        template: String,
        selectedFormat: VideoFormatEntity?,
        context: Context = Context()
    ): RenderedName {
        val ext = cleanExtension(
            selectedFormat?.ext
                ?: videoInfo.ext
                ?: "mp4"
        )
        val variables = mapOf(
            "title" to videoInfo.title.ifBlank { "video" },
            "resolution" to resolutionLabel(selectedFormat),
            "format_id" to selectedFormat?.formatId.orEmpty(),
            "ext" to ext,
            "playlist_index" to context.playlistIndex?.let { "%03d".format(Locale.US, it) }.orEmpty(),
            "playlist_title" to context.playlistTitle.orEmpty()
        )

        val rawTemplate = template.ifBlank { DEFAULT_TEMPLATE }
        val expanded = Regex("""%\(([^)]+)\)s""").replace(rawTemplate) { match ->
            variables[match.groupValues[1]].orEmpty()
        }.cleanupSeparators()

        val (rawBase, rawExt) = splitExtension(expanded, ext)
        val cleanBase = FileNameCleaner.cleanFileName(rawBase.ifBlank { videoInfo.title.ifBlank { "video" } })
        val cleanExt = cleanExtension(rawExt.ifBlank { ext })
        return RenderedName(cleanBase, cleanExt)
    }

    private fun resolutionLabel(format: VideoFormatEntity?): String {
        val height = format?.height ?: 0
        if (height > 0) {
            return "${height}p"
        }
        val fields = listOfNotNull(format?.formatNote, format?.format, format?.url)
        for (field in fields) {
            Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(field)?.let {
                return "${it.groupValues[1]}p"
            }
            Regex("""\d{3,5}x(\d{3,5})""").find(field)?.let {
                return "${it.groupValues[1]}p"
            }
        }
        return ""
    }

    private fun splitExtension(value: String, fallbackExt: String): Pair<String, String> {
        val trimmed = value.trim()
        val extension = trimmed.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.length in 1..8 && extension.all { it.isLetterOrDigit() }) {
            trimmed.substringBeforeLast('.') to extension
        } else {
            trimmed to fallbackExt
        }
    }

    private fun cleanExtension(value: String): String {
        val cleaned = value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
            .take(8)
        return cleaned.ifBlank { "mp4" }
    }

    private fun String.cleanupSeparators(): String {
        return replace(Regex("""[-_\s]+\.""") , ".")
            .replace(Regex("""[-_\s]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    data class RenderedName(
        val baseName: String,
        val extension: String
    ) {
        val fileName: String
            get() = "$baseName.$extension"
    }
}
