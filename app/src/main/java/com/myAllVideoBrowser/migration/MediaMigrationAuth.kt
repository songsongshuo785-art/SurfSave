package com.myAllVideoBrowser.migration

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/** 批量迁移时，从待授权记录里挑出可以交给 MediaStore.createDeleteRequest 的 URI。 */
object MediaMigrationAuth {
    fun batchDeleteUris(pendings: List<PendingMigration>): List<Uri> =
        pendings
            // Android 11+ 的 deleteMedia 返回 retryUri = null、verificationUri = content URI；
            // Android 10 两者都会填。优先 retryUri，再退回 verificationUri。
            .map { it.retryUri ?: it.verificationUri }
            .filter {
                it.scheme == ContentResolver.SCHEME_CONTENT &&
                    it.authority == MediaStore.AUTHORITY
            }
            .distinct()
}
