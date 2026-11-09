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
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.features.adapter.MangaReaderFeatures
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.manga.PageKey
import io.legado.features.api.manga.ReaderPage
import io.legado.features.api.manga.ReaderSessionKey
import io.legado.features.api.host.ResourcePriority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import splitties.init.appCtx

object NgMangaReaderBridge {
    private val imageWindow = MangaReaderFeatures.ImageWindow()
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
    fun retainedAhead() = preferences.let { maxOf(1, it.chapterAhead, it.imageChapters) }
    fun updateImageWindow(book: Book, source: BookSource?, current: Int, total: Int, epoch: Long) {
        val snapshot = book.copy()
        val capturedSource = source?.let { GSON.fromJson(GSON.toJson(it), BookSource::class.java) }
        val session = ReaderSessionKey(book.bookUrl, book.origin, epoch)
        imageWindow.update(session, current, total, preferences.imageChapters, prefetch = { index ->
            val chapter = appDb.bookChapterDao.getChapter(snapshot.bookUrl, index)
            val content = chapter?.let { BookHelp.getContent(snapshot, it) }
            if (chapter == null || content.isNullOrEmpty()) false else {
                var images = 0
                BookHelp.flowImages(chapter, content).collect { url ->
                    currentCoroutineContext().ensureActive()
                    NgOriginalImageResources.resolve(snapshot, capturedSource, url, ResourcePriority.PREFETCH).use { }
                    images++
                }
                images > 0
            }
        }, report = { _, error -> AppLog.put("漫画章节原图预取失败", error) })
    }
    fun stopImages() { imageWindow.clear(); NgMangaTranslationBridge.stopBackground() }
    fun invalidateBook(bookId: String) {
        navigation.reset()
        NgMangaPdfBridge.cancelBook(bookId)
        stopImages()
        NgMangaTranslationBridge.invalidateBook(bookId)
        NgOriginalImageResources.invalidateBook(bookId)
    }
    fun prefetch(pages: List<BaseMangaPage>, visible: Int, forward: Boolean, count: Int) =
        NgMangaLayout.prefetch(pages, visible, forward, count)
}
