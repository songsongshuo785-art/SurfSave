package com.myAllVideoBrowser.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentLengthResolverTest {
    @Test
    fun descriptorSizeTakesPriorityOverOtherLengthSources() {
        val result = ContentLengthResolver.resolve(
            sources(
                reportedSize = { 99L },
                descriptorSize = { 12L },
                assetDescriptorSize = { 24L },
                bytes = byteArrayOf(1, 2, 3)
            )
        )

        assertEquals(12L, result.length)
        assertEquals(12L, result.descriptorSize)
        assertEquals(24L, result.assetDescriptorSize)
        assertNull(result.countedSize)
    }

    @Test
    fun zeroLengthDescriptorsFallBackToCountingInputStream() {
        val result = ContentLengthResolver.resolve(
            sources(
                reportedSize = { 0L },
                descriptorSize = { 0L },
                assetDescriptorSize = { 0L },
                bytes = byteArrayOf(1, 2, 3, 4, 5)
            )
        )

        assertEquals(5L, result.length)
        assertEquals(5L, result.countedSize)
    }

    @Test
    fun reportedSizeQueryFailureStillUsesDescriptorLength() {
        val result = ContentLengthResolver.resolve(
            sources(
                reportedSize = { throw SecurityException("query denied") },
                descriptorSize = { 7L },
                assetDescriptorSize = { null },
                bytes = byteArrayOf(1)
            )
        )

        assertEquals(7L, result.length)
        assertNull(result.mediaStoreReportedSize)
        assertNull(result.countedSize)
    }

    private fun sources(
        reportedSize: () -> Long?,
        descriptorSize: () -> Long?,
        assetDescriptorSize: () -> Long?,
        bytes: ByteArray
    ) = ContentLengthSources(
        queryReportedSize = reportedSize,
        queryDescriptorSize = descriptorSize,
        queryAssetDescriptorSize = assetDescriptorSize,
        openInputStream = { ByteArrayInputStream(bytes) }
    )
}
