package com.myAllVideoBrowser.ui.main.video

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDeleteAccumulatorTest {

    @Test
    fun privateSuccessPrivateFailurePublicCancelled() {
        val accumulator = BatchDeleteAccumulator()

        accumulator.onSuccess(2)   // 2 个私有成功
        accumulator.onFailure(1)   // 1 个私有失败
        accumulator.onFailure(3)   // 3 个公共授权取消，计入 failed

        assertEquals(BatchDeleteResult(success = 2, failed = 4), accumulator.toResult())
    }
}
