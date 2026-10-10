package com.myAllVideoBrowser.util

import android.os.Bundle
import android.os.Parcel

/**
 * Turns a `WebView.saveState()` Bundle into bytes and back.
 *
 * The byte format is a Parcel serialization. Android explicitly does not guarantee that a Parcel
 * stays readable across system/WebView versions, so the result is treated as an opaque, fragile
 * payload: every failure path returns `null` and the caller falls back to a plain URL load.
 */
interface WebViewStateCodec {
    fun encode(state: Bundle): ByteArray?

    fun decode(bytes: ByteArray): Bundle?
}

class ParcelWebViewStateCodec : WebViewStateCodec {
    override fun encode(state: Bundle): ByteArray? = runCatching {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    override fun decode(bytes: ByteArray): Bundle? = runCatching {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(BrowserWebViewStateStore::class.java.classLoader)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()
}
