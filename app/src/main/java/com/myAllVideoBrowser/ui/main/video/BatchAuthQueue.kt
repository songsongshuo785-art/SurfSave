package com.myAllVideoBrowser.ui.main.video

/** 批量迁移的最终汇总结果。 */
data class BatchMigrationResult(
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val sourceNotDeleted: Int,
)

/** 批量授权队列；Android 10 逐个授权时按顺序出队，Android 11+ 不走这里。 */
class BatchAuthQueue<T> {
    private val queue = ArrayDeque<T>()

    val size: Int get() = queue.size

    fun reset(items: List<T>) {
        queue.clear()
        queue.addAll(items)
    }

    fun peek(): T? = queue.firstOrNull()

    fun poll(): T? = queue.removeFirstOrNull()

    fun isEmpty(): Boolean = queue.isEmpty()
}
