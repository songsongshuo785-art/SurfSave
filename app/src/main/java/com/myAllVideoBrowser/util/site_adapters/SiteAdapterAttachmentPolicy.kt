package com.myAllVideoBrowser.util.site_adapters

/**
 * 站点适配器补出来的清晰度该挂到哪条候选上。
 *
 * 为什么不能“挂到最新那条候选”了事：KVS 页面上的候选来自网络嗅探，除了播放器自己的媒体，
 * 还可能是广告、推荐位。把播放器清单挂到广告候选上，用户点 1080p 会下到错的东西。
 * 因此优先用 **URL 重叠** 做关联（适配器清单里必然包含正在播放的那条 URL），
 * 只有在“页面上只有一条候选、不存在歧义”时才允许无重叠挂载，其余情况一律不猜。
 *
 * 纯函数，便于单测；页面/世代竞态由调用方（ViewModel 的 generation 校验）负责。
 */
internal sealed class SiteAdapterAttachment {
    /** [overlap] 为 false 表示没有 URL 重叠，仅因“页面只有一条候选”才挂载。 */
    data class Attach(val videoId: String, val overlap: Boolean) : SiteAdapterAttachment()

    /** 页面上还没有候选：先不挂，等候选出现。 */
    object NoCandidate : SiteAdapterAttachment()

    /** 多条候选且无 URL 重叠：无法判断清单属于哪条，放弃挂载（宁可少功能，也不挂错）。 */
    object Ambiguous : SiteAdapterAttachment()
}

internal data class SiteAdapterCandidate(
    val videoId: String,
    /** 该候选已知的媒体地址身份（已归一化）。 */
    val normalizedUrls: Set<String>
)

internal object SiteAdapterAttachmentPolicy {

    fun decide(
        candidates: List<SiteAdapterCandidate>,
        adapterUrls: Set<String>,
        preferredVideoId: String?
    ): SiteAdapterAttachment {
        if (candidates.isEmpty()) {
            return SiteAdapterAttachment.NoCandidate
        }

        val overlapping = candidates.filter { candidate ->
            candidate.normalizedUrls.any { it in adapterUrls }
        }
        if (overlapping.isNotEmpty()) {
            val preferred = overlapping.firstOrNull { it.videoId == preferredVideoId }
            if (preferred != null) {
                return SiteAdapterAttachment.Attach(preferred.videoId, overlap = true)
            }
            if (overlapping.size == 1) {
                return SiteAdapterAttachment.Attach(overlapping.first().videoId, overlap = true)
            }
            // 多条候选都命中同一个清单：无法区分，放弃。
            return SiteAdapterAttachment.Ambiguous
        }

        if (candidates.size == 1) {
            return SiteAdapterAttachment.Attach(candidates.first().videoId, overlap = false)
        }
        return SiteAdapterAttachment.Ambiguous
    }
}
