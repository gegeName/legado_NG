package io.legado.features.mihon

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import io.legado.features.api.source.*
import io.legado.features.runtime.ExtensionRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class MihonProvider(
    private val resolve: suspend (Long) -> Source? = { ExtensionRuntime.awaitInitialized(); ExtensionRuntime.getSource(it) },
    private val list: suspend () -> List<Source> = { ExtensionRuntime.awaitInitialized(); ExtensionRuntime.getAllSources() },
) : SourceProvider {
    override val provider = "mihon"
    private data class UpdateLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<Pair<Long, String>, UpdateLock>()
    private suspend fun source(key: SourceKey): Source {
        require(key.provider == provider && key.contentKind == ContentKind.MANGA)
        currentCoroutineContext().ensureActive()
        return resolve(key.nativeId.toLong()) ?: error("漫画扩展未加载：${key.nativeId}")
    }
    override suspend fun sources() = list().map { s -> SourceDescriptor(SourceKey(provider, ContentKind.MANGA, s.id.toString()), s.name, (s as? CatalogueSource)?.lang.orEmpty(), ExtensionRuntime.isSourceEnabled(s.id), s.supportsLatest) }
    override suspend fun filters(source: SourceKey): FilterSession = MangaFilters(source, source(source).getFilterList())
    override suspend fun preferences(source: SourceKey): SourcePreferenceSession? {
        val configurable = source(source) as? eu.kanade.tachiyomi.source.ConfigurableSource ?: return null
        return withContext(Dispatchers.Main) {
            io.legado.features.runtime.ExtensionPreferences(io.legado.features.extensioncompat.ExtensionServices.require().application, source, configurable::setupPreferenceScreen)
        }
    }
    private fun filters(key: SourceKey, session: FilterSession): MangaFilters {
        require(session.source == key && session is MangaFilters) { "筛选会话与源不匹配" }
        return session
    }
    override suspend fun search(source: SourceKey, query: String, page: Int, filters: FilterSession): CatalogPage {
        require(page > 0)
        val s = source(source)
        return filters(source, filters).use { s.getSearchManga(page, query, it).catalog(source) }
    }
    override suspend fun discover(source: SourceKey, section: CatalogSection, page: Int, filters: FilterSession): CatalogPage {
        require(page > 0)
        val s = source(source)
        return filters(source, filters).use {
            when (section) {
                CatalogSection.POPULAR -> s.getPopularManga(page)
                CatalogSection.LATEST -> if (s.supportsLatest) s.getLatestUpdates(page) else MangasPage(emptyList(), false)
                CatalogSection.FILTER -> s.getSearchManga(page, "", it)
            }.catalog(source)
        }
    }
    private fun MangasPage.catalog(key: SourceKey) = CatalogPage(mangas.map { it.catalog(key) }, hasNextPage)
    private fun SManga.catalog(key: SourceKey) = CatalogItem(url, title, author, thumbnail_url?.let { PluginUris.image(ImageReference(key, 0, "", it)) }, description, genre, memo.toString())
    private fun CatalogItem.manga(key: SourceKey) = SManga.create().also {
        it.url = id; it.title = title; it.author = author; it.description = description; it.genre = genre
        it.thumbnail_url = cover?.let { url -> PluginUris.image(url)?.imageUrl ?: url }
        it.memo = metadata?.let { value -> Json.parseToJsonElement(value) as JsonObject } ?: JsonObject(emptyMap())
    }
    private suspend fun update(key: SourceKey, item: CatalogItem, details: Boolean, chapters: Boolean): SMangaUpdate {
        val s = source(key)
        val lockKey = s.id to item.id
        val lock = synchronized(locks) { locks.getOrPut(lockKey) { UpdateLock() }.also { it.users++ } }
        try { return lock.mutex.withLock { s.getMangaUpdate(item.manga(key), emptyList(), details, chapters) } }
        finally { synchronized(locks) { if (--lock.users == 0) locks.remove(lockKey, lock) } }
    }
    override suspend fun details(source: SourceKey, item: CatalogItem) = update(source, item, true, false).manga.catalog(source)
    override suspend fun chapters(source: SourceKey, item: CatalogItem) = update(source, item, false, true).chapters.mapIndexed { i, c -> ChapterDescriptor(c.url, c.name, i, c.chapter_number, c.date_upload, c.memo.toString()) }
    override suspend fun content(source: SourceKey, item: CatalogItem, chapter: ChapterDescriptor): ContentResult {
        val s = source(source)
        // Re-resolve the chapter to retain extension-specific fields and detect removed chapters.
        val c = update(source, item, false, true).chapters.firstOrNull { it.url == chapter.id } ?: error("章节已失效，请刷新目录")
        val pages = s.getPageList(c)
        check(pages.isNotEmpty()) { "扩展未返回图片" }
        return ContentResult.Images(pages.map { ImageReference(source, it.index, it.url, it.imageUrl) })
    }
    @OptIn(InternalCoroutinesApi::class)
    suspend fun openImage(ref: ImageReference): ImageStream {
        val s = source(ref.source) as? HttpSource ?: error("此扩展不支持 HTTP 图片")
        val context = currentCoroutineContext()
        val page = Page(ref.index, ref.pageUrl, ref.imageUrl)
        if (page.imageUrl.isNullOrBlank()) {
            check(page.url.isNotBlank()) { "扩展图片页地址为空" }
            page.imageUrl = s.getImageUrl(page)
        }
        val response = s.getImage(page)
        val registration = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) closeResponseAsync(response) }
        try {
            context.ensureActive()
            check(response.isSuccessful) { "图片 HTTP ${response.code}" }
            return object : ImageStream {
                override val stream = response.body.byteStream()
                override val length = response.body.contentLength()
                override fun close() { registration?.dispose(); closeResponseAsync(response) }
            }
        } catch (e: Throwable) { registration?.dispose(); closeResponseAsync(response); throw e }
    }

    private fun closeResponseAsync(response: okhttp3.Response) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { response.close() }
        }
    }
}
