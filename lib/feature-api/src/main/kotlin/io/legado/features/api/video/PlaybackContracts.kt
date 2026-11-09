package io.legado.features.api.video

import io.legado.features.api.source.MediaTrack
import io.legado.features.api.source.VideoResource

data class VideoLine(val id: String, val title: String, val resources: List<VideoResource>, val expiresAt: Long? = null)
data class VideoResolveResult(val episodeId: String, val lines: List<VideoLine>)
data class PlaybackRequest(val episodeId: String, val lineId: String, val quality: Int, val url: String,
    val headers: Map<String, String>, val subtitles: List<MediaTrack>, val audio: List<MediaTrack>, val position: Long)
data class PlaybackSelection(val episodeId: String = "", val lineId: String? = null, val qualityTitle: String? = null, val position: Long = 0,
    val subtitle: Int? = 0, val audio: Int? = null)
enum class VideoEnhancement(val id: Int, val title: String) {
    NONE(0, "原画"), AMD_CAS(1, "CAS 锐化"), ANIME_4K(2, "动漫线条"), SHADOW_BOOST(3, "暗部提亮"), SMART_COMBO(4, "锐化与提亮"), LANCZOS_3(5, "Lanczos-3 缩放");
    companion object { fun fromId(id: Int) = entries.find { it.id == id } ?: NONE }
}
