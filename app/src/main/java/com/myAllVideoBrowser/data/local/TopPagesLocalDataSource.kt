package com.myAllVideoBrowser.data.local

import androidx.core.net.toUri
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPagesLocalDataSource @Inject constructor(
    private val pageDao: PageDao,
    private val sharedPrefHelper: SharedPrefHelper
) : TopPagesRepository {
    companion object {
        private const val TIKTOK_URL = "https://www.tiktok.com"
        private const val X_URL = "https://x.com"
        private const val INSTAGRAM_URL = "https://www.instagram.com"
        private const val FACEBOOK_URL = "https://www.facebook.com"
    }

    override suspend fun getTopPages(): List<PageInfo> {
        val localBookmarks = pageDao.getPageInfos().blockingFirst(emptyList())
        if (localBookmarks.isEmpty()) {
            val isFirstStart = sharedPrefHelper.getIsFirstStart()
            if (isFirstStart) {
                val defaultList = getDefaultBookmarks()
                pageDao.insertAllProgressInfo(defaultList)
                sharedPrefHelper.setHasMigratedHomeDefaultSites(true)

                return defaultList
            }
        }

        return migrateHomeDefaultSitesIfNeeded(localBookmarks)
    }

    override fun saveTopPage(pageInfo: PageInfo) {
        pageDao.insertProgressInfo(pageInfo)
    }

    override fun replaceBookmarksWith(pageInfos: List<PageInfo>) {
        pageDao.deleteAll()
        pageDao.insertAllProgressInfo(pageInfos)
    }

    override fun deletePageInfo(pageInfo: PageInfo) {
        pageDao.deleteProgressInfo(pageInfo)
    }

    override suspend fun updateLocalStorageFavicons(): Flow<PageInfo> {
        throw NotImplementedError("NO NEED, HANDLED BY REPO")
    }

    private fun getDefaultBookmarks(): List<PageInfo> {
        return defaultHomeSites().mapIndexed { index, page ->
            page.copy(order = index)
        }
    }

    private fun migrateHomeDefaultSitesIfNeeded(localBookmarks: List<PageInfo>): List<PageInfo> {
        if (localBookmarks.isEmpty() || sharedPrefHelper.hasMigratedHomeDefaultSites()) {
            return localBookmarks
        }

        val remaining = localBookmarks.toMutableList()
        val merged = mutableListOf<PageInfo>()

        defaultHomeSites().forEachIndexed { index, defaultPage ->
            val existingIndex = remaining.indexOfFirst { isSameDefaultSite(it.link, defaultPage.link) }
            if (existingIndex >= 0) {
                val existing = remaining.removeAt(existingIndex)
                merged += existing.copy(
                    isSystem = true,
                    name = existing.name.ifBlank { defaultPage.name },
                    link = defaultPage.link,
                    order = index
                )
            } else {
                merged += defaultPage.copy(order = index)
            }
        }

        merged += remaining.mapIndexed { index, page ->
            page.copy(order = index + merged.size)
        }

        pageDao.deleteAll()
        pageDao.insertAllProgressInfo(merged)
        sharedPrefHelper.setHasMigratedHomeDefaultSites(true)
        return merged
    }

    private fun defaultHomeSites(): List<PageInfo> {
        return listOf(
            PageInfo(link = TIKTOK_URL, name = "TikTok", isSystem = true),
            PageInfo(link = X_URL, name = "X", isSystem = true),
            PageInfo(link = INSTAGRAM_URL, name = "Instagram", isSystem = true),
            PageInfo(link = FACEBOOK_URL, name = "Facebook", isSystem = true)
        )
    }

    private fun isSameDefaultSite(left: String, right: String): Boolean {
        return normalizeBookmarkHost(left) == normalizeBookmarkHost(right)
    }

    private fun normalizeBookmarkHost(url: String): String {
        val host = url.toUri().host.orEmpty().removePrefix("www.")
        return if (host == "twitter.com") "x.com" else host
    }
}
