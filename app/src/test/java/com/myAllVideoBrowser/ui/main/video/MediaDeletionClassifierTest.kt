package com.myAllVideoBrowser.ui.main.video

import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaDeletionClassifierTest {

    @Test
    fun batchDelete_managedAndLegacyPublic_areBothPublic() {
        val managed = video(1, "content://media/external/downloads/1", "a.mp4")
        val legacy = video(2, "content://media/external/downloads/2", "b.mp4")
        val privateVideo = video(3, "file:///tmp/private/c.mp4", "c.mp4")
        val publicUris = setOf(managed.uri, legacy.uri)

        val split = MediaDeletionClassifier.classify(
            listOf(managed, legacy, privateVideo),
        ) { uri -> uri in publicUris }

        assertEquals(listOf(managed.uri, legacy.uri), split.publicUris)
        assertEquals(listOf(privateVideo), split.privateVideos)
    }

    private fun video(id: Long, uri: String, name: String) =
        LocalVideo(id, Uri.parse(uri), name)
}
