package com.myAllVideoBrowser.ui.main.video

/** 批量迁移的累积结果；授权取消/源未删除只增加 sourceNotDeleted，不增加 failed。 */
class BatchMigrationAccumulator {
    var success: Int = 0
    var failed: Int = 0
    var skipped: Int = 0
    var sourceNotDeleted: Int = 0

    fun toResult(): BatchMigrationResult =
        BatchMigrationResult(success, failed, skipped, sourceNotDeleted)
}
