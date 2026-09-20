package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import java.io.File
import java.util.Locale

internal class CompletedVideoMetadataIndex private constructor(
    private val byUri: Map<String, ProgressInfo>,
    private val byUniqueFileName: Map<String, ProgressInfo>
) {
    fun find(mediaUri: String, displayName: String): ProgressInfo? =
        byUri[mediaUri] ?: byUniqueFileName[normalizeFileName(displayName)]

    companion object {
        fun from(progress: List<ProgressInfo>): CompletedVideoMetadataIndex {
            val trustedProgress = progress.filter { it.mediaBindingTrusted }
            val byUri = trustedProgress
                .filter { it.finalMediaUri.isNotBlank() }
                .groupBy { it.finalMediaUri }
                .mapNotNull { (uri, items) ->
                    items.distinctBy { it.id }.singleOrNull()?.let { uri to it }
                }
                .toMap()
            val candidates = trustedProgress
                .flatMap { item -> candidateFileNames(item).map { name -> name to item } }
                .groupBy({ it.first }, { it.second })
            val unique = candidates.mapNotNull { (name, items) ->
                items.distinctBy { it.id }.singleOrNull()?.let { name to it }
            }.toMap()
            return CompletedVideoMetadataIndex(byUri, unique)
        }

        private fun candidateFileNames(progressInfo: ProgressInfo): Set<String> {
            val videoInfo = progressInfo.videoInfo
            return listOf(
                videoInfo.name,
                File(videoInfo.name).name,
                videoInfo.title,
                "${videoInfo.title}.mp4"
            ).map(::normalizeFileName).filter(String::isNotBlank).toSet()
        }

        private fun normalizeFileName(fileName: String): String =
            File(fileName).name.trim().lowercase(Locale.US)
    }
}
