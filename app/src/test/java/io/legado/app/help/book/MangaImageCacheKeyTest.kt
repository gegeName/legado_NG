package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Test
import io.legado.app.utils.MD5Utils

class MangaImageCacheKeyTest {
    @Test
    fun canonicalFileNameMatchesTheLegacyUrlCacheName() {
        val book = Book(bookUrl = "book-1")
        val url = "https://example.com/page.png"

        assertEquals("${MD5Utils.md5Encode16(url)}.png", MangaImageCacheKey.fileName(book, url))
        assertEquals(url, MangaImageCacheKey.value(book, url))
    }
}
