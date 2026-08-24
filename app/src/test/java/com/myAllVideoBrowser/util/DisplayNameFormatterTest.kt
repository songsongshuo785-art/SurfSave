package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameFormatterTest {

    @Test
    fun keepsDotsThatArePartOfAWebTitle() {
        assertEquals("Minecraft 1.20.4", DisplayNameFormatter.clean("Minecraft 1.20.4"))
    }

    @Test
    fun removesKnownMediaExtensionsIncludingDuplicates() {
        assertEquals("clip", DisplayNameFormatter.clean("clip.mp4.mp4"))
        assertEquals("clip", DisplayNameFormatter.clean("clip.MKV"))
    }

    @Test
    fun keepsUnknownExtensions() {
        assertEquals("archive.txt", DisplayNameFormatter.clean("archive.txt"))
    }
}
