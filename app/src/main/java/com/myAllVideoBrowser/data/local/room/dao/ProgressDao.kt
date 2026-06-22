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

    @Delete
    fun deleteProgressInfo(progressInfo: ProgressInfo)

    @Query("DELETE FROM ProgressInfo")
    fun clear()
}
