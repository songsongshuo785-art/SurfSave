package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaUrlIdentityTest {
    @Test
    fun hostAndSchemeCaseAreNormalizedButPathCaseIsNot() {
        assertEquals(
            "https://cdn.example/Photo.JPG",
            MediaUrlIdentity.of("HTTPS://CDN.Example/Photo.JPG")
        )
    }

    @Test
    fun pathCaseIsSignificant() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/A.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg")
        )
    }

    @Test
    fun queryValueCaseIsSignificant() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?id=ABC123"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?id=abc123")
        )
    }

    @Test
    fun schemeIsKept() {
        assertNotEquals(
            MediaUrlIdentity.of("http://cdn.example/a.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg")
        )
    }

    @Test
    fun wwwHostIsKept() {
        assertNotEquals(
            MediaUrlIdentity.of("https://www.cdn.example/a.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg")
        )
    }

    @Test
    fun explicitPortIsKept() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example:8443/a.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg")
        )
    }

    @Test
    fun temporaryQueryKeysAreDroppedByLowercaseKeyButSurvivingValuesKeepCase() {
        assertEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?id=XYZ"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?TOKEN=abc&id=XYZ&Signature=deadbeef")
        )
        assertEquals(
            "https://cdn.example/a.jpg?id=XYZ",
            MediaUrlIdentity.of("https://cdn.example/a.jpg?TOKEN=abc&id=XYZ")
        )
    }

    @Test
    fun businessKeysThatMerelyContainSignatureKeyNamesAreKept() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?tokenId=AAA"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?tokenId=BBB")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?signatureStyle=dark"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?signatureStyle=light")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?expired=false"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?expired=true")
        )
    }

    @Test
    fun genericBusinessQueryKeysAreKept() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?key=AAA"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?key=BBB")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?hash=1"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?hash=2")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?policy=public"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?policy=private")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?e=1"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?e=2")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?sv=1"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?sv=2")
        )
    }

    @Test
    fun queryParameterOrderIsSignificant() {
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg?a=1&b=2"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg?b=2&a=1")
        )
    }

    @Test
    fun percentEncodingIsNotDecoded() {
        assertEquals(
            "https://cdn.example/a%2Fb.jpg",
            MediaUrlIdentity.of("https://cdn.example/a%2Fb.jpg")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a%2Fb.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a/b.jpg")
        )
    }

    @Test
    fun fragmentIsDroppedButTrailingSlashIsKept() {
        assertEquals(
            "https://cdn.example/a.jpg/",
            MediaUrlIdentity.of("https://cdn.example/a.jpg/#top")
        )
        assertNotEquals(
            MediaUrlIdentity.of("https://cdn.example/a.jpg"),
            MediaUrlIdentity.of("https://cdn.example/a.jpg/")
        )
    }

    @Test
    fun blankInputHasNoIdentity() {
        assertEquals("", MediaUrlIdentity.of(null))
        assertEquals("", MediaUrlIdentity.of(""))
        assertEquals("", MediaUrlIdentity.of("   "))
    }

    @Test
    fun unparseableOrHostlessInputFallsBackToTheTrimmedValue() {
        assertEquals("not a url", MediaUrlIdentity.of("not a url"))
        assertEquals("/relative/a.jpg", MediaUrlIdentity.of("/relative/a.jpg"))
    }
}
