package com.myAllVideoBrowser.ui.main.player

import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding

/** One visibility/buffering policy for the fullscreen chrome and its real Media3 controller. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlayerChrome {
    fun bind(binding: FragmentPlayerBinding, player: Player, inPip: () -> Boolean = { false }) {
        binding.videoView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        binding.videoView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            binding.topBar.visibility = if (inPip()) View.GONE else visibility
        })
        fun updateBuffering() {
            binding.loadingBar.visibility = if (player.playbackState == Player.STATE_BUFFERING && !inPip()) View.VISIBLE else View.GONE
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = updateBuffering()
        })
        updateBuffering()
    }
}
