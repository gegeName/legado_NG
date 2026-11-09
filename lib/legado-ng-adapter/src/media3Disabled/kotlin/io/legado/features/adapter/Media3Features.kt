package io.legado.features.adapter

import android.content.Context
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.legado.features.api.video.*
import okhttp3.OkHttpClient

@Suppress("UNUSED_PARAMETER")
object Media3Features {
    const val available = false
    fun captionView(context: Context): View = View(context)
    fun attachCaptions(player: ExoPlayer) = Unit
    fun clearCaptions() = Unit
    fun source(context: Context, base: MediaSource, request: PlaybackRequest?, client: OkHttpClient): MediaSource = base
    fun enhance(player: ExoPlayer, mode: VideoEnhancement) = mode == VideoEnhancement.NONE
    fun selectAudio(player: ExoPlayer, request: PlaybackRequest?) = Unit
}
