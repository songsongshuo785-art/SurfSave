package com.myAllVideoBrowser.ui.main.migration

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.migration.MigrationManager
import com.myAllVideoBrowser.migration.MigrationOverview
import com.myAllVideoBrowser.migration.PrivateVideoMoveResult
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SingleLiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class MigrationCenterViewModel @Inject constructor(
    private val migrationManager: MigrationManager
) : BaseViewModel() {

    val overview = MutableLiveData<MigrationOverview>()
    val isWorking = MutableLiveData(false)
    val messageEvent = SingleLiveEvent<String>()
    val uninstallLegacyEvent = SingleLiveEvent<String>()

    override fun start() {
        refresh()
    }

    override fun stop() {
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            postWorking(false)
            runOnMain {
                overview.value = migrationManager.getOverview()
            }
        }
    }

    fun exportMigrationPackage(includeCookieContents: Boolean = false) {
        performAction(
            successMessage = "备份包已导出到共享下载目录。"
        ) {
            migrationManager.exportMigrationPackage(includeCookieContents)
        }
    }

    fun importMigrationPackage(packageUri: Uri? = null) {
        performAction(
            successMessage = "备份数据已经导入当前应用，请先核对后再处理旧应用和本地数据包。"
        ) {
            migrationManager.importMigrationPackage(packageUri)
        }
    }

    fun movePrivateVideosToSharedDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            postWorking(true)
            runCatching {
                migrationManager.movePrivateVideosToSharedDownloads()
            }.onSuccess { result ->
                runOnMain {
                    messageEvent.value = buildMoveMessage(result)
                    overview.value = migrationManager.getOverview()
                    isWorking.value = false
                }
            }.onFailure { error ->
                runOnMain {
                    messageEvent.value = error.message ?: "私有视频迁移失败。"
                    isWorking.value = false
                }
            }
        }
    }

    fun deleteMigrationPackage() {
        viewModelScope.launch(Dispatchers.IO) {
            postWorking(true)
            val deleted = migrationManager.deleteMigrationPackage()
            runOnMain {
                messageEvent.value = if (deleted) {
                    "本地备份包已删除。"
                } else {
                    "当前没有可删除的本地备份包。"
                }
                overview.value = migrationManager.getOverview()
                isWorking.value = false
            }
        }
    }

    fun requestUninstallLegacy() {
        val packageName = overview.value?.companionPackage ?: return
        uninstallLegacyEvent.value = packageName
    }

    private fun performAction(
        successMessage: String,
        action: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            postWorking(true)
            runCatching {
                action()
            }.onSuccess {
                runOnMain {
                    messageEvent.value = successMessage
                    overview.value = migrationManager.getOverview()
                    isWorking.value = false
                }
            }.onFailure { error ->
                runOnMain {
                    messageEvent.value = error.message ?: "迁移操作失败。"
                    isWorking.value = false
                }
            }
        }
    }

    private fun buildMoveMessage(result: PrivateVideoMoveResult): String {
        return when {
            result.movedCount == 0 && result.failedCount == 0 ->
                "当前没有需要迁移到公共下载目录的应用私有视频。"

            result.failedCount == 0 ->
                "已迁移 ${result.movedCount} 个应用私有视频到公共下载目录。"

            else ->
                "已迁移 ${result.movedCount} 个应用私有视频，失败 ${result.failedCount} 个。"
        }
    }

    private fun postWorking(isBusy: Boolean) {
        runOnMain {
            isWorking.value = isBusy
        }
    }
}
