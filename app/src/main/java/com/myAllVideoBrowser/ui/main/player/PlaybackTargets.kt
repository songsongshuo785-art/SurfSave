package com.myAllVideoBrowser.ui.main.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal data class PlaybackTarget(
    val label: String,
    val componentName: ComponentName?,
    val isDefault: Boolean
) {
    val isBuiltIn: Boolean
        get() = componentName == null
}

internal object PlaybackTargetOrdering {
    fun sorted(targets: List<PlaybackTarget>, locale: Locale = Locale.getDefault()): List<PlaybackTarget> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }
        return targets.sortedWith { left, right ->
            val labelComparison = collator.compare(left.label, right.label)
            if (labelComparison != 0) {
                labelComparison
            } else {
                left.componentName.orEmptyKey().compareTo(right.componentName.orEmptyKey())
            }
        }
    }

    private fun ComponentName?.orEmptyKey(): String = this?.flattenToString().orEmpty()
}

internal class PlaybackTargetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SharedPrefHelper.PREF_KEY,
        Context.MODE_PRIVATE
    )

    fun rememberedComponents(): Set<ComponentName> {
        val stored = preferences.getStringSet(KEY_REMEMBERED_COMPONENTS, emptySet()).orEmpty()
        val parsed = stored.mapNotNull(ComponentName::unflattenFromString).toSet()
        if (parsed.size != stored.size) {
            preferences.edit {
                putStringSet(KEY_REMEMBERED_COMPONENTS, parsed.mapTo(linkedSetOf()) {
                    it.flattenToString()
                })
            }
        }
        return parsed
    }

    fun defaultComponent(): ComponentName? {
        val stored = preferences.getString(KEY_DEFAULT_COMPONENT, null) ?: return null
        return ComponentName.unflattenFromString(stored) ?: run {
            preferences.edit { remove(KEY_DEFAULT_COMPONENT) }
            null
        }
    }

    fun rememberAndSetDefault(componentName: ComponentName) {
        val remembered = rememberedComponents().toMutableSet().apply { add(componentName) }
        preferences.edit {
            putStringSet(KEY_REMEMBERED_COMPONENTS, remembered.mapTo(linkedSetOf()) {
                it.flattenToString()
            })
            putString(KEY_DEFAULT_COMPONENT, componentName.flattenToString())
        }
    }

    fun setBuiltInAsDefault() {
        preferences.edit { remove(KEY_DEFAULT_COMPONENT) }
    }

    fun remove(componentName: ComponentName) {
        val remembered = rememberedComponents().toMutableSet().apply { remove(componentName) }
        preferences.edit {
            putStringSet(KEY_REMEMBERED_COMPONENTS, remembered.mapTo(linkedSetOf()) {
                it.flattenToString()
            })
            if (defaultComponent() == componentName) {
                remove(KEY_DEFAULT_COMPONENT)
            }
        }
    }

    companion object {
        private const val KEY_REMEMBERED_COMPONENTS = "PLAYBACK_TARGET_REMEMBERED_COMPONENTS"
        private const val KEY_DEFAULT_COMPONENT = "PLAYBACK_TARGET_DEFAULT_COMPONENT"
    }
}

internal object PlaybackTargetResolver {
    fun availableTargets(
        context: Context,
        store: PlaybackTargetStore,
        builtInLabel: String
    ): List<PlaybackTarget> {
        val resolvedExternalTargets = mutableListOf<PlaybackTarget>()
        store.rememberedComponents().forEach { componentName ->
            val target = resolve(context, componentName)
            if (target == null) {
                AppLogger.i("Removing unavailable playback target: ${componentName.flattenToShortString()}")
                store.remove(componentName)
            } else {
                resolvedExternalTargets += target
            }
        }
        val availableComponents = resolvedExternalTargets.mapNotNullTo(hashSetOf()) {
            it.componentName
        }
        val persistedDefault = store.defaultComponent()
        val defaultComponent = persistedDefault?.takeIf { it in availableComponents }
        if (persistedDefault != null && defaultComponent == null) {
            store.setBuiltInAsDefault()
        }
        val targets = mutableListOf(
            PlaybackTarget(
                label = builtInLabel,
                componentName = null,
                isDefault = defaultComponent == null
            )
        )
        targets += resolvedExternalTargets.map {
            it.copy(isDefault = it.componentName == defaultComponent)
        }
        return PlaybackTargetOrdering.sorted(targets)
    }

    fun resolve(
        context: Context,
        componentName: ComponentName,
        isDefault: Boolean = false
    ): PlaybackTarget? {
        val packageManager = context.packageManager
        val activityInfo = runCatching {
            getActivityInfo(packageManager, componentName)
        }.getOrNull() ?: return null
        if (!activityInfo.exported || !activityInfo.enabled || !activityInfo.applicationInfo.enabled) {
            return null
        }
        val label = activityInfo.loadLabel(packageManager).toString().ifBlank {
            componentName.packageName
        }
        return PlaybackTarget(label, componentName, isDefault)
    }

    private fun getActivityInfo(
        packageManager: PackageManager,
        componentName: ComponentName
    ): ActivityInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(
                componentName,
                PackageManager.ComponentInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(componentName, 0)
        }
    }
}

internal object ExternalPlaybackIntentFactory {
    private const val EXTERNAL_MEDIA_MIME_TYPE = "video/*"
    internal const val CHOSEN_TARGET_ACTION =
        "com.surfsave.browser.player.PLAYBACK_TARGET_CHOSEN"
    private val chooserRequestCode = AtomicInteger(1)

    fun createPlaybackIntent(
        mediaUrl: String,
        title: String,
        componentName: ComponentName? = null
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(mediaUrl.toUri(), EXTERNAL_MEDIA_MIME_TYPE)
        putExtra(Intent.EXTRA_TITLE, title)
        component = componentName
    }

    fun createChooserIntent(context: Context, target: Intent, title: String): Intent {
        val callbackIntent = Intent(context, PlaybackTargetChosenReceiver::class.java).apply {
            action = CHOSEN_TARGET_ACTION
        }
        var flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system chooser must append EXTRA_CHOSEN_COMPONENT to this explicit callback.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val callback = PendingIntent.getBroadcast(
            context,
            chooserRequestCode.getAndIncrement(),
            callbackIntent,
            flags
        )
        return Intent.createChooser(target, title, callback.intentSender)
    }

    fun isChosenTargetCallback(intent: Intent): Boolean = intent.action == CHOSEN_TARGET_ACTION
}

class PlaybackTargetChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ExternalPlaybackIntentFactory.isChosenTargetCallback(intent)) {
            return
        }
        val componentName = chosenComponent(intent)
        if (componentName == null) {
            AppLogger.w("System player chooser returned without a chosen component")
            return
        }
        // The private one-shot PendingIntent is invoked by the system chooser itself. Persist the
        // exact selection first; normal menu/default resolution will prune it if the app is later
        // uninstalled, disabled or changes its exported activity.
        PlaybackTargetStore(context).rememberAndSetDefault(componentName)
    }

    private fun chosenComponent(intent: Intent): ComponentName? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
    }
}
