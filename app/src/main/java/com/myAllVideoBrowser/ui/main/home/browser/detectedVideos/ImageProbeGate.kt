package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks media identities that are currently being probed.
 *
 * The detected list is only updated after a probe completes, so two scans racing on
 * the same URL can both observe "not detected yet" and both issue a HEAD/Range
 * request. Holding the identity for the duration of the probe closes that window:
 * the first caller wins and every other caller for the same identity is skipped.
 */
internal class ImageProbeGate {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Returns false when another caller already owns [identity]. */
    fun tryAcquire(identity: String): Boolean {
        if (identity.isBlank()) {
            // Without an identity there is nothing to deduplicate on; let the probe run.
            return true
        }
        return inFlight.add(identity)
    }

    fun release(identity: String) {
        if (identity.isNotBlank()) {
            inFlight.remove(identity)
        }
    }
}
