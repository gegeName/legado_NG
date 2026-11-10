package io.legado.app.integration.source

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.applyCustomBookType
import io.legado.app.help.book.updateTypeFromSource
import io.legado.app.help.source.getBookType
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.utils.GSON
import io.legado.features.adapter.SourceFeatures
import io.legado.features.api.source.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** NG entities and persistence only; provider strategy lives in lib/source-*. */
object NgExtensionSourceBridge {
    private const val CATALOG = "legadoFeatures.catalog"
    private const val CHAPTER = "legadoFeatures.chapter"
    fun key(source: BookSource): SourceKey? = PluginUris.source(source.bookSourceUrl, if (source.bookSourceType == BookSourceType.video) ContentKind.VIDEO else ContentKind.MANGA)
    fun handles(source: BookSource) = key(source) != null
    fun syncSources(sources: List<SourceDescriptor>, providers: Set<String> = sources.map { it.key.provider }.toSet(), applyEnabled: Boolean = false) {
        val dao = appDb.bookSourceDao
        val origins = sources.map { PluginUris.source(it.key) }.toSet()
        dao.all.filter { old ->
            runCatching { key(old)?.provider }.getOrNull() in providers && old.bookSourceUrl !in origins
        }.map { it.bookSourceUrl }.distinct().forEach(SourceHelp::deleteBookSource)
        sources.forEach { descriptor ->
            val origin = PluginUris.source(descriptor.key)
            val old = dao.getBookSource(origin)
            val source = old ?: BookSource(bookSourceUrl = origin).apply {
                bookSourceGroup = descriptor.key.provider
                enabled = descriptor.enabled
                enabledExplore = true
                bookSourceComment = "[legado-features:${descriptor.key.provider}:1]"
            }
            if (applyEnabled || !descriptor.enabled || source.bookSourceComment.orEmpty().contains("[feature-unavailable]")) {
                source.enabled = descriptor.enabled
                source.enabledExplore = descriptor.enabled
                source.bookSourceComment = source.bookSourceComment.orEmpty().replace("\n[feature-unavailable]", "")
            }
            source.bookSourceName = "${descriptor.name} (${descriptor.language})"
            source.bookSourceType = if (descriptor.key.contentKind == ContentKind.VIDEO) BookSourceType.video else BookSourceType.image
            source.searchUrl = "feature:search"
            source.exploreUrl = GSON.toJson(buildList {
                add(mapOf("title" to "热门", "url" to "feature:popular"))
                if (descriptor.supportsLatest) add(mapOf("title" to "最新", "url" to "feature:latest"))
                add(mapOf("title" to "筛选", "url" to "feature:filter"))
            })
            dao.insert(source)
        }
    }
    private fun CatalogItem.search(key: SourceKey, source: BookSource): SearchBook = SearchBook(
        bookUrl = PluginUris.book(key, id), tocUrl = PluginUris.book(key, id), origin = source.bookSourceUrl,
        originName = source.bookSourceName, name = title, author = author.orEmpty(), coverUrl = cover, intro = description, kind = genre,
        type = if (key.contentKind == ContentKind.VIDEO) BookType.video else BookType.image,
        variable = GSON.toJson(mapOf(CATALOG to GSON.toJson(this))),
    )
    fun item(book: Book, key: SourceKey): CatalogItem {
        val saved = book.variableMap[CATALOG]?.let { GSON.fromJson(it, CatalogItem::class.java) }
        return saved ?: CatalogItem(PluginUris.original(book.bookUrl, key), book.name, book.author, book.coverUrl, book.intro, book.kind)
    }
    suspend fun search(source: BookSource, query: String, page: Int, filter: ((String, String, String?) -> Boolean)? = null): ArrayList<SearchBook> {
        val key = checkNotNull(key(source))
        val provider = SourceFeatures.provider(key)
        val result = SourceFeatures.filters(key, search = true).use { provider.search(key, query, page, it) }
        return result.items.map { it.search(key, source) }.filter { filter?.invoke(it.name, it.author, it.kind) != false }.toCollection(ArrayList())
    }
    suspend fun explore(source: BookSource, url: String, page: Int): ArrayList<SearchBook> {
        val key = checkNotNull(key(source))
        val provider = SourceFeatures.provider(key)
        val section = when (url) { "feature:popular" -> CatalogSection.POPULAR; "feature:latest" -> CatalogSection.LATEST; "feature:filter" -> CatalogSection.FILTER; else -> error("未知扩展发现分类") }
        return SourceFeatures.filters(key).use { provider.discover(key, section, page, it) }.items.map { it.search(key, source) }.toCollection(ArrayList())
    }
    suspend fun details(source: BookSource, book: Book, canRename: Boolean): Book {
        val key = checkNotNull(key(source))
        val result = SourceFeatures.provider(key).details(key, item(book, key))
        currentCoroutineContext().ensureActive()
        if (canRename) { book.name = result.title; book.author = result.author.orEmpty() }
        book.coverUrl = result.cover; book.intro = result.description; book.kind = result.genre
        book.updateTypeFromSource(source.getBookType())
        book.applyCustomBookType()
        book.variableMap[CATALOG] = GSON.toJson(result)
        book.variable = GSON.toJson(book.variableMap)
        return book
    }
    suspend fun chapters(source: BookSource, book: Book): Result<List<BookChapter>> = runCatching {
        val key = checkNotNull(key(source))
        val result = SourceFeatures.provider(key).chapters(key, item(book, key))
        check(result.isNotEmpty()) { "扩展目录为空" }
        val list = result.map { c -> BookChapter(url = c.id, title = c.title, bookUrl = book.bookUrl, baseUrl = book.bookUrl, index = c.index, isVolume = c.volume, variable = GSON.toJson(mapOf(CHAPTER to GSON.toJson(c)))) }.toCollection(ArrayList())
        BookChapterList.updateBookTocInfo(book, list)
        list
    }.onFailure { if (it is CancellationException) throw it; currentCoroutineContext().ensureActive() }
    suspend fun content(source: BookSource, book: Book, chapter: BookChapter, save: Boolean): String {
        val key = checkNotNull(key(source))
        val c = chapter.variableMap[CHAPTER]?.let { GSON.fromJson(it, ChapterDescriptor::class.java) } ?: ChapterDescriptor(chapter.url, chapter.title, chapter.index)
        val content = SourceFeatures.provider(key).content(key, item(book, key), c)
        val result = when (content) {
            is ContentResult.Images -> content.images.joinToString("\n") { "<img src=\"${PluginUris.image(it).replace("&", "&amp;").replace("\"", "&quot;")}\">" }
            is ContentResult.Text -> content.text
            is ContentResult.Videos -> {
                // Persist the full result for the video bridge; its capability policy selects the request.
                chapter.variableMap["legadoFeatures.videos"] = GSON.toJson(content.videos)
                chapter.variable = GSON.toJson(chapter.variableMap)
                val selected = content.videos.firstOrNull { it.preferred } ?: content.videos.first()
                selected.url + "," + GSON.toJson(mapOf("headers" to selected.headers))
            }
        }
        currentCoroutineContext().ensureActive()
        if (save) BookHelp.saveContent(source, book, chapter, result)
        return result
    }
}
