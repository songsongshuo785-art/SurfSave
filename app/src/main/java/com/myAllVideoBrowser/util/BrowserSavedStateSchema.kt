package com.myAllVideoBrowser.util

/**
 * Marker that tells a current-format fragment saved state apart from a legacy one.
 *
 * Before the size cap the WebView state was written straight into the fragment view bundle, so
 * "no snapshot key" used to mean "legacy bundle, parse the whole thing". With the cap in place the
 * same absence means "the snapshot was skipped because it exceeded
 * [BrowserSavedStateBudget.MAX_SYSTEM_WEBVIEW_STATE_BYTES]". The marker is written on every save,
 * so both cases stay distinguishable.
 */
object BrowserSavedStateSchema {

    /** Key of the marker inside the fragment saved state. */
    const val KEY = "webview_state_schema"

    /** Format written by this build. */
    const val CURRENT = 1

    fun isCurrentFormat(markerValue: Int): Boolean = markerValue >= CURRENT
}
