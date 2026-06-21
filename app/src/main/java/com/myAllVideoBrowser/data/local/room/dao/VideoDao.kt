package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import io.reactivex.rxjava3.core.Maybe

@Dao
interface VideoDao {

    @Query("SELECT * FROM VideoInfo WHERE originalUrl = :url")
    fun getVideoById(url: String): Maybe<VideoInfo>

    @Query("SELECT * FROM VideoInfo")
    fun getAllVideos(): List<VideoInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertVideo(videoInfo: VideoInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(videos: List<VideoInfo>)

    @Query("DELETE FROM VideoInfo")
    fun clear()
}
