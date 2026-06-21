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

    @Delete
    fun deleteProgressInfo(progressInfo: ProgressInfo)

    @Query("DELETE FROM ProgressInfo")
    fun clear()
}
