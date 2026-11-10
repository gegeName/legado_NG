package io.legado.features.reader

import io.legado.features.api.host.ConfigPort

data class ReaderPreferences(val chapterAhead: Int, val chapterBehind: Int, val imageChapters: Int, val doublePage: Boolean = false)

/** One-time seed from NG text prefetch; later edits never read the old preference again. */
class ReaderPreferenceStore(private val config: ConfigPort) {
    private val namespace = "manga-reader"
    @Synchronized fun load(legacyChapterCount: Int): ReaderPreferences {
        val legacyImageCount = legacyChapterCount.coerceIn(2, 12)
        if (config.get(namespace, "version") == null) {
            val count = legacyChapterCount.coerceIn(0, 100)
            config.put(namespace, "chapterAhead", count.toString())
            config.put(namespace, "chapterBehind", minOf(count, 5).toString())
            config.put(namespace, "imageChapters", legacyImageCount.toString())
            config.put(namespace, "version", "1")
        }
        fun value(key: String, fallback: Int) = (config.get(namespace, key)?.toIntOrNull() ?: fallback).coerceIn(0, 100)
        // Version 1 seeded image prefetch as zero, which made every page load on demand.
        val imageChapters = value("imageChapters", legacyImageCount).let {
            if (it == 0) legacyImageCount else it
        }
        return ReaderPreferences(value("chapterAhead", 10), value("chapterBehind", 5), imageChapters, config.get(namespace, "doublePage") == "true")
    }
    @Synchronized fun save(value: ReaderPreferences) {
        require(value.chapterAhead in 0..100 && value.chapterBehind in 0..100 && value.imageChapters in 0..100)
        config.put(namespace, "chapterAhead", value.chapterAhead.toString())
        config.put(namespace, "chapterBehind", value.chapterBehind.toString())
        config.put(namespace, "imageChapters", value.imageChapters.toString())
        config.put(namespace, "doublePage", value.doublePage.toString())
        config.put(namespace, "version", "1")
    }
}
