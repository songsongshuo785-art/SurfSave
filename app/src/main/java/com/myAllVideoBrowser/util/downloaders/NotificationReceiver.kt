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
import javax.inject.Inject

class NotificationReceiver : DaggerBroadcastReceiver() {
    @Inject
    lateinit var progressRepository: ProgressRepository

    @Inject
    lateinit var downloadQueueManager: DownloadQueueManager

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val taskId = intent.extras?.getString(TASK_ID)
        receiverScope.launch {
            val progressInfo = progressRepository.getProgressInfos().blockingFirst()
                .firstOrNull { it.id == taskId }

            AppLogger.d("-----------------------------------   $taskId  $progressInfo")

            if (progressInfo == null) {
                return@launch
            }

            when (intent.action) {
                ACTION_PAUSE -> {
                    downloadQueueManager.pause(progressInfo.id)
                }

                ACTION_RESUME -> {
                    downloadQueueManager.resume(progressInfo.id)
                }

                ACTION_CANCEL -> {
                    downloadQueueManager.cancel(progressInfo.id, true)
                }

                else -> {
                    AppLogger.d("ACTION NOT SUPPORTED ${intent.action}")
                }
            }
        }
    }

    companion object {
        const val TASK_ID = "TASK_ID"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
    }
}
