package com.myAllVideoBrowser.ui.main.home.browser

import android.app.Application
import com.myAllVideoBrowser.util.AppLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserAdFilterRuleProvider @Inject constructor(
    private val application: Application
) {
    val filter: BrowserAdFilter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val bundledLines = application.assets
                .open(BUNDLED_RULES_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readLines() }
            val localFile = localRulesFile()
            if (!localFile.isFile) {
                BrowserAdFilter.fromLines(bundledLines.asSequence())
            } else {
                runCatching {
                    localFile.bufferedReader(Charsets.UTF_8).use { localReader ->
                        BrowserAdFilter.fromLines(
                            bundledLines.asSequence() + localReader.lineSequence()
                        )
                    }
                }.getOrElse { error ->
                    AppLogger.e(
                        "Custom ad filter rules ignored: ${error.javaClass.simpleName}"
                    )
                    BrowserAdFilter.fromLines(bundledLines.asSequence())
                }
            }
        }.getOrElse { error ->
            AppLogger.e("Ad filter rules unavailable: ${error.javaClass.simpleName}")
            BrowserAdFilter.empty()
        }
    }

    private fun localRulesFile(): File = File(application.filesDir, LOCAL_RULES_FILE)

    companion object {
        const val BUNDLED_RULES_ASSET = "adblock_rules_v1.txt"
        const val LOCAL_RULES_FILE = "adblock_rules_custom_v1.txt"
    }
}
