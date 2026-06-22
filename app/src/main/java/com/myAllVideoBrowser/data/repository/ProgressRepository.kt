package com.myAllVideoBrowser.data.repository

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.di.qualifier.LocalData
import io.reactivex.rxjava3.core.Flowable
import javax.inject.Inject
import javax.inject.Singleton

interface ProgressRepository {

    fun getProgressInfos(): Flowable<List<ProgressInfo>>

    fun getProgressInfosOnce(): List<ProgressInfo>

    fun getProgressInfoById(id: String): ProgressInfo?

    fun saveProgressInfo(progressInfo: ProgressInfo)

    fun saveProgressInfos(progressInfos: List<ProgressInfo>)

    fun deleteProgressInfo(progressInfo: ProgressInfo)

    fun updateProgressFields(
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
    )

    fun updateQueuePosition(id: String, position: Long)

    fun updateQueueState(id: String, status: Int, queuedForLater: Boolean, infoLine: String, logPath: String)
}

@Singleton
class ProgressRepositoryImpl @Inject constructor(
    @param:LocalData private val localDataSource: ProgressRepository
) : ProgressRepository {
    override fun getProgressInfos(): Flowable<List<ProgressInfo>> {
        return localDataSource.getProgressInfos()
    }

    override fun getProgressInfosOnce(): List<ProgressInfo> {
        return localDataSource.getProgressInfosOnce()
    }

    override fun getProgressInfoById(id: String): ProgressInfo? {
        return localDataSource.getProgressInfoById(id)
    }

    override fun saveProgressInfo(progressInfo: ProgressInfo) {
        localDataSource.saveProgressInfo(progressInfo)
    }

    override fun saveProgressInfos(progressInfos: List<ProgressInfo>) {
        localDataSource.saveProgressInfos(progressInfos)
    }

    override fun deleteProgressInfo(progressInfo: ProgressInfo) {
        localDataSource.deleteProgressInfo(progressInfo)
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
        localDataSource.updateProgressFields(
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
        localDataSource.updateQueuePosition(id, position)
    }

    override fun updateQueueState(id: String, status: Int, queuedForLater: Boolean, infoLine: String, logPath: String) {
        localDataSource.updateQueueState(id, status, queuedForLater, infoLine, logPath)
    }
}
