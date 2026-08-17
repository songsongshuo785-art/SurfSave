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

    override fun findDuplicateByFingerprint(fingerprint: String): ProgressInfo? {
        return progressDao.findDuplicateByFingerprint(fingerprint)
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

    override fun claimYtDlpExecution(id: String, token: String, startedAt: Long, logPath: String): Int =
        progressDao.claimYtDlpExecution(id, token, startedAt, logPath)

    override fun requestYtDlpPause(
        id: String,
        token: String,
        reason: Int,
        queuedForLater: Boolean,
        infoLine: String,
        logPath: String
    ): Int = progressDao.requestYtDlpPause(id, token, reason, queuedForLater, infoLine, logPath)

    override fun requestYtDlpCancel(
        id: String,
        expectedToken: String,
        assignedToken: String,
        removePartial: Boolean,
        logPath: String
    ): Int = progressDao.requestYtDlpCancel(
        id, expectedToken, assignedToken, removePartial, logPath
    )

    override fun resumeYtDlp(id: String, queuePosition: Long, logPath: String): Int =
        progressDao.resumeYtDlp(id, queuePosition, logPath)

    override fun retryYtDlpFinalization(id: String, token: String, logPath: String): Int =
        progressDao.retryYtDlpFinalization(id, token, logPath)

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
    ): Int = progressDao.updateYtDlpProgress(
        id, token, downloaded, total, fragDownloaded, fragTotal, infoLine,
        startedAt, logPath, isLive
    )

    override fun claimYtDlpFinalization(
        id: String,
        token: String,
        source: String,
        target: String
    ): Int = progressDao.claimYtDlpFinalization(id, token, source, target)

    override fun commitYtDlpFinalization(
        id: String,
        token: String,
        status: Int,
        completedAt: Long,
        lastError: String,
        infoLine: String
    ): Int = progressDao.commitYtDlpFinalization(
        id, token, status, completedAt, lastError, infoLine
    )

    override fun commitYtDlpError(id: String, token: String, completedAt: Long, error: String): Int =
        progressDao.commitYtDlpError(id, token, completedAt, error)

    override fun commitYtDlpPause(id: String, token: String, infoLine: String): Int =
        progressDao.commitYtDlpPause(id, token, infoLine)

    override fun commitYtDlpCanceled(id: String, token: String, completedAt: Long): Int =
        progressDao.commitYtDlpCanceled(id, token, completedAt)

    override fun deleteCommittedYtDlpCanceled(id: String, token: String): Int =
        progressDao.deleteCommittedYtDlpCanceled(id, token)

    override fun adoptLegacyYtDlpExecution(id: String, token: String): Int =
        progressDao.adoptLegacyYtDlpExecution(id, token)
}
