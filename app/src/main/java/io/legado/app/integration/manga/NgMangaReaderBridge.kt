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
import io.legado.features.api.manga.PageKey
import io.legado.features.api.manga.DisplayItem
import io.legado.features.api.manga.ReaderPage
import io.legado.features.api.manga.ReaderSessionKey
import io.legado.features.api.host.ResourcePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx

object NgMangaReaderBridge {
    private const val imageDownloadConcurrency = 3
    private val imageDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageDownloadRequests = Channel<Unit>(Channel.CONFLATED)
    private val completedImageChapters = mutableSetOf<Int>()
    private val imageDownloadLock = Any()
    private var imageDownloadSession: ReaderSessionKey? = null
    private var imageDownloadJob: Job? = null
    private var lastImageDownloadChapter = Int.MIN_VALUE
    private var imageDownloadStartChapter: Int? = null
    private var imageDownloadStopped = false
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
        val capturedSource = source?.let { GSON.fromJson(GSON.toJson(it), BookSource::class.java) }
        val session = ReaderSessionKey(book.bookUrl, book.origin, epoch)
        val shouldScan = synchronized(imageDownloadLock) {
            if (imageDownloadSession != session) {
                imageDownloadJob?.cancel()
                completedImageChapters.clear()
                lastImageDownloadChapter = Int.MIN_VALUE
                imageDownloadStartChapter = null
                imageDownloadStopped = false
                imageDownloadSession = session
            }
            val jumped = lastImageDownloadChapter != Int.MIN_VALUE &&
                kotlin.math.abs(current - lastImageDownloadChapter) > 1
            if (jumped) {
                imageDownloadStartChapter = current
                imageDownloadJob?.cancel()
                imageDownloadJob = null
            }
            val changed = current != lastImageDownloadChapter || newlyAvailableChapter != null
            lastImageDownloadChapter = current
            if (!imageDownloadStopped && imageDownloadJob?.isActive != true) {
                if (hasExhaustedImageQuota(snapshot.intro)) {
                    imageDownloadStopped = true
                    AppLog.put("漫画图片后台预取已暂停：书源图片额度已用尽")
                } else {
                    val startChapter = imageDownloadStartChapter
                    imageDownloadJob = imageDownloadScope.launch {
                        downloadCachedChapterImages(session, snapshot, capturedSource, startChapter)
                    }
                }
            }
            changed
        }
        if (shouldScan) imageDownloadRequests.trySend(Unit)
    }
    private fun hasExhaustedImageQuota(intro: String?): Boolean {
        val quota = Regex("图片读取/上限\\s*[:：]\\s*(\\d+)\\s*/\\s*(\\d+)").find(intro.orEmpty()) ?: return false
        val used = quota.groupValues[1].toLongOrNull() ?: return false
        val limit = quota.groupValues[2].toLongOrNull() ?: return false
        return limit > 0 && used >= limit
    }
    private suspend fun downloadCachedChapterImages(
        session: ReaderSessionKey,
        book: Book,
        source: BookSource?,
        startChapter: Int?
    ) {
        val permits = Semaphore(imageDownloadConcurrency)
        while (currentCoroutineContext().isActive) {
            var failed = false
            try {
                val allChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                val chapters = startChapter?.let { start -> allChapters.filter { it.index >= start } } ?: allChapters
                for (chapter in chapters) {
                    currentCoroutineContext().ensureActive()
                    if (synchronized(imageDownloadLock) {
                            imageDownloadSession != session || chapter.index in completedImageChapters
                        }) continue
                    if (!BookHelp.hasContent(book, chapter)) continue
                    val content = BookHelp.getContent(book, chapter) ?: continue
                    val urls = BookHelp.flowImages(chapter, content).toList().distinct()
                    try {
                        for (batch in urls.chunked(imageDownloadConcurrency)) {
                            coroutineScope {
                                batch.map { url ->
                                    async {
                                        permits.withPermit {
                                            currentCoroutineContext().ensureActive()
                                            NgOriginalImageResources.resolve(book, source, url, ResourcePriority.PREFETCH).use { }
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                        synchronized(imageDownloadLock) {
                            if (imageDownloadSession == session) completedImageChapters.add(chapter.index)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e.message?.contains("图片下载后校验失败") == true) {
                            synchronized(imageDownloadLock) {
                                if (imageDownloadSession == session) imageDownloadStopped = true
                            }
                            AppLog.put("漫画图片后台预取已暂停：图片接口返回了无效内容", e)
                            return
                        }
                        failed = true
                        AppLog.put("漫画章节原图预取失败：${chapter.title}", e)
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                AppLog.put("漫画已缓存章节图片队列失败", e)
            }
            if (failed) delay(30_000) else imageDownloadRequests.receive()
        }
    }
    fun stopImages() {
        synchronized(imageDownloadLock) {
            imageDownloadJob?.cancel()
            imageDownloadJob = null
            imageDownloadSession = null
            completedImageChapters.clear()
            lastImageDownloadChapter = Int.MIN_VALUE
            imageDownloadStartChapter = null
            imageDownloadStopped = false
        }
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
