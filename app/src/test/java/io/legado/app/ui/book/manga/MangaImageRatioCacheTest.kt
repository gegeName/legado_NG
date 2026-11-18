package io.legado.app.ui.book.manga

import org.junit.Assert.*
import org.junit.Test

class MangaImageRatioCacheTest {
    @Test fun retainedGeometrySurvivesItemRecreation() {
        val cache = MangaImageRatioCache()
        assertEquals(0f, cache.get("page"))
        cache.put("page", 1116, 1727)
        repeat(4) { assertEquals(1727f, 1116 / cache.get("page"), 0.001f) }
    }

    @Test fun mountedPageReceivesLatePrefetchBoundsAndKeepsDecodedGeometry() {
        val cache = MangaImageRatioCache()
        val ratio = cache.observe("page")
        assertEquals(0f, ratio.value)

        cache.put("page", 1080, 1596, onlyIfUnknown = true)
        assertEquals(1080f / 1596, ratio.value)

        cache.put("page", 1080, 1594)
        cache.put("page", 1080, 1596, onlyIfUnknown = true)
        assertEquals(1080f / 1594, ratio.value)
    }

    @Test fun invalidBoundsDoNotEraseKnownGeometry() {
        val cache = MangaImageRatioCache()
        cache.put("page", 1116, 1727)
        cache.put("page", -1, -1)
        cache.put("page", 0, 12)
        assertEquals(1116f / 1727, cache.get("page"))
    }

    @Test fun cacheIsBoundedAndKeepsRecentlyVisibleImages() {
        val cache = MangaImageRatioCache(2)
        cache.put("a", 1, 2)
        cache.put("b", 1, 3)
        cache.get("a")
        cache.put("c", 1, 4)
        assertEquals(0f, cache.get("b"))
        assertEquals(0.5f, cache.get("a"))
        assertEquals(0.25f, cache.get("c"))
    }
}
