package io.legado.features.adapter

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.legado.features.api.video.*
import io.legado.features.media3.Media3Pipeline
import io.legado.features.media3.Media3Captions
import okhttp3.OkHttpClient

object Media3Features {
    const val available = true
    private val captions = Media3Captions()
    fun captionView(context: Context) = captions.view(context)
    fun attachCaptions(player: ExoPlayer) = captions.attach(player)
    fun clearCaptions() = captions.clear()
    fun source(context: Context, base: MediaSource, request: PlaybackRequest?, client: OkHttpClient) = Media3Pipeline.source(context, base, request, client)
    fun enhance(player: ExoPlayer, mode: VideoEnhancement) = Media3Pipeline.enhance(player, mode)
    fun selectAudio(player: ExoPlayer, request: PlaybackRequest?) = Media3Pipeline.selectExternalAudio(player, request)
}
