package io.legado.features.video

import io.legado.features.api.video.*
import io.legado.features.api.source.VideoResource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PlaybackResourcesExpiredException : IllegalStateException("视频资源已过期，请重新解析")

class VideoSession {
    private var generation = 0L
    private var episode = ""
    private var selection = PlaybackSelection()
    @Synchronized fun begin(episodeId: String): Long {
        if (episode != episodeId) { episode = episodeId; selection = PlaybackSelection(episodeId) }
        return ++generation
    }
    @Synchronized fun current(revision: Long) = generation == revision
    @Synchronized fun invalidate() { generation++; episode = ""; selection = PlaybackSelection() }
    @Synchronized fun select(lineId: String?, qualityTitle: String?, position: Long) {
        selection = selection.copy(episodeId = episode, lineId = lineId, qualityTitle = qualityTitle, position = position.coerceAtLeast(0))
    }
    @Synchronized fun tracks(subtitle: Int?, audio: Int?, position: Long) {
        selection = selection.copy(subtitle = subtitle?.coerceAtLeast(0), audio = audio?.coerceAtLeast(0), position = position.coerceAtLeast(0))
    }
    @Synchronized fun resolve(revision: Long, result: VideoResolveResult, now: Long): PlaybackRequest? {
        if (!current(revision) || result.episodeId != episode) return null
        val lines = result.lines.map { line -> line.copy(resources = line.resources.filter { resource -> resource.expiresAt?.let { it > now } != false }) }.filter { line ->
            line.resources.isNotEmpty() && line.expiresAt?.let { expires -> expires > now } != false
        }
        if (lines.isEmpty()) {
            if (result.lines.any { it.resources.isNotEmpty() }) throw PlaybackResourcesExpiredException()
            error("视频资源为空")
        }
        val line = lines.firstOrNull { it.id == selection.lineId } ?: lines.first()
        val quality = line.resources.indexOfFirst { it.title == selection.qualityTitle }.takeIf { it >= 0 }
            ?: line.resources.indexOfFirst { it.preferred }.takeIf { it >= 0 } ?: 0
        val media = line.resources[quality]
        require(media.url.isNotBlank()) { "播放地址为空" }
        selection = selection.copy(lineId = line.id, qualityTitle = media.title)
        return PlaybackRequest(result.episodeId, line.id, quality, media.url, media.headers.toMap(),
            selection.subtitle?.let { media.subtitles.getOrNull(it)?.let(::listOf) }.orEmpty(),
            selection.audio?.let { media.audio.getOrNull(it)?.let(::listOf) }.orEmpty(), selection.position)
    }
    @Synchronized fun selected() = selection
    /** One refresh per resolve. A stale session never starts another source request. */
    suspend fun resolveRefreshing(revision: Long, result: VideoResolveResult,
        now: () -> Long = System::currentTimeMillis, refresh: suspend () -> VideoResolveResult): PlaybackRequest? {
        return try { resolve(revision, result, now()) }
        catch (expired: PlaybackResourcesExpiredException) {
            currentCoroutineContext().ensureActive()
            if (!current(revision)) return null
            val updated = refresh()
            currentCoroutineContext().ensureActive()
            resolve(revision, updated, now())
        }
    }
}

object VideoCatalog {
    fun variants(episode: String, resources: List<VideoResource>): VideoResolveResult =
        VideoResolveResult(episode, resources.groupBy { it.lineId }.map { (id, media) -> VideoLine(id, media.first().lineTitle, media) })
    /** Source headers are authoritative per resource; no headers from a previous quality survive. */
    fun headers(base: Map<String, String>, media: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for ((name, value) in base + media) {
            result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
            result[name] = value
        }
        return result
    }
}
