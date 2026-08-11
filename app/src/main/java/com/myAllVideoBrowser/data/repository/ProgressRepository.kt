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

    fun findDuplicateByFingerprint(fingerprint: String): ProgressInfo?

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

    fun claimYtDlpExecution(id: String, token: String, startedAt: Long, logPath: String): Int

    fun requestYtDlpPause(
        id: String,
        token: String,
        reason: Int,
        queuedForLater: Boolean,
        infoLine: String,
        logPath: String
    ): Int

    fun requestYtDlpCancel(
        id: String,
        expectedToken: String,
        assignedToken: String,
        removePartial: Boolean,
        logPath: String
    ): Int

    fun resumeYtDlp(id: String, queuePosition: Long, logPath: String): Int

    fun updateYtDlpProgress(
        id: String,
        token: String,
        downloaded: Long,
        total: Long,
        fragDownloaded: Int,
        fragTotal: Int,
        infoLine: String,
        startedAt: Long,
        logPath: String,
        isLive: Boolean
    ): Int

    fun claimYtDlpFinalization(id: String, token: String, source: String, target: String): Int

    fun commitYtDlpFinalization(
        id: String,
        token: String,
        status: Int,
        completedAt: Long,
        lastError: String,
        infoLine: String
    ): Int

    fun commitYtDlpError(id: String, token: String, completedAt: Long, error: String): Int

    fun commitYtDlpPause(id: String, token: String, infoLine: String): Int

    fun commitYtDlpCanceled(id: String, token: String, completedAt: Long): Int

    fun deleteCommittedYtDlpCanceled(id: String, token: String): Int

    fun adoptLegacyYtDlpExecution(id: String, token: String): Int
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

    override fun findDuplicateByFingerprint(fingerprint: String): ProgressInfo? {
        return localDataSource.findDuplicateByFingerprint(fingerprint)
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

    override fun claimYtDlpExecution(id: String, token: String, startedAt: Long, logPath: String): Int =
        localDataSource.claimYtDlpExecution(id, token, startedAt, logPath)

    override fun requestYtDlpPause(
        id: String,
        token: String,
        reason: Int,
        queuedForLater: Boolean,
        infoLine: String,
        logPath: String
    ): Int = localDataSource.requestYtDlpPause(
        id, token, reason, queuedForLater, infoLine, logPath
    )

    override fun requestYtDlpCancel(
        id: String,
        expectedToken: String,
        assignedToken: String,
        removePartial: Boolean,
        logPath: String
    ): Int = localDataSource.requestYtDlpCancel(
        id, expectedToken, assignedToken, removePartial, logPath
    )

    override fun resumeYtDlp(id: String, queuePosition: Long, logPath: String): Int =
        localDataSource.resumeYtDlp(id, queuePosition, logPath)

    override fun updateYtDlpProgress(
        id: String,
        token: String,
        downloaded: Long,
        total: Long,
        fragDownloaded: Int,
        fragTotal: Int,
        infoLine: String,
        startedAt: Long,
        logPath: String,
        isLive: Boolean
    ): Int = localDataSource.updateYtDlpProgress(
        id, token, downloaded, total, fragDownloaded, fragTotal, infoLine,
        startedAt, logPath, isLive
    )

    override fun claimYtDlpFinalization(
        id: String,
        token: String,
        source: String,
        target: String
    ): Int = localDataSource.claimYtDlpFinalization(id, token, source, target)

    override fun commitYtDlpFinalization(
        id: String,
        token: String,
        status: Int,
        completedAt: Long,
        lastError: String,
        infoLine: String
    ): Int = localDataSource.commitYtDlpFinalization(
        id, token, status, completedAt, lastError, infoLine
    )

    override fun commitYtDlpError(id: String, token: String, completedAt: Long, error: String): Int =
        localDataSource.commitYtDlpError(id, token, completedAt, error)

    override fun commitYtDlpPause(id: String, token: String, infoLine: String): Int =
        localDataSource.commitYtDlpPause(id, token, infoLine)

    override fun commitYtDlpCanceled(id: String, token: String, completedAt: Long): Int =
        localDataSource.commitYtDlpCanceled(id, token, completedAt)

    override fun deleteCommittedYtDlpCanceled(id: String, token: String): Int =
        localDataSource.deleteCommittedYtDlpCanceled(id, token)

    override fun adoptLegacyYtDlpExecution(id: String, token: String): Int =
        localDataSource.adoptLegacyYtDlpExecution(id, token)
}
