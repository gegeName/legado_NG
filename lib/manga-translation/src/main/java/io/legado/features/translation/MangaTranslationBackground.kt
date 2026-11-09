package io.legado.features.translation

internal object MangaTranslationBackground {
    fun estimate(samples: IntArray): Int {
        val counts = IntArray(4096)
        samples.forEach { color ->
            if (color ushr 24 >= 128) counts[bucket(color)]++
        }
        val dominant = counts.indices.maxBy { counts[it] }
        if (counts[dominant] == 0) return -1
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        samples.forEach { color ->
            if (color ushr 24 >= 128 && bucket(color) == dominant) {
                red += color ushr 16 and 255
                green += color ushr 8 and 255
                blue += color and 255
                count++
            }
        }
        return (255 shl 24) or ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or (blue / count).toInt()
    }

    fun textColor(background: Int): Int {
        val brightness = (background ushr 16 and 255) * 299 +
            (background ushr 8 and 255) * 587 + (background and 255) * 114
        return if (brightness >= 150_000) 0xff000000.toInt() else -1
    }

    private fun bucket(color: Int): Int = ((color ushr 20 and 15) shl 8) or
        ((color ushr 12 and 15) shl 4) or (color ushr 4 and 15)
}
