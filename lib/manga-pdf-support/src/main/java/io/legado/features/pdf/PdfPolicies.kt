package io.legado.features.pdf

import kotlin.math.*
import io.legado.features.api.manga.*

object PdfPolicies {
    fun size(width: Int, height: Int, screen: Int, tier: PdfTier, heap: Long): Pair<Int, Int> {
        require(width > 0 && height > 0 && screen > 0)
        val pixels = if (tier == PdfTier.PREVIEW) 1_000_000L else when {
            heap < 192L * 1024 * 1024 -> 2_000_000L
            heap >= 512L * 1024 * 1024 -> 6_000_000L
            else -> 4_000_000L
        }
        val target = if (tier == PdfTier.PREVIEW) screen.toDouble() * 2 / 3 else screen.toDouble() * 2
        val rawHeight = target * height / width
        val scale = minOf(1.0, 4096 / max(target, rawHeight), sqrt(pixels / (target * rawHeight)))
        return max(1, floor(target * scale).toInt()) to max(1, floor(rawHeight * scale).toInt())
    }
    fun window(page: Int, total: Int, ahead: Int): IntRange {
        if (ahead <= 0 || page !in 0 until total) return IntRange.EMPTY
        return (page - 16).coerceAtLeast(0)..(page.toLong() + ahead.coerceAtMost(16)).coerceAtMost(total - 1L).toInt()
    }
}
