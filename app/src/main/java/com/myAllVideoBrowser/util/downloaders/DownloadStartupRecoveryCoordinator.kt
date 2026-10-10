package com.myAllVideoBrowser.util.downloaders

import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlRecoveryCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** 启动对账时，一个「状态是 ACTIVE 但已经没有对应 Worker」的遗留任务该怎么处理。 */
internal enum class OrphanRecoveryDecision {
    /** 放回 PENDING，恢复调度后自动续跑。 */
    RESUME,

    /** 用户当时按的是暂停，恢复成已暂停，不再自动起。 */
    MARK_PAUSED,

    /** 用户当时按的是取消，收尾成已取消（不替用户删文件）。 */
    MARK_CANCELED,

    /** 收尾成失败：状态机里本不该出现的遗留态，显式暴露而不是静默改状态。 */
    MARK_ERROR,

    /** 不需要对账（PENDING / PAUSE / 各种终态）。 */
    SKIP
}

/**
 * 纯函数，便于单测：不发网络、不落库、不看 WorkManager。
 *
 * 判据只按“用户当时的意图”还原：
 * - PREPARE / START / DOWNLOADING / PROXYREADY：当时在下载 → 自动续跑
 * - PAUSING：当时在暂停 → 已暂停
 * - CANCELING：当时在取消 → 已取消
 * - FINALIZING：发布最终文件被中断（Custom/SuperX 不存在这个态）→ 失败
 */
internal fun orphanRecoveryDecision(status: Int): OrphanRecoveryDecision = when (status) {
    VideoTaskState.PREPARE,
    VideoTaskState.START,
    VideoTaskState.DOWNLOADING,
    VideoTaskState.PROXYREADY -> OrphanRecoveryDecision.RESUME

    VideoTaskState.PAUSING -> OrphanRecoveryDecision.MARK_PAUSED
    VideoTaskState.CANCELING -> OrphanRecoveryDecision.MARK_CANCELED
    VideoTaskState.FINALIZING -> OrphanRecoveryDecision.MARK_ERROR
    else -> OrphanRecoveryDecision.SKIP
}

/**
 * 启动恢复的唯一入口（在 [com.myAllVideoBrowser.DLApplication] 里调用一次）。
 *
 * 拆成两段的原因：yt-dlp 有自己的执行令牌、临时文件与终止态语义，必须由
 * [YoutubeDlRecoveryCoordinator] 处理；Custom / SuperX 没有任何恢复入口，
 * 由 [DownloadQueueManager.reconcileOrphans] 对账。两者都不在这里重复启动同一个任务。
 */
@Singleton
class DownloadStartupRecoveryCoordinator @Inject constructor(
    private val youtubeDlRecoveryCoordinator: YoutubeDlRecoveryCoordinator,
    private val downloadQueueManager: DownloadQueueManager
) {
    fun recover() {
        youtubeDlRecoveryCoordinator.recover()
        downloadQueueManager.reconcileOrphans()
    }
}
