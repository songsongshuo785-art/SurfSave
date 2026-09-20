package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import io.reactivex.rxjava3.core.Flowable

@Dao
interface BrowserFileDownloadDao {
    @Query(
        """SELECT * FROM BrowserFileDownload
            ORDER BY
                CASE WHEN status IN (1, 2, 3) THEN 0 ELSE 1 END,
                CASE WHEN status IN (1, 2, 3) THEN createdAt ELSE 0 END ASC,
                CASE WHEN status NOT IN (1, 2, 3) THEN completedAt ELSE 0 END DESC,
                id DESC"""
    )
    fun observeAll(): Flowable<List<BrowserFileDownload>>

    @Query("SELECT * FROM BrowserFileDownload ORDER BY id ASC")
    fun getAll(): List<BrowserFileDownload>

    @Query("SELECT * FROM BrowserFileDownload WHERE status IN (1, 2, 3) ORDER BY createdAt ASC")
    fun getActive(): List<BrowserFileDownload>

    @Query("SELECT * FROM BrowserFileDownload WHERE id = :id LIMIT 1")
    fun getById(id: Long): BrowserFileDownload?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(download: BrowserFileDownload): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(downloads: List<BrowserFileDownload>)

    @Update
    fun update(download: BrowserFileDownload)

    @Delete
    fun delete(download: BrowserFileDownload)

    @Query("DELETE FROM BrowserFileDownload")
    fun clear()
}
