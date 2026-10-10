package com.myAllVideoBrowser.util.media

/**
 * codec 证据判定（D2 与 A′ 共用，唯一实现）。
 *
 * 为什么必须区分「缺省值」和「明确证据」：
 * yt-dlp 对 html5/generic 这类「只返回一条媒体地址」的解析**根本不提供 codec 字段**，
 * 而 `VideoService` 会把缺失值落库成 `"none"`（Android `JSONObject.optString` 遇到 JSON null
 * 还会返回字符串 `"null"`，Gson 反序列化旧 JSON 时则是 Kotlin null）。
 *
 * 若把这种缺省值当成「video-only」的正面证据，就会给一条**完整的 MP4** 追加 `+bestaudio`，
 * yt-dlp 随即报 `Requested format is not available` —— 这是 Hanime1 `22554` 的真实失败路径
 * （`DIAG_EXEC formatSelector='1+bestaudio' videoOnly=true formatId='1'` 且 `vcodec=null`）。
 */
internal object FormatCodecEvidence {

    /** yt-dlp 未提供 codec 时的等价写法（空串 / JSON null 的字符串化 / 显式 none / unknown）。 */
    private val absentCodecValues = setOf("", "null", "none", "unknown")

    fun hasExplicitVideoCodec(vcodec: String?): Boolean =
        vcodec != null && vcodec.trim().lowercase() !in absentCodecValues

    fun hasExplicitAudioCodec(acodec: String?): Boolean =
        acodec != null && acodec.trim().lowercase() !in absentCodecValues

    /** 纯音频：沿用改动前的语义（`vcodec == "none"` 且 `acodec != "none"`），不引入新判据。 */
    fun isAudioOnly(vcodec: String?, acodec: String?): Boolean =
        vcodec == "none" && acodec != "none"

    /**
     * video-only：**只有**在 yt-dlp 明确给出视频 codec、且音轨被明确标成 `none` 时才成立。
     * 缺少视频 codec（未知/缺省）一律不算 —— 这正是 D2 要修掉的误判。
     */
    fun isVideoOnly(vcodec: String?, acodec: String?): Boolean =
        hasExplicitVideoCodec(vcodec) && acodec == "none"

    /**
     * yt-dlp `-f` 的取值。
     *
     * @return `null` 表示 audio-only 分支（调用方走 `--extract-audio`，不传 `-f`）。
     */
    fun formatSelector(formatId: String?, vcodec: String?, acodec: String?): String? {
        if (isAudioOnly(vcodec, acodec)) {
            return null
        }
        val id = formatId.orEmpty()
        return if (isVideoOnly(vcodec, acodec)) "$id+bestaudio" else id
    }
}
