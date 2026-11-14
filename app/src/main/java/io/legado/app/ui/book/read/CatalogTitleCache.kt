package io.legado.app.ui.book.read

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.data.entities.ReplaceRule

/** Keeps expensive title-rule results across catalog drawer recreation. */
internal class CatalogTitleCache(private var capacity: Int) {
    companion object {
        val shared = CatalogTitleCache(4096)
    }

    fun reserve(chapterCount: Int) {
        synchronized(values) { capacity = maxOf(capacity, chapterCount + 4096) }
    }
    private data class RuleKey(
        val id: Long, val name: String, val pattern: String, val replacement: String,
        val isRegex: Boolean, val timeout: Long,
    )

    private data class Key(
        val bookUrl: String, val chapterUrl: String, val title: String,
        val rules: List<RuleKey>, val book: ReplaceBook?, val converter: Int,
        val scriptChapter: List<Any?>?,
    )

    private val values = LinkedHashMap<Key, String>(capacity, 0.75f, true)
    private val titleLineBreaks = Regex("[\\r\\n\\u2028\\u2029]+")

    init {
        require(capacity > 0)
    }

    fun title(
        chapter: BookChapter,
        rules: List<ReplaceRule>?,
        useReplace: Boolean,
        replaceBook: ReplaceBook?,
        chineseConverterType: Int,
        render: (List<ReplaceRule>) -> String,
    ): String {
        // A content-only rule must never enter the catalog's title pipeline.
        val titleRules = if (useReplace) rules.orEmpty().filter {
            it.scopeTitle && it.isEnabled && it.pattern.isNotEmpty()
        } else emptyList()
        // ReplaceRule/BookChapter equality only compares IDs/URLs. Snapshot actual values
        // so edits under the same identity cannot return a stale title.
        val scriptChapter = if (titleRules.any { it.isRegex && it.replacement.startsWith("@js:") }) {
            listOf(chapter.index, chapter.baseUrl, chapter.isVolume, chapter.isVip, chapter.isPay,
                chapter.resourceUrl, chapter.tag, chapter.wordCount, chapter.start, chapter.end,
                chapter.startFragmentId, chapter.endFragmentId, chapter.variable, chapter.imgUrl)
        } else null
        val key = Key(chapter.bookUrl, chapter.url, chapter.title, titleRules.map {
            RuleKey(it.id, it.name, it.pattern, it.replacement, it.isRegex, it.timeoutMillisecond)
        }, replaceBook.takeIf { titleRules.isNotEmpty() }, chineseConverterType, scriptChapter)
        synchronized(values) { values[key]?.let { return it } }
        // Never hold the cache monitor while running a user regex/JS replacement.
        // Rules may introduce a newline after the chapter number. A catalog title is
        // inline text; keeping that break makes a one-line row appear truncated early.
        val value = render(titleRules).replace(titleLineBreaks, " ").trim()
        synchronized(values) {
            values[key] = value
            if (values.size > capacity) {
                val eldest = values.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return value
    }
}
