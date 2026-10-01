package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks media identities that are currently being probed, per page generation.
 *
 * The detected list is only updated after a probe completes, so two scans racing on
 * the same URL can both observe "not detected yet" and both issue a HEAD/Range
 * request. Holding the identity for the duration of the probe closes that window:
 * the first caller wins and every other caller for the same identity is skipped.
 *
 * The page generation is part of the key. Navigation starts a new generation, and
 * a probe still in flight for the old page must never suppress the same image on
 * the new page: the old probe discards its result once its generation is stale, so
 * sharing the key across generations would leave the new page without the image
 * until a manual re-scan.
 */
internal class ImageProbeGate {
    internal data class Key(val pageGeneration: Long?, val identity: String)

    private val inFlight = ConcurrentHashMap.newKeySet<Key>()

    /** Returns false when another caller already owns this key. */
    fun tryAcquire(pageGeneration: Long?, identity: String): Boolean {
        if (identity.isBlank()) {
            // Without an identity there is nothing to deduplicate on; let the probe run.
            return true
        }
        return inFlight.add(Key(pageGeneration, identity))
    }

    fun release(pageGeneration: Long?, identity: String) {
        if (identity.isNotBlank()) {
            inFlight.remove(Key(pageGeneration, identity))
        }
    }
}
