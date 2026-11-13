package com.myAllVideoBrowser.ui.main.video

import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo

/** 把批量删除目标拆成“需要 MediaStore 授权的公共 URI”和“可直接删除的私有视频”。 */
object MediaDeletionClassifier {
    data class Split(
        val publicUris: List<Uri>,
        val privateVideos: List<LocalVideo>,
    )

    fun classify(
        videos: List<LocalVideo>,
        isPublic: (Uri) -> Boolean,
    ): Split {
        val publicUris = mutableListOf<Uri>()
        val privateVideos = mutableListOf<LocalVideo>()
        videos.forEach { video ->
            if (isPublic(video.uri)) {
                publicUris += video.uri
            } else {
                privateVideos += video
            }
        }
        return Split(publicUris, privateVideos)
    }
}
