package com.myAllVideoBrowser.data.local

import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import io.reactivex.rxjava3.core.Flowable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressLocalDataSource @Inject constructor(
    private val progressDao: ProgressDao
) : ProgressRepository {

    override fun getProgressInfos(): Flowable<List<ProgressInfo>> {
        return progressDao.getProgressInfos()
    }

    override fun getProgressInfosOnce(): List<ProgressInfo> {
        return progressDao.getAllProgressInfos()
    }

    override fun getProgressInfoById(id: String): ProgressInfo? {
        return progressDao.getProgressInfoById(id)
    }

    override fun saveProgressInfo(progressInfo: ProgressInfo) {
        progressDao.insertProgressInfo(progressInfo)
    }

    override fun saveProgressInfos(progressInfos: List<ProgressInfo>) {
        progressDao.insertAllProgressInfo(progressInfos)
    }

    override fun deleteProgressInfo(progressInfo: ProgressInfo) {
        progressDao.deleteProgressInfo(progressInfo)
    }

    override fun updateProgressFields(
        id: String,
        downloaded: Long,
        total: Long,
        fragDownloaded: Int,
        fragTotal: Int,
        status: Int,
        infoLine: String,
        startedAt: Long,
        completedAt: Long,
        lastError: String,
        logPath: String,
        isLive: Boolean
    ) {
        progressDao.updateProgressFields(
            id,
            downloaded,
            total,
            fragDownloaded,
            fragTotal,
            status,
            infoLine,
            startedAt,
            completedAt,
            lastError,
            logPath,
            isLive
        )
    }

    override fun updateQueuePosition(id: String, position: Long) {
        progressDao.updateQueuePosition(id, position)
    }

    override fun updateQueueState(id: String, status: Int, queuedForLater: Boolean, infoLine: String, logPath: String) {
        progressDao.updateQueueState(id, status, queuedForLater, infoLine, logPath)
    }
}
