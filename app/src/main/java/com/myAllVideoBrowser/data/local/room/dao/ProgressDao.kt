package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import io.reactivex.rxjava3.core.Flowable

@Dao
interface ProgressDao {

    @Query("SELECT * FROM ProgressInfo")
    fun getProgressInfos(): Flowable<List<ProgressInfo>>

    @Query("SELECT * FROM ProgressInfo")
    fun getAllProgressInfos(): List<ProgressInfo>

    @Query("SELECT * FROM ProgressInfo WHERE id = :id LIMIT 1")
    fun getProgressInfoById(id: String): ProgressInfo?

    @Query(
        """SELECT * FROM ProgressInfo
            WHERE downloadFingerprint = :fingerprint
            AND downloadStatus NOT IN (6, 9)
            ORDER BY queuedAt DESC, id DESC
            LIMIT 1"""
    )
    fun findDuplicateByFingerprint(fingerprint: String): ProgressInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProgressInfo(progressInfo: ProgressInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllProgressInfo(progressInfos: List<ProgressInfo>)

    // 按列更新进度/状态：只覆盖下方列，queuePosition 等队列列不被碰，
    // 避免与队列重排的整行 REPLACE 互相覆盖（lost update）。
    @Query(
        """UPDATE ProgressInfo SET
            progressDownloaded = :downloaded,
            progressTotal = :total,
            fragmentsDownloaded = :fragDownloaded,
            fragmentsTotal = :fragTotal,
            downloadStatus = :status,
            infoLine = :infoLine,
            startedAt = :startedAt,
            completedAt = :completedAt,
            lastError = :lastError,
            logPath = :logPath,
            isLive = :isLive
            WHERE id = :id"""
    )
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

    @Query("UPDATE ProgressInfo SET queuePosition = :position WHERE id = :id")
    fun updateQueuePosition(id: String, position: Long)

    @Query("UPDATE ProgressInfo SET downloadStatus = :status, queuedForLater = :queuedForLater, infoLine = :infoLine, logPath = :logPath WHERE id = :id")
    fun updateQueueState(id: String, status: Int, queuedForLater: Boolean, infoLine: String, logPath: String)

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 1, executionToken = :token,
            stopReason = 0, removePartialOnCancel = 0, finalizationSource = '',
            finalizationTarget = '', startedAt = CASE WHEN startedAt = 0 THEN :startedAt ELSE startedAt END,
            logPath = :logPath, infoLine = 'Preparing'
            WHERE id = :id AND downloadStatus = -1 AND queuedForLater = 0"""
    )
    fun claimYtDlpExecution(id: String, token: String, startedAt: Long, logPath: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 10, stopReason = :reason,
            queuedForLater = :queuedForLater, infoLine = :infoLine, logPath = :logPath
            WHERE id = :id AND executionToken = :token AND stopReason = 0
            AND downloadStatus IN (1, 2, 3, 4)"""
    )
    fun requestYtDlpPause(
        id: String,
        token: String,
        reason: Int,
        queuedForLater: Boolean,
        infoLine: String,
        logPath: String
    ): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 11, stopReason = 2,
            executionToken = :assignedToken, removePartialOnCancel = :removePartial,
            queuedForLater = 0, infoLine = 'Canceling', logPath = :logPath
            WHERE id = :id AND executionToken = :expectedToken
            AND downloadStatus IN (-1, 1, 2, 3, 4, 7, 10)
            AND stopReason IN (0, 1, 3)"""
    )
    fun requestYtDlpCancel(
        id: String,
        expectedToken: String,
        assignedToken: String,
        removePartial: Boolean,
        logPath: String
    ): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = -1, stopReason = 0,
            executionToken = '', removePartialOnCancel = 0, finalizationSource = '',
            finalizationTarget = '', queuedForLater = 0, infoLine = 'Queued',
            queuePosition = :queuePosition, logPath = :logPath
            WHERE id = :id AND downloadStatus IN (6, 7, 8)"""
    )
    fun resumeYtDlp(id: String, queuePosition: Long, logPath: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 12, completedAt = 0,
            lastError = '', infoLine = 'Retrying media publication', logPath = :logPath
            WHERE id = :id AND executionToken = :token AND downloadStatus IN (6, 8)
            AND finalizationSource != '' AND finalizationTarget != ''"""
    )
    fun retryYtDlpFinalization(id: String, token: String, logPath: String): Int

    @Query(
        """UPDATE ProgressInfo SET progressDownloaded = :downloaded,
            progressTotal = :total, fragmentsDownloaded = :fragDownloaded,
            fragmentsTotal = :fragTotal, downloadStatus = 3, infoLine = :infoLine,
            startedAt = CASE WHEN startedAt = 0 THEN :startedAt ELSE startedAt END,
            logPath = :logPath, isLive = :isLive
            WHERE id = :id AND executionToken = :token AND stopReason = 0
            AND downloadStatus IN (1, 2, 3, 4)"""
    )
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

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 12, finalizationSource = :source,
            finalizationTarget = :target, infoLine = 'Finalizing'
            WHERE id = :id AND executionToken = :token AND (
                (stopReason = 0 AND downloadStatus IN (1, 2, 3, 4))
                OR (stopReason = 3 AND downloadStatus = 10)
            )"""
    )
    fun claimYtDlpFinalization(id: String, token: String, source: String, target: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = :status, completedAt = :completedAt,
            progressDownloaded = CASE WHEN :status = 5 THEN progressTotal ELSE progressDownloaded END,
            lastError = :lastError, infoLine = :infoLine
            WHERE id = :id AND executionToken = :token AND downloadStatus = 12"""
    )
    fun commitYtDlpFinalization(
        id: String,
        token: String,
        status: Int,
        completedAt: Long,
        lastError: String,
        infoLine: String
    ): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 6, completedAt = :completedAt,
            lastError = :error, infoLine = :error
            WHERE id = :id AND executionToken = :token AND stopReason = 0
            AND downloadStatus IN (1, 2, 3, 4)"""
    )
    fun commitYtDlpError(id: String, token: String, completedAt: Long, error: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 7, infoLine = :infoLine
            WHERE id = :id AND executionToken = :token AND downloadStatus = 10
            AND stopReason = 1"""
    )
    fun commitYtDlpPause(id: String, token: String, infoLine: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 9, completedAt = :completedAt,
            infoLine = 'Canceled'
            WHERE id = :id AND executionToken = :token AND downloadStatus = 11
            AND stopReason = 2"""
    )
    fun commitYtDlpCanceled(id: String, token: String, completedAt: Long): Int

    @Query("DELETE FROM ProgressInfo WHERE id = :id AND executionToken = :token AND downloadStatus = 9")
    fun deleteCommittedYtDlpCanceled(id: String, token: String): Int

    @Query(
        """UPDATE ProgressInfo SET downloadStatus = 10, stopReason = 1,
            executionToken = :token, queuedForLater = 0, removePartialOnCancel = 0,
            finalizationSource = '', finalizationTarget = '',
            infoLine = 'Pausing interrupted legacy download'
            WHERE id = :id AND executionToken = '' AND stopReason = 0
            AND downloadStatus IN (1, 2, 3, 4)"""
    )
    fun adoptLegacyYtDlpExecution(id: String, token: String): Int

    @Delete
    fun deleteProgressInfo(progressInfo: ProgressInfo)

    @Query("DELETE FROM ProgressInfo")
    fun clear()
}
