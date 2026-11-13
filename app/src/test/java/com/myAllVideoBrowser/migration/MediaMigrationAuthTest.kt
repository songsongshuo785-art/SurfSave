package com.myAllVideoBrowser.migration

import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaMigrationAuthTest {

    @Test
    fun migrateBatch_Rplus_usesVerificationUriForBatchDelete() {
        val mediaUri = Uri.parse("content://media/external/downloads/1")
        val pending = pending(retryUri = null, verificationUri = mediaUri)

        assertEquals(
            listOf(mediaUri),
            MediaMigrationAuth.batchDeleteUris(listOf(pending)),
        )
    }

    @Test
    fun rejectsNonMediaStoreUris() {
        val pending = pending(
            retryUri = null,
            verificationUri = Uri.parse("content://com.example.provider/1"),
        )

        assertTrue(MediaMigrationAuth.batchDeleteUris(listOf(pending)).isEmpty())
    }

    private fun pending(retryUri: Uri?, verificationUri: Uri) = PendingMigration(
        video = LocalVideo(1, Uri.parse("file:///tmp/private/foo.mp4"), "foo.mp4"),
        target = StorageTarget.PRIVATE_INTERNAL,
        targetUri = Uri.parse("file:///tmp/private/target/foo.mp4"),
        retryUri = retryUri,
        verificationUri = verificationUri,
    )
}
