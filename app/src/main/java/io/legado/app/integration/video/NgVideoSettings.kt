package io.legado.app.integration.video

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import io.legado.app.help.video.VideoBufferPolicy
import io.legado.app.help.video.VideoPlaybackBridge
import io.legado.app.model.VideoPlay
import io.legado.app.ui.design.components.compose.NgFormSelectOption
import io.legado.app.ui.video.VideoSettingsDivider
import io.legado.app.ui.video.VideoSettingsSection
import io.legado.app.ui.video.VideoSettingsSelectRow
import io.legado.features.adapter.Media3Features
import io.legado.features.api.video.VideoEnhancement

@Composable
internal fun NgVideoFeatureSettings(performanceContent: @Composable ColumnScope.() -> Unit = {}) {
    val resources by NgVideoBridge.resources.collectAsState()
    var enhancement by remember { mutableStateOf(NgVideoBridge.enhancement) }
    var buffer by remember { mutableIntStateOf(VideoPlay.bufferPolicy) }
    val selected = NgVideoBridge.selection()
    val lines = resources.distinctBy { it.lineId }
    val qualities = resources.filter { it.lineId == selected.lineId }
    val current = qualities.firstOrNull { it.title == selected.qualityTitle }
    if (Media3Features.available || lines.size > 1 || qualities.size > 1) {
        VideoSettingsSection("画面与音轨") {
            if (Media3Features.available) {
                VideoSettingsSelectRow("画面处理", enhancement.id.toString(),
                    VideoEnhancement.entries.map { NgFormSelectOption(it.title, it.id.toString()) }) {
                    enhancement = VideoEnhancement.fromId(it.toInt())
                    NgVideoBridge.enhancement = enhancement
                    val position = VideoPlaybackBridge.state.value.position
                    VideoPlay.durChapterPos = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    VideoPlay.startPlay(VideoPlaybackBridge)
                }
            }
            if (lines.size > 1) {
                if (Media3Features.available) VideoSettingsDivider()
                VideoSettingsSelectRow("播放线路", selected.lineId.orEmpty(),
                    lines.map { NgFormSelectOption(it.lineTitle, it.lineId) }, onValueChange = NgVideoBridge::selectLine)
            }
            if (qualities.size > 1) {
                if (Media3Features.available || lines.size > 1) VideoSettingsDivider()
                VideoSettingsSelectRow("清晰度", selected.qualityTitle.orEmpty(),
                    qualities.map { NgFormSelectOption(it.title, it.title) }, onValueChange = NgVideoBridge::selectQuality)
            }
            if (Media3Features.available) {
                if (!current?.subtitles.isNullOrEmpty()) {
                    VideoSettingsDivider()
                    VideoSettingsSelectRow("字幕", (selected.subtitle ?: -1).toString(),
                        listOf(NgFormSelectOption("关闭", "-1")) + current.subtitles.mapIndexed { i, track ->
                            NgFormSelectOption(track.language.ifBlank { "字幕 ${i + 1}" }, i.toString())
                        }) { NgVideoBridge.selectTracks(it.toInt().takeIf { i -> i >= 0 }, selected.audio) }
                }
                if (!current?.audio.isNullOrEmpty()) {
                    VideoSettingsDivider()
                    VideoSettingsSelectRow("音轨", (selected.audio ?: -1).toString(),
                        listOf(NgFormSelectOption("原始音轨", "-1")) + current.audio.mapIndexed { i, track ->
                            NgFormSelectOption(track.language.ifBlank { "音轨 ${i + 1}" }, i.toString())
                        }) { NgVideoBridge.selectTracks(selected.subtitle, it.toInt().takeIf { i -> i >= 0 }) }
                }
            }
        }
    }
    VideoSettingsSection("播放性能") {
        VideoSettingsSelectRow("播放缓冲", buffer.toString(),
            VideoBufferPolicy.entries.mapIndexed { i, p -> NgFormSelectOption(p.title, i.toString()) },
            summary = "下次加载视频时生效") { buffer = it.toInt(); VideoPlay.bufferPolicy = buffer }
        performanceContent()
    }
}
