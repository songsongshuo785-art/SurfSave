package com.myAllVideoBrowser.contentblock.nativebridge

internal object AdblockRustNative {
    const val DECISION_ALLOW = 0
    const val DECISION_BLOCK = 1
    const val DECISION_INVALID_REQUEST = 2
    const val DECISION_ENGINE_MISSING = 3

    init {
        System.loadLibrary("surfsave_content_block")
    }

    external fun nativeVersion(): String?
    external fun nativeCreate(filterLists: Array<String>, contextFree: Boolean): Long
    external fun nativeCreateFromSerialized(serialized: ByteArray): Long
    external fun nativeEvaluate(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): Int
    external fun nativeCosmeticResources(handle: Long, url: String): String?
    external fun nativeHiddenSelectors(
        handle: Long,
        classes: Array<String>,
        ids: Array<String>,
        exceptions: Array<String>
    ): String?
    external fun nativeSerialize(handle: Long): ByteArray?
    external fun nativeStats(handle: Long): String?
    external fun nativeDestroy(handle: Long)
}
