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
}
