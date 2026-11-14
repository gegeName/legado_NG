package io.legado.app.integration.manga

import android.content.Context
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.integration.image.NgOriginalImageResources
import io.legado.app.utils.GSON
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.features.adapter.MangaReaderFeatures
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.manga.DisplayItem
import io.legado.features.api.manga.ReaderSessionKey
import io.legado.features.api.host.ResourcePriority
import kotlinx.coroutines.flow.toList
import splitties.init.appCtx

object NgMangaReaderBridge {
    private val imageDownloads = MangaReaderFeatures.ImageDownloads()
    val navigation = MangaReaderFeatures.Navigation()
    private val config = object : ConfigPort {
        private val prefs get() = appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE)
        override fun get(namespace: String, key: String): String? = prefs.getString("$namespace:$key", null)
        override fun put(namespace: String, key: String, value: String?) { prefs.edit().putString("$namespace:$key", value).apply() }
    }
    var preferences: MangaReaderFeatures.Preferences
        get() = MangaReaderFeatures.preferences(config, AppConfig.preDownloadNum)
        set(value) = MangaReaderFeatures.savePreferences(config, value)
    fun chapterWindow(current: Int, total: Int) = preferences.let { MangaReaderFeatures.chapters(current, total, it.chapterAhead, it.chapterBehind) }
    fun retainedAhead() = preferences.chapterAhead.coerceAtLeast(1)
    fun updateImageWindow(book: Book, source: BookSource?, current: Int, total: Int, epoch: Long,
                          newlyAvailableChapter: Int? = null) {
        val snapshot = book.copy()
        if (hasExhaustedImageQuota(snapshot.intro)) {
            imageDownloads.clear()
            return
        }
        val capturedSource = source?.let { GSON.fromJson(GSON.toJson(it), BookSource::class.java) }
        val generation = NgOriginalImageResources.generation(book.bookUrl)
        imageDownloads.update(ReaderSessionKey(book.bookUrl, book.origin, epoch), current, total, newlyAvailableChapter,
            chapters = { appDb.bookChapterDao.getChapterList(snapshot.bookUrl).map { it.index } },
            images = { index ->
                val chapter = appDb.bookChapterDao.getChapter(snapshot.bookUrl, index)
                val content = chapter?.let { BookHelp.getContent(snapshot, it) }
                if (chapter == null || content.isNullOrEmpty()) null
                else BookHelp.flowImages(chapter, content).toList()
            },
            download = { url ->
                NgOriginalImageResources.resolve(snapshot, capturedSource, url, ResourcePriority.PREFETCH,
                    generation = generation).use { }
            },
            report = { chapter, error ->
                val title = chapter?.let { appDb.bookChapterDao.getChapter(snapshot.bookUrl, it)?.title }
                AppLog.put(if (title == null) "漫画已缓存章节图片队列失败" else "漫画章节原图预取失败：$title", error)
            })
    }
    private fun hasExhaustedImageQuota(intro: String?): Boolean {
        val quota = Regex("图片读取/上限\\s*[:：]\\s*(\\d+)\\s*/\\s*(\\d+)").find(intro.orEmpty()) ?: return false
        val used = quota.groupValues[1].toLongOrNull() ?: return false
        val limit = quota.groupValues[2].toLongOrNull() ?: return false
        return limit > 0 && used >= limit
    }
    fun stopImages() {
        imageDownloads.clear()
        NgMangaTranslationBridge.stopBackground()
    }
    fun invalidateBook(bookId: String) {
        navigation.reset()
        NgMangaPdfBridge.cancelBook(bookId)
        stopImages()
        NgMangaTranslationBridge.invalidateBook(bookId)
        NgOriginalImageResources.invalidateBook(bookId)
    }
    fun prefetchItems(items: List<DisplayItem?>, visible: Int, forward: Boolean, count: Int) =
        NgMangaLayout.prefetch(items, visible, forward, count)
    fun prefetch(pages: List<BaseMangaPage>, visible: Int, forward: Boolean, count: Int) =
        NgMangaLayout.prefetch(NgMangaLayout.prefetchItems(pages), visible, forward, count)
}
