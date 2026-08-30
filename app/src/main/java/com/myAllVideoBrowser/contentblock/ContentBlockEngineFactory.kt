package com.myAllVideoBrowser.contentblock

import com.myAllVideoBrowser.contentblock.nativebridge.AdblockRustEngine
import javax.inject.Inject

interface ContentBlockEngineFactory {
    fun fromLists(filterLists: List<String>, contextFree: Boolean): ContentBlockEngine

    fun fromSerialized(serialized: ByteArray): ContentBlockEngine
}

class NativeContentBlockEngineFactory @Inject constructor() : ContentBlockEngineFactory {
    override fun fromLists(
        filterLists: List<String>,
        contextFree: Boolean
    ): ContentBlockEngine = AdblockRustEngine.fromLists(filterLists, contextFree)

    override fun fromSerialized(serialized: ByteArray): ContentBlockEngine =
        AdblockRustEngine.fromSerialized(serialized)
}
