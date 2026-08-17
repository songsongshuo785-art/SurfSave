package com.myAllVideoBrowser.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

internal data class ContentLengthProbe(
    val length: Long?,
    val mediaStoreReportedSize: Long?,
    val descriptorSize: Long?,
    val assetDescriptorSize: Long?,
    val countedSize: Long?
)

internal data class ContentLengthSources(
    val queryReportedSize: () -> Long?,
    val queryDescriptorSize: () -> Long?,
    val queryAssetDescriptorSize: () -> Long?,
    val openInputStream: () -> InputStream?
)

/**
 * Resolves the actual length behind a content URI without treating a newly-written
 * MediaStore SIZE=0 value as proof that the file is empty.
 */
internal object ContentLengthResolver {
    fun resolve(context: Context, uri: Uri): ContentLengthProbe {
        val resolver = context.contentResolver
        return resolve(
            ContentLengthSources(
                queryReportedSize = { queryReportedSize(resolver, uri) },
                queryDescriptorSize = {
                    resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.statSize.takeIf { it >= 0L }
                    }
                },
                queryAssetDescriptorSize = {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.length.takeIf { it >= 0L }
                    }
                },
                openInputStream = { resolver.openInputStream(uri) }
            )
        )
    }

    internal fun resolve(sources: ContentLengthSources): ContentLengthProbe {
        val reportedSize = runCatching(sources.queryReportedSize).getOrNull()
        val descriptorSize = runCatching(sources.queryDescriptorSize).getOrNull()
        val assetDescriptorSize = runCatching(sources.queryAssetDescriptorSize).getOrNull()

        val knownPositiveLength = sequenceOf(
            descriptorSize,
            assetDescriptorSize
        ).filterNotNull().firstOrNull { it > 0L }

        val countedSize = if (knownPositiveLength == null) {
            runCatching {
                sources.openInputStream()?.use(::countBytes)
            }.getOrNull()
        } else {
            null
        }

        val resolvedLength = knownPositiveLength
            ?: countedSize
            ?: sequenceOf(descriptorSize, assetDescriptorSize)
                .filterNotNull()
                .firstOrNull()

        return ContentLengthProbe(
            length = resolvedLength,
            mediaStoreReportedSize = reportedSize,
            descriptorSize = descriptorSize,
            assetDescriptorSize = assetDescriptorSize,
            countedSize = countedSize
        )
    }

    private fun queryReportedSize(resolver: ContentResolver, uri: Uri): Long? {
        return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeColumn)) {
                cursor.getLong(sizeColumn).takeIf { it >= 0L }
            } else {
                null
            }
        }
    }

    private fun countBytes(input: InputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (read == 0) {
                if (input.read() < 0) return total
                total += 1
            } else {
                total += read
            }
        }
    }
}
