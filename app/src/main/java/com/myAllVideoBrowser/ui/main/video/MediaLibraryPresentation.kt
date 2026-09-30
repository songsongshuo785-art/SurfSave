package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.model.LocalVideo

enum class LibraryMediaType { ALL, VIDEO, AUDIO, IMAGE }

/** Display-only filtering; the repository list and its newest-first order remain intact. */
object MediaLibraryPresentation {
    fun filter(items: List<LocalVideo>, query: String, type: LibraryMediaType): List<LocalVideo> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return items.filter { item ->
            val typeMatches = when (type) {
                LibraryMediaType.ALL -> true
                LibraryMediaType.AUDIO -> item.isAudio
                LibraryMediaType.IMAGE -> item.isImage
                LibraryMediaType.VIDEO -> !item.isAudio && !item.isImage
            }
            typeMatches && terms.all { term ->
                item.name.contains(term, ignoreCase = true) || item.sourceHost.contains(term, ignoreCase = true)
            }
        }
    }
}
