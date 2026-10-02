package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import java.lang.ref.WeakReference

/**
 * Reconnects the process-restorable detected-media panel with the runtime-only
 * dependencies owned by its host WebTabFragment.
 *
 * After a process death the panel fragment is restored by FragmentManager while
 * the host's [VideoDetectionTabViewModel] and [DownloadTabListener] are plain
 * fields that only exist once the host is rebuilt. The panel and the host are
 * restored independently, so neither can assume the other exists first: each
 * side registers as it comes back and whichever arrives second binds the panel.
 *
 * References are weak so a closed tab or a dismissed panel cannot leak.
 */
object DetectedMediaPanelRegistry {

    /** Runtime dependencies a host can lend to a restored panel. */
    interface Host {
        val detectedMediaTabViewModel: VideoDetectionTabViewModel?
        val detectedMediaTabListener: DownloadTabListener?
    }

    /** Restorable side that receives the host dependencies once available. */
    interface Panel {
        /** True while the panel is attached and can still accept dependencies. */
        val panelAttached: Boolean

        fun applyRuntimeDependencies(
            model: VideoDetectionTabViewModel?,
            listener: DownloadTabListener?
        )
    }

    private val hosts = HashMap<Int, WeakReference<Host>>()
    private val panels = HashMap<Int, WeakReference<Panel>>()

    fun registerHost(tabIndex: Int, host: Host) {
        hosts[tabIndex] = WeakReference(host)
        panels[tabIndex]?.get()?.let { panel -> bind(panel, host) }
    }

    fun unregisterHost(tabIndex: Int) {
        hosts.remove(tabIndex)
    }

    fun registerPanel(tabIndex: Int, panel: Panel) {
        panels[tabIndex] = WeakReference(panel)
        hosts[tabIndex]?.get()?.let { host -> bind(panel, host) }
    }

    fun unregisterPanel(tabIndex: Int, panel: Panel) {
        if (panels[tabIndex]?.get() === panel) {
            panels.remove(tabIndex)
        }
    }

    fun resolveHost(tabIndex: Int): Host? = hosts[tabIndex]?.get()

    private fun bind(panel: Panel, host: Host) {
        if (!panel.panelAttached) return
        panel.applyRuntimeDependencies(
            host.detectedMediaTabViewModel,
            host.detectedMediaTabListener
        )
    }
}
