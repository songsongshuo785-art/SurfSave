package com.myAllVideoBrowser.ui.main.video

import com.myAllVideoBrowser.data.local.model.LocalVideo
import java.util.Locale

internal object VideoLibraryOrdering {
    fun newestFirst(videos: List<LocalVideo>): MutableList<LocalVideo> {
        return videos.sortedWith(
            compareByDescending<LocalVideo> { it.sortTimeMillis }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.uri.toString() }
        ).toMutableList()
    }
}
