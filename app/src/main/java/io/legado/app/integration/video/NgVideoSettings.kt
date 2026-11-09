package io.legado.app.integration.video

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import io.legado.app.R
import io.legado.app.ui.design.components.compose.*
import io.legado.app.help.gsyVideo.VideoPlaybackBridge
import io.legado.app.model.VideoPlay
import io.legado.features.api.video.VideoEnhancement

@Composable fun NgVideoFeatureSettings() {
    val resources by NgVideoBridge.resources.collectAsState()
    var enhancement by remember { mutableStateOf(NgVideoBridge.enhancement) }
    val arrow = painterResource(R.drawable.ic_ng_spinner_arrow_down)
    val selected = NgVideoBridge.selection()
    val lines = resources.distinctBy { it.lineId }
    if (lines.size > 1) NgFormSelectField("线路", selected.lineId.orEmpty(), lines.map { NgFormSelectOption(it.lineTitle, it.lineId) }, NgVideoBridge::selectLine, arrowIcon = arrow)
    val qualities = resources.filter { it.lineId == selected.lineId }
    if (qualities.size > 1) NgFormSelectField("清晰度", NgVideoBridge.selectedQuality().orEmpty(), qualities.map { NgFormSelectOption(it.title, it.title) },
        { NgVideoBridge.selectQuality(it) }, arrowIcon = arrow)
    val current = qualities.firstOrNull { it.title == selected.qualityTitle }
    if (!io.legado.features.adapter.Media3Features.available) return
    if (!current?.subtitles.isNullOrEmpty()) NgFormSelectField("字幕", (selected.subtitle ?: -1).toString(),
        listOf(NgFormSelectOption("关闭", "-1")) + current!!.subtitles.mapIndexed { i, track -> NgFormSelectOption(track.language.ifBlank { "字幕 ${i + 1}" }, i.toString()) },
        { NgVideoBridge.selectTracks(it.toInt().takeIf { i -> i >= 0 }, selected.audio) }, arrowIcon = arrow)
    if (!current?.audio.isNullOrEmpty()) NgFormSelectField("音轨", (selected.audio ?: -1).toString(),
        listOf(NgFormSelectOption("原始音轨", "-1")) + current!!.audio.mapIndexed { i, track -> NgFormSelectOption(track.language.ifBlank { "音轨 ${i + 1}" }, i.toString()) },
        { NgVideoBridge.selectTracks(selected.subtitle, it.toInt().takeIf { i -> i >= 0 }) }, arrowIcon = arrow)
    NgFormSelectField("画面处理", enhancement.id.toString(), VideoEnhancement.entries.map { NgFormSelectOption(it.title, it.id.toString()) }, {
        enhancement = VideoEnhancement.fromId(it.toInt())
        NgVideoBridge.enhancement = enhancement
        val position = VideoPlaybackBridge.state.value.position
        VideoPlay.durChapterPos = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        VideoPlay.startPlay(VideoPlaybackBridge)
    }, arrowIcon = arrow)
}
