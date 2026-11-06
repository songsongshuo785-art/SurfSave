package com.myAllVideoBrowser.ui.main.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAuthQueueTest {

    @Test
    fun drainsThreePendings() {
        val queue = BatchAuthQueue<String>()
        queue.reset(listOf("A", "B", "C"))

        assertEquals("A", queue.peek())
        assertEquals("A", queue.poll())
        assertEquals("B", queue.poll())
        assertEquals("C", queue.poll())
        assertNull(queue.poll())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun cancelCountsRemainingAsSourceNotDeleted() {
        val queue = BatchAuthQueue<String>()
        queue.reset(listOf("A", "B", "C"))
        val accumulator = BatchMigrationAccumulator()

        // 第一个授权成功
        queue.poll()
        // 第二个授权被拒绝后用户取消：剩余两个都算旧文件保留
        accumulator.sourceNotDeleted += queue.size

        assertEquals(2, accumulator.sourceNotDeleted)
        assertEquals(0, accumulator.failed)
    }
}
