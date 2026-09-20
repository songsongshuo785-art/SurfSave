package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bitmap is published immediately; only JPEG encoding/storage waits for IO. */
object BrowserThumbnailPersistence {
    fun persist(scope: CoroutineScope, snapshot: WebTab, current: () -> WebTab?, publish: (WebTab) -> Unit, io: CoroutineDispatcher = Dispatchers.IO) {
        val bitmap = snapshot.getPageThumbnail() ?: return
        scope.launch {
            var path: String? = null
            var accepted = false
            try {
                withContext(io) { path = BrowserThumbnailStore.save(snapshot.id, bitmap) }
                if (path == null) return@launch
                val latest = current()
                if (latest == null || latest.id != snapshot.id || latest.getUrl() != snapshot.getUrl() ||
                    latest.getPageThumbnail() !== bitmap) return@launch
                val previousPath = latest.getPageThumbnailPath()
                publish(latest.copyWith(pageThumbnail = null, pageThumbnailPath = path))
                accepted = true
                withContext(io) { if (previousPath != path) BrowserThumbnailStore.delete(previousPath) }
            } finally {
                withContext(NonCancellable + io) {
                    if (!accepted) BrowserThumbnailStore.delete(path)
                }
            }
        }
    }
}
