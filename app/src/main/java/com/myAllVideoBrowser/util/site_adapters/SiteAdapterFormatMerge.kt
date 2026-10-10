package com.myAllVideoBrowser.util.site_adapters

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity

/**
 * 站点适配器补出来的清晰度与既有格式的合并规则。纯函数，便于单测。
 *
 * 两条规则（对应评审约束 4）：
 * 1. **URL 相同就不新增行**：同一清晰度可能已经被网络嗅探/yt-dlp 记下来了（策略不同 ⇒ 身份不同），
 *    此时只把站点自己的标签补到既有行上，避免同一清晰度出现两行；策略/身份原样保留，
 *    谁的执行语义更可靠就继续用它。
 * 2. 其余条目追加，但身份重复（同一个清晰度被列了两次）的条目不重复添加。
 *
 * 这里刻意不判断“贵不贵”“分辨率高低”：只做去重与补标签，排序交给 `VideoFormatUi.sortFormats`。
 */
internal object SiteAdapterFormatMerge {

    data class Merged(
        val formats: List<VideoFormatEntity>,
        val addedCount: Int,
        val labelUpdateCount: Int
    ) {
        val changed: Boolean get() = addedCount > 0 || labelUpdateCount > 0
    }

    fun merge(
        existing: List<VideoFormatEntity>,
        incoming: List<VideoFormatEntity>,
        urlIdentity: (String?) -> String,
        identityOf: (VideoFormatEntity) -> String
    ): Merged {
        val result = existing.toMutableList()
        val identities = result.mapTo(mutableSetOf()) { identityOf(it) }
        var added = 0
        var labelsUpdated = 0

        incoming.forEach { candidate ->
            val candidateUrl = urlIdentity(candidate.url)
            val sameResourceIndex = if (candidateUrl.isBlank()) {
                -1
            } else {
                result.indexOfFirst { current ->
                    urlIdentity(current.url) == candidateUrl ||
                        urlIdentity(current.manifestUrl) == candidateUrl
                }
            }

            if (sameResourceIndex >= 0) {
                val current = result[sameResourceIndex]
                val enriched = current.copy(
                    format = current.format ?: candidate.format,
                    formatNote = current.formatNote ?: candidate.formatNote
                )
                if (enriched != current) {
                    result[sameResourceIndex] = enriched
                    labelsUpdated++
                }
                return@forEach
            }

            val identity = identityOf(candidate)
            if (!identities.add(identity)) {
                return@forEach
            }
            result += candidate
            added++
        }

        return Merged(formats = result, addedCount = added, labelUpdateCount = labelsUpdated)
    }
}
