package com.myAllVideoBrowser.ui.main.video

data class BatchDeleteResult(
    val success: Int,
    val failed: Int,
)

/** 批量删除的成功/失败累加器，授权取消的公共项也计入 failed。 */
class BatchDeleteAccumulator {
    var success: Int = 0
        private set
    var failed: Int = 0
        private set

    fun onSuccess(count: Int = 1) {
        success += count
    }

    fun onFailure(count: Int = 1) {
        failed += count
    }

    fun toResult(): BatchDeleteResult = BatchDeleteResult(success, failed)
}
