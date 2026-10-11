package com.myAllVideoBrowser.util

/**
 * Budget for the WebView state that is handed to the Android saved-state bundle.
 *
 * The full history is kept in memory ([ui.main.home.browser.webTab.WebTab]) and on disk
 * ([BrowserWebViewStateStore], 1 MiB per tab). The copy inside the activity saved state travels
 * through a Binder transaction that Android caps at roughly 1 MiB for the whole activity, so only a
 * small snapshot may go there. Larger histories are skipped: the restore path then falls back to the
 * in-memory state and finally to the durable snapshot, so nothing is lost.
 */
object BrowserSavedStateBudget {

    /**
     * Conservative per-tab ceiling. At most 3 live tabs can contribute to one saved state (see
     * `BrowserFragment.MAX_LIVE_WEB_TABS`), so the worst case stays well below the Binder budget.
     * The disk limit ([BrowserWebViewStateStore.MAX_SNAPSHOT_BYTES]) is deliberately much higher:
     * it protects a file, not a transacted bundle.
     */
    const val MAX_SYSTEM_WEBVIEW_STATE_BYTES = 192 * 1024

    /** An unmeasurable (null) or empty state is never handed to the system. */
    fun allowsSystemBundle(stateSizeBytes: Int?): Boolean =
        stateSizeBytes != null && stateSizeBytes in 1..MAX_SYSTEM_WEBVIEW_STATE_BYTES
}
