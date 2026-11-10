package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.UrlUtil

/** The legacy manga cache is keyed by the absolute image URL. */
internal object MangaImageCacheKey {
    fun value(@Suppress("UNUSED_PARAMETER") book: Book, src: String): String = src

    fun fileName(book: Book, src: String): String =
        "${MD5Utils.md5Encode16(src)}.${UrlUtil.getSuffix(src, "jpg")}"
}
