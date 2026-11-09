package io.legado.features.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.*
import io.legado.features.api.video.*
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
object Media3Pipeline {
    fun selectExternalAudio(player: ExoPlayer, request: PlaybackRequest?) {
        if (request?.audio.isNullOrEmpty()) return
        val source = 1 + request!!.subtitles.distinct().size
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val external = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.mediaTrackGroup.id.startsWith("$source:") } ?: return
                player.removeListener(this)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(external.mediaTrackGroup, 0)).build()
            }
        })
    }
    fun subtitleMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
    fun source(context: Context, base: MediaSource, request: PlaybackRequest?, client: OkHttpClient): MediaSource {
        if (request == null || request.subtitles.isEmpty() && request.audio.isEmpty()) return base
        val data = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client).setDefaultRequestProperties(request.headers))
        val factory = DefaultMediaSourceFactory(data)
        val sources = mutableListOf(base)
        request.subtitles.distinct().forEachIndexed { index, track ->
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(subtitleMime(track.url)).setLanguage(track.language)
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
            @Suppress("DEPRECATION")
            sources += SingleSampleMediaSource.Factory(data).createMediaSource(subtitle, C.TIME_UNSET)
        }
        request.audio.distinct().forEach { track ->
            sources += factory.createMediaSource(MediaItem.Builder().setUri(track.url).setMediaId("audio:${track.language}:${track.url}").build())
        }
        return MergingMediaSource(true, false, *sources.toTypedArray())
    }
    fun enhance(player: ExoPlayer, mode: VideoEnhancement): Boolean = try {
        VideoEnhanceHelper.applyEnhanceMode(player, VideoEnhanceHelper.EnhanceMode.fromId(mode.id)); true
    } catch (_: Exception) {
        player.setVideoEffects(emptyList()); false
    }
}
