package io.legado.app.integration.video

import android.content.Context
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.video.VideoPlaybackTarget
import io.legado.app.help.video.VideoPlaybackBridge
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.VideoPlay
import io.legado.app.utils.GSON
import io.legado.features.adapter.VideoFeatures
import io.legado.features.adapter.Media3Features
import io.legado.features.api.source.VideoResource
import io.legado.features.api.video.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import splitties.init.appCtx
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

object NgVideoBridge {
    private val features = VideoFeatures()
    private val variants = MutableStateFlow<List<VideoResource>>(emptyList())
    val resources = variants.asStateFlow()
    @Volatile private var media: PlaybackRequest? = null
    private val prefs get() = appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE)
    var enhancement: VideoEnhancement
        get() = VideoEnhancement.fromId(prefs.getInt("video:enhancement", 0))
        set(value) { prefs.edit().putInt("video:enhancement", value.id).apply() }
    @Synchronized fun begin(target: VideoPlaybackTarget): VideoPlaybackTarget {
        val episode = listOf(VideoPlay.book?.bookUrl, VideoPlay.source?.getKey(), VideoPlay.durVolumeIndex, VideoPlay.chapterInVolumeIndex, if (VideoPlay.singleUrl) VideoPlay.videoUrl else "").joinToString(":")
        variants.value = emptyList(); media = null
        return Guarded(target, features.begin(episode), episode)
    }
    private class Guarded(val target: VideoPlaybackTarget, val revision: Long, val episode: String) : VideoPlaybackTarget {
        var request: PlaybackRequest? = null
        override var seekOnStart: Long
            get() = target.seekOnStart
            set(value) { synchronized(NgVideoBridge) { if (features.current(revision)) target.seekOnStart = value } }
        override var mapHeadData: Map<String, String>?
            get() = target.mapHeadData
            set(value) { synchronized(NgVideoBridge) { if (features.current(revision)) target.mapHeadData = features.headers(value.orEmpty(), request?.headers.orEmpty()) } }
        override fun setUp(url: String?, cache: Boolean, cachePath: File?, title: String?): Boolean = synchronized(NgVideoBridge) {
            if (!features.current(revision)) return@synchronized false
            media = request?.copy(url = url.orEmpty(), headers = target.mapHeadData.orEmpty())
            target.setUp(url, cache, cachePath, title)
        }
        override fun startPlayLogic() { synchronized(NgVideoBridge) { if (features.current(revision)) target.startPlayLogic() } }
        override fun loading() { synchronized(NgVideoBridge) { if (features.current(revision)) target.loading() } }
        override fun loadError(message: String) { synchronized(NgVideoBridge) { if (features.current(revision)) target.loadError(message) } }
    }
    suspend fun resolve(target: VideoPlaybackTarget, chapter: BookChapter, source: io.legado.app.data.entities.BookSource, book: io.legado.app.data.entities.Book): PlaybackRequest? {
        val guarded = target as? Guarded ?: return null
        if (!features.current(guarded.revision)) throw kotlinx.coroutines.CancellationException("视频解析会话已失效")
        val json = chapter.variableMap["legadoFeatures.videos"] ?: return null
        var resources = GSON.fromJson(json, Array<VideoResource>::class.java).toList()
        val request = features.resolveRefreshing(guarded.revision, guarded.episode, resources) {
            io.legado.app.integration.source.NgExtensionSourceBridge.content(source, book, chapter, save = false)
            GSON.fromJson(chapter.variableMap["legadoFeatures.videos"], Array<VideoResource>::class.java).toList().also { resources = it }
        } ?: throw kotlinx.coroutines.CancellationException("视频解析会话已失效")
        return synchronized(this) {
            if (!features.current(guarded.revision)) throw kotlinx.coroutines.CancellationException("视频解析会话已失效")
            guarded.request = request
            variants.value = resources
            request
        }
    }
    fun selectQuality(title: String) {
        val position = VideoPlaybackBridge.state.value.position
        features.select(features.selected().lineId, title, position)
        VideoPlay.durChapterPos = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        VideoPlay.startPlay(VideoPlaybackBridge)
    }
    fun selectedQuality() = features.selected().qualityTitle
    fun selection() = features.selected()
    fun selectLine(line: String) {
        features.select(line, null, VideoPlaybackBridge.state.value.position)
        restart()
    }
    fun selectTracks(subtitle: Int?, audio: Int?) {
        features.tracks(subtitle, audio, VideoPlaybackBridge.state.value.position)
        restart()
    }
    private fun restart() {
        VideoPlay.durChapterPos = features.selected().position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        VideoPlay.startPlay(VideoPlaybackBridge)
    }
    fun currentRequest() = media
    fun selectAudio(player: ExoPlayer, request: PlaybackRequest?) = Media3Features.selectAudio(player, request)
    fun source(context: Context, base: MediaSource, request: PlaybackRequest?) = Media3Features.source(context, base, request, okHttpClient)
    fun enhance(player: ExoPlayer): Boolean {
        Media3Features.attachCaptions(player)
        return Media3Features.enhance(player, enhancement)
    }
    @Synchronized fun invalidate() { features.invalidate(); variants.value = emptyList(); media = null; Media3Features.clearCaptions() }
}
