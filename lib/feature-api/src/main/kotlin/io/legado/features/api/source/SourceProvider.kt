package io.legado.features.api.source

import java.io.InputStream

data class SourceDescriptor(val key: SourceKey, val name: String, val language: String = "", val enabled: Boolean = true, val supportsLatest: Boolean = false)
data class CatalogItem(val id: String, val title: String, val author: String? = null, val cover: String? = null, val description: String? = null, val genre: String? = null, val metadata: String? = null)
data class CatalogPage(val items: List<CatalogItem>, val hasNext: Boolean)
data class ChapterDescriptor(val id: String, val title: String, val index: Int, val number: Float = -1f, val uploadedAt: Long = 0L, val metadata: String? = null,
    val volume: Boolean = false, val parentItem: CatalogItem? = null, val originalId: String? = null)
enum class CatalogSection { POPULAR, LATEST, FILTER }

data class FilterValue(val text: String = "", val selected: Int = 0, val checked: Boolean = false, val ascending: Boolean = true)
enum class FilterKind { HEADER, SEPARATOR, TEXT, SELECT, CHECKBOX, TRISTATE, SORT, GROUP }
data class FilterDescriptor(val path: List<Int>, val title: String, val kind: FilterKind, val value: FilterValue = FilterValue(), val choices: List<String> = emptyList(), val children: List<FilterDescriptor> = emptyList())

/** Instances belong to one request/UI session, are not stored in a global source singleton. */
interface FilterSession : AutoCloseable {
    val source: SourceKey
    fun describe(): List<FilterDescriptor>
    fun set(path: List<Int>, value: FilterValue)
    override fun close()
}

data class ImageReference(val source: SourceKey, val index: Int, val pageUrl: String, val imageUrl: String? = null)
interface ImageStream : AutoCloseable {
    val stream: InputStream
    val length: Long
    override fun close()
}
data class MediaTrack(val url: String, val language: String)
data class VideoResource(val url: String, val title: String, val headers: Map<String, String> = emptyMap(), val subtitles: List<MediaTrack> = emptyList(), val audio: List<MediaTrack> = emptyList(), val preferred: Boolean = false,
    val lineId: String = "default", val lineTitle: String = "默认线路", val expiresAt: Long? = null)
sealed interface ContentResult {
    data class Images(val images: List<ImageReference>) : ContentResult
    data class Videos(val videos: List<VideoResource>) : ContentResult
    data class Text(val text: String) : ContentResult
}

interface SourceProvider {
    val provider: String
    suspend fun sources(): List<SourceDescriptor>
    suspend fun filters(source: SourceKey): FilterSession
    suspend fun searchFilters(source: SourceKey): FilterSession = filters(source)
    suspend fun preferences(source: SourceKey): SourcePreferenceSession? = null
    suspend fun search(source: SourceKey, query: String, page: Int, filters: FilterSession): CatalogPage
    suspend fun discover(source: SourceKey, section: CatalogSection, page: Int, filters: FilterSession): CatalogPage
    suspend fun details(source: SourceKey, item: CatalogItem): CatalogItem
    suspend fun chapters(source: SourceKey, item: CatalogItem): List<ChapterDescriptor>
    suspend fun content(source: SourceKey, item: CatalogItem, chapter: ChapterDescriptor): ContentResult
}
