package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import android.content.Intent
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import dagger.android.DaggerBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class NotificationReceiver : DaggerBroadcastReceiver() {
    @Inject
    lateinit var progressRepository: ProgressRepository

    @Inject
    lateinit var downloadQueueManager: DownloadQueueManager

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                    val taskId = intent.getStringExtra(TASK_ID)
                    if (taskId.isNullOrBlank()) {
                        AppLogger.w("Notification action ignored: missing task id")
                        return@withTimeout
                    }
                    val progressInfo = progressRepository.getProgressInfos()
                        .timeout(RECEIVER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        .blockingFirst()
                        .firstOrNull { it.id == taskId }

                    AppLogger.d("Notification action ${intent.action} for $taskId: $progressInfo")

                    if (progressInfo == null) {
                        AppLogger.w("Notification action ignored: task $taskId was not found")
                        return@withTimeout
                    }

                    when (intent.action) {
                        ACTION_PAUSE -> downloadQueueManager.pause(progressInfo.id)
                        ACTION_RESUME -> downloadQueueManager.resume(progressInfo.id)
                        ACTION_CANCEL -> downloadQueueManager.cancel(progressInfo.id, true)
                        else -> AppLogger.w("Notification action is not supported: ${intent.action}")
                    }
                }
            } catch (error: Throwable) {
                AppLogger.e("Notification action failed: ${intent.action}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val RECEIVER_TIMEOUT_MILLIS = 9_000L
        const val TASK_ID = "TASK_ID"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
    }
}
