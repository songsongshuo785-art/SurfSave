package com.myAllVideoBrowser.ui.main.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.SharedPrefHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackTargetsTest {
    private lateinit var context: Context
    private lateinit var store: PlaybackTargetStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
        store = PlaybackTargetStore(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun newStore_defaultsToBuiltInWithNoRememberedPlayers() {
        assertNull(store.defaultComponent())
        assertTrue(store.rememberedComponents().isEmpty())
    }

    @Test
    fun rememberAndSetDefault_roundTripsExactComponent() {
        val component = ComponentName("org.example.player", "org.example.player.PlayerActivity")

        store.rememberAndSetDefault(component)

        assertEquals(component, store.defaultComponent())
        assertEquals(setOf(component), store.rememberedComponents())
    }

    @Test
    fun removingCurrentExternalPlayer_restoresBuiltInDefault() {
        val component = ComponentName("org.example.player", "org.example.player.PlayerActivity")
        store.rememberAndSetDefault(component)

        store.remove(component)

        assertNull(store.defaultComponent())
        assertTrue(store.rememberedComponents().isEmpty())
    }

    @Test
    fun unavailableRememberedPlayer_isPrunedAndBuiltInBecomesDefault() {
        val missing = ComponentName("org.example.missing", "org.example.missing.PlayerActivity")
        store.rememberAndSetDefault(missing)

        val targets = PlaybackTargetResolver.availableTargets(context, store, "SurfSave")

        assertEquals(1, targets.size)
        assertTrue(targets.single().isBuiltIn)
        assertTrue(targets.single().isDefault)
        assertNull(store.defaultComponent())
        assertTrue(store.rememberedComponents().isEmpty())
    }

    @Test
    fun targetOrdering_isAlphabeticalAndCaseInsensitive() {
        val targets = listOf(
            target("zeta", "org.example.zeta"),
            target("Alpha", "org.example.alpha"),
            target("beta", "org.example.beta")
        )

        val sorted = PlaybackTargetOrdering.sorted(targets, Locale.US)

        assertEquals(listOf("Alpha", "beta", "zeta"), sorted.map { it.label })
    }

    @Test
    fun externalPlaybackIntent_containsOnlyPublicPlaybackMetadata() {
        val component = ComponentName("org.example.player", "org.example.player.PlayerActivity")

        val intent = ExternalPlaybackIntentFactory.createPlaybackIntent(
            mediaUrl = "https://cdn.example/video.m3u8?token=temporary",
            title = "Example",
            componentName = component
        )

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://cdn.example/video.m3u8?token=temporary", intent.dataString)
        assertEquals("video/*", intent.type)
        assertEquals(component, intent.component)
        assertEquals("Example", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(setOf(Intent.EXTRA_TITLE), intent.extras?.keySet())
        assertFalse(intent.hasExtra("Cookie"))
        assertFalse(intent.hasExtra("Authorization"))
    }

    @Test
    fun chooserWrapsTheExternalPlaybackIntent() {
        val target = ExternalPlaybackIntentFactory.createPlaybackIntent(
            mediaUrl = "https://cdn.example/video.mp4",
            title = "Example"
        )

        val chooser = ExternalPlaybackIntentFactory.createChooserIntent(
            context,
            target,
            "Choose player"
        )

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(target, chooserTarget(chooser))
    }

    @Test
    fun chooserCallback_remembersAndSelectsTheChosenComponent() {
        val chosen = ComponentName(context, MainActivity::class.java)
        val callback = Intent(ExternalPlaybackIntentFactory.CHOSEN_TARGET_ACTION).apply {
            putExtra(Intent.EXTRA_CHOSEN_COMPONENT, chosen)
        }

        PlaybackTargetChosenReceiver().onReceive(context, callback)

        assertEquals(chosen, store.defaultComponent())
        assertEquals(setOf(chosen), store.rememberedComponents())
    }

    private fun target(label: String, packageName: String): PlaybackTarget = PlaybackTarget(
        label = label,
        componentName = ComponentName(packageName, "$packageName.PlayerActivity"),
        isDefault = false
    )

    private fun chooserTarget(chooser: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            chooser.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    private fun preferences() = context.getSharedPreferences(
        SharedPrefHelper.PREF_KEY,
        Context.MODE_PRIVATE
    )
}
