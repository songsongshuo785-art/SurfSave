package com.myAllVideoBrowser.ui.component

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Stateful player fixture: actual Media3 control events, without a decoder or network. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PreviewPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var value = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build())
        .setPlaylist(listOf(MediaItemData.Builder("preview").setDurationUs(600_000_000).setIsSeekable(true).build()))
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .setContentPositionMs(92_000L)
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
    override fun getState(): State = value
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        value = value.buildUpon().setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }
    fun buffering(buffering: Boolean) {
        value = value.buildUpon().setPlaybackState(if (buffering) Player.STATE_BUFFERING else Player.STATE_READY).build()
        invalidateState()
    }
}
