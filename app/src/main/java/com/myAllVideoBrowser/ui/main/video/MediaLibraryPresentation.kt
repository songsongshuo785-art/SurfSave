package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.model.LocalVideo
import java.util.Locale

enum class LibraryMediaType { ALL, VIDEO, AUDIO }

/** Display-only filtering; the repository list and its newest-first order remain intact. */
object MediaLibraryPresentation {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "amr")
    fun filter(items: List<LocalVideo>, query: String, type: LibraryMediaType): List<LocalVideo> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return items.filter { item ->
            val audio = item.mimeType.startsWith("audio/", ignoreCase = true) ||
                (!item.mimeType.startsWith("video/", ignoreCase = true) && item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions)
            val typeMatches = type == LibraryMediaType.ALL || (type == LibraryMediaType.AUDIO) == audio
            typeMatches && terms.all { term ->
                item.name.contains(term, ignoreCase = true) || item.sourceHost.contains(term, ignoreCase = true)
            }
        }
    }
}
