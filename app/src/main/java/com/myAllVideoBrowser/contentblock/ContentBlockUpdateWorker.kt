package com.myAllVideoBrowser.contentblock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ContentBlockUpdateWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    lateinit var manager: ContentBlockManager

    override suspend fun doWork(): Result {
        if (!manager.isEnabled()) return Result.success()
        manager.initialize()
        return when (contentBlockUpdateOutcome(manager.updateRules())) {
            ContentBlockUpdateOutcome.SUCCESS -> Result.success()
            ContentBlockUpdateOutcome.RETRY -> Result.retry()
            ContentBlockUpdateOutcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "surfsave-content-block-rules-v1"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ContentBlockUpdateWorker>(
                4,
                TimeUnit.DAYS
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

internal enum class ContentBlockUpdateOutcome {
    SUCCESS,
    RETRY,
    FAILURE
}

internal fun contentBlockUpdateOutcome(result: FilterUpdateResult): ContentBlockUpdateOutcome {
    return when (result) {
        is FilterUpdateResult.Updated,
        is FilterUpdateResult.NotModified -> ContentBlockUpdateOutcome.SUCCESS
        is FilterUpdateResult.Failed -> when (result.reason) {
            FilterUpdateFailure.NETWORK,
            FilterUpdateFailure.HTTP -> ContentBlockUpdateOutcome.RETRY
            else -> ContentBlockUpdateOutcome.FAILURE
        }
    }
}
