package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.model.LocalVideo
import java.util.Locale

internal object VideoLibraryOrdering {
    enum class SortOrder {
        NEWEST,
        OLDEST,
        NAME,
        SIZE,
        DURATION
    }

    fun sort(videos: List<LocalVideo>, order: SortOrder): MutableList<LocalVideo> {
        val comparator = when (order) {
            SortOrder.NEWEST -> compareBy<LocalVideo> { it.sortTimeMillis <= 0L }
                .thenByDescending { it.sortTimeMillis.coerceAtLeast(0L) }
            SortOrder.OLDEST -> compareBy<LocalVideo> { it.sortTimeMillis <= 0L }
                .thenBy { it.sortTimeMillis.coerceAtLeast(0L) }
            SortOrder.NAME -> compareBy<LocalVideo> { it.displayName.lowercase(Locale.ROOT) }
            SortOrder.SIZE -> compareBy<LocalVideo> { it.sizeBytes <= 0L }
                .thenByDescending { it.sizeBytes.coerceAtLeast(0L) }
            SortOrder.DURATION -> compareBy<LocalVideo> { it.durationMillis <= 0L }
                .thenByDescending { it.durationMillis.coerceAtLeast(0L) }
        }
        return videos.sortedWith(
            comparator
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.uri.toString() }
        ).toMutableList()
    }

    fun newestFirst(videos: List<LocalVideo>): MutableList<LocalVideo> {
        return sort(videos, SortOrder.NEWEST)
    }
}
