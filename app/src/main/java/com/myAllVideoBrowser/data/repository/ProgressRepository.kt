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
}
