package io.legado.features.tvbox.sniff

/** Port of legado help/exoplayer/M3u8AdFilter: live manifests keep their sliding window intact. */
object LegadoM3u8AdFilter {
    private val keywords = listOf("guanggao", "ad.ts", "ad_", "_ad.", "/ad/", "notice.ts", "tuiguang",
        "advert", "promo", "sponsor", "v-ad", "banner", "googleads", "g.doubleclick")

    fun filter(text: String, log: (String) -> Unit = {}): String {
        if (!text.contains("#EXTM3U") || !text.contains("#EXT-X-ENDLIST")) return text
        val lines = text.lines()
        val result = StringBuilder()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#EXTINF:")) {
                val next = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (keywords.any(next.lowercase()::contains)) {
                    log("视频动态广告拦截：自动切除点播 M3U8 广告切片 [$next]")
                    index += 2
                    continue
                }
            }
            result.append(line).append('\n')
            index++
        }
        return result.toString()
    }
}
