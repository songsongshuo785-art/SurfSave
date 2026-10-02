package com.myAllVideoBrowser.util

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpResponseSizesTest {

    @Test
    fun rangeProbePrefersContentRangeTotalOverPartialBody() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://cdn.example/image?id=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("Content-Range", "bytes 0-0/12345")
            .body("x".toResponseBody())
            .build()

        assertEquals(12345L, response.contentLengthOrUnknown())
    }

    @Test
    fun plainResponseUsesBodyLength() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://cdn.example/image?id=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("hello".toResponseBody())
            .build()

        assertEquals(5L, response.contentLengthOrUnknown())
    }

    @Test
    fun unknownRangeTotalFallsBackToPartialBodyLength() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://cdn.example/image?id=1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("Content-Range", "bytes 0-0/*")
            .body("x".toResponseBody())
            .build()

        assertEquals(1L, response.contentLengthOrUnknown())
    }
}
