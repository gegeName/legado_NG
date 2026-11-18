package io.legado.app.ui.book.manga

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 阅读会话内的图片几何，不随 LazyList item 回收；不保存 Bitmap 或写入书籍配置。 */
internal class MangaImageRatioCache(private val capacity: Int = 256) {
    private val ratios = LinkedHashMap<String, MutableStateFlow<Float>>(16, 0.75f, true)

    @Synchronized
    fun get(url: String): Float = ratios[url]?.value ?: 0f

    @Synchronized
    fun observe(url: String): StateFlow<Float> = entry(url)

    @Synchronized
    fun put(url: String, width: Int, height: Int, onlyIfUnknown: Boolean = false) {
        if (width <= 0 || height <= 0) return
        val ratio = entry(url)
        if (!onlyIfUnknown || ratio.value == 0f) ratio.value = width.toFloat() / height
    }

    private fun entry(url: String): MutableStateFlow<Float> = ratios.getOrPut(url) {
        MutableStateFlow(0f)
    }.also {
        if (ratios.size > capacity) ratios.remove(ratios.keys.first())
    }
}
