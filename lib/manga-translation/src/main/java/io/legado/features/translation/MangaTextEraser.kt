package io.legado.features.translation

import java.util.ArrayDeque
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object MangaTextEraser {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
        val area: Long get() = width.toLong() * height

        fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    data class Group(val index: Int, val bounds: Box)

    fun mergeDuplicates(boxes: List<Box>): List<Group> {
        val groups = mutableListOf<Group>()
        boxes.indices.sortedByDescending { boxes[it].area }.forEach { index ->
            val box = boxes[index]
            if (box.area == 0L) return@forEach
            var merged = Group(index, box)
            var changed: Boolean
            do {
                changed = false
                for (position in groups.indices.reversed()) {
                    val existing = groups[position]
                    val intersection = max(0, min(existing.bounds.right, merged.bounds.right) - max(existing.bounds.left, merged.bounds.left)).toLong() *
                        max(0, min(existing.bounds.bottom, merged.bounds.bottom) - max(existing.bounds.top, merged.bounds.top))
                    if (intersection >= min(existing.bounds.area, merged.bounds.area) * 0.85) {
                        val representative = if (boxes[existing.index].area >= boxes[merged.index].area) existing.index else merged.index
                        merged = Group(representative, merged.bounds.union(existing.bounds))
                        groups.removeAt(position)
                        changed = true
                    }
                }
            } while (changed)
            groups.add(merged)
        }
        return groups.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    fun overlay(pixels: IntArray, width: Int, height: Int, text: Box, background: Int, padding: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        require(text.area > 0L && text.left >= 0 && text.top >= 0 && text.right <= width && text.bottom <= height)
        require(padding >= 0)
        val opaqueBackground = background or 0xff000000.toInt()
        val overlay = IntArray(pixels.size)
        val visited = BitSet(pixels.size)
        val queue = ArrayDeque<Int>()
        fun extend(seed: Int) {
            if (visited[seed] || !isInk(pixels[seed], background)) return
            queue.add(seed)
            visited.set(seed)
            val component = ArrayList<Int>()
            var cropped = false
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var insideText = 0L
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                if (x in text.left until text.right && y in text.top until text.bottom) insideText++
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) cropped = true
                if (component.size < 32_768) component.add(index) else cropped = true
                for (neighborY in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (neighborX in max(0, x - 1)..min(width - 1, x + 1)) {
                        val neighbor = neighborY * width + neighborX
                        if (!visited[neighbor] && isInk(pixels[neighbor], background)) {
                            visited.set(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }
            val surroundingOutline = minX < text.left && maxX >= text.right && minY < text.top && maxY >= text.bottom &&
                insideText * 20 <= text.area
            val oversized = maxX - minX + 1 > text.width + padding * 2 ||
                maxY - minY + 1 > text.height + padding * 2
            val outlineLike = maxX - minX + 1 >= text.width * 0.65 &&
                maxY - minY + 1 >= text.height * 0.65 &&
                component.size.toLong() * 6 < (maxX - minX + 1).toLong() * (maxY - minY + 1)
            if (!surroundingOutline && !cropped && !oversized && !outlineLike && insideText > 0) {
                component.forEach { overlay[it] = opaqueBackground }
            }
        }
        for (y in text.top until text.bottom) {
            for (x in text.left until text.right) extend(y * width + x)
        }
        return overlay
    }

    fun runs(overlay: IntArray, width: Int): IntArray {
        require(width > 0 && overlay.size % width == 0)
        fun collect(destination: IntArray?): Int {
            var count = 0
            var index = 0
            while (index < overlay.size) {
                if (overlay[index] ushr 24 == 0) {
                    index++
                    continue
                }
                val start = index
                val rowEnd = (index / width + 1) * width
                while (index < rowEnd && overlay[index] ushr 24 != 0) index++
                if (destination != null) {
                    destination[count] = start
                    destination[count + 1] = index
                }
                count += 2
            }
            return count
        }
        return IntArray(collect(null)).also { collect(it) }
    }

    fun layoutBounds(pixels: IntArray, overlay: IntArray, width: Int, height: Int,
                     text: Box, background: Int, padding: Int, search: Box = text): Box? {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        require(overlay.size == pixels.size && padding >= 0)
        require(text.area > 0L && text.left >= 0 && text.top >= 0 && text.right <= width && text.bottom <= height)
        require(search.left >= 0 && search.top >= 0 && search.right <= width && search.bottom <= height)
        val blocked = BitSet(search.width * search.height)
        val margin = min(padding, 2)
        for (y in max(0, search.top - margin) until min(height, search.bottom + margin)) {
            for (x in max(0, search.left - margin) until min(width, search.right + margin)) {
                val index = y * width + x
                if (overlay[index] ushr 24 != 0 || !isInk(pixels[index], background)) continue
                for (row in max(search.top, y - margin) until min(search.bottom, y + margin + 1)) {
                    val left = max(search.left, x - margin) - search.left
                    val right = min(search.right, x + margin + 1) - search.left
                    blocked.set((row - search.top) * search.width + left, (row - search.top) * search.width + right)
                }
            }
        }
        val heights = IntArray(search.width + 1)
        val stack = IntArray(search.width + 1)
        var best: Box? = null
        var bestDistance = Long.MAX_VALUE
        for (y in 0 until search.height) {
            for (x in 0 until search.width) {
                heights[x] = if (blocked[y * search.width + x]) 0 else heights[x] + 1
            }
            var count = 0
            for (x in heights.indices) {
                while (count > 0 && heights[stack[count - 1]] > heights[x]) {
                    val rectangleHeight = heights[stack[--count]]
                    val left = if (count == 0) 0 else stack[count - 1] + 1
                    val candidate = Box(search.left + left, search.top + y + 1 - rectangleHeight,
                        search.left + x, search.top + y + 1)
                    if (candidate.width < 2 || candidate.height < 2) continue
                    val distance = abs(candidate.left.toLong() + candidate.right - text.left - text.right) +
                        abs(candidate.top.toLong() + candidate.bottom - text.top - text.bottom)
                    if (candidate.area > (best?.area ?: 0L) || candidate.area == best?.area && distance < bestDistance) {
                        best = candidate
                        bestDistance = distance
                    }
                }
                stack[count++] = x
            }
        }
        return best?.takeIf { it.area >= text.area * 0.15 }
    }

    /** Finds a nearby enclosed, nearly uniform interior; open page backgrounds are rejected. */
    fun enclosedInterior(pixels: IntArray, width: Int, height: Int, text: Box, background: Int): Box? {
        val radius = max(48, max(text.width, text.height) * 3)
        val search = Box(max(0, text.left - radius), max(0, text.top - radius),
            min(width, text.right + radius), min(height, text.bottom + radius))
        if (search.width.toLong() * search.height > 1_000_000L) return null
        val centerX = (text.left + text.right) / 2
        val centerY = (text.top + text.bottom) / 2
        var seed = -1
        var seedDistance = Int.MAX_VALUE
        for (y in text.top until text.bottom) for (x in text.left until text.right) {
            val index = y * width + x
            if (nearColor(pixels[index], background, 40)) {
                val distance = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
                if (distance < seedDistance) { seed = index; seedDistance = distance }
            }
        }
        if (seed < 0) return null
        val visited = BitSet(search.width * search.height)
        val queue = ArrayDeque<Int>()
        queue.add(seed)
        visited.set((seed / width - search.top) * search.width + seed % width - search.left)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var touchesSearchEdge = false
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
            if (x == search.left || x == search.right - 1 || y == search.top || y == search.bottom - 1) touchesSearchEdge = true
            for (ny in max(search.top, y - 1)..min(search.bottom - 1, y + 1)) {
                for (nx in max(search.left, x - 1)..min(search.right - 1, x + 1)) {
                    val local = (ny - search.top) * search.width + nx - search.left
                    if (!visited[local] && nearColor(pixels[ny * width + nx], background, 40)) {
                        visited.set(local)
                        queue.add(ny * width + nx)
                    }
                }
            }
        }
        if (touchesSearchEdge || right < left || bottom < top) return null
        val interior = Box(left, top, right + 1, bottom + 1)
        if (interior.area < text.area || centerX !in interior.left until interior.right || centerY !in interior.top until interior.bottom) return null
        return interior
    }

    private fun nearColor(color: Int, reference: Int, tolerance: Int) = color ushr 24 >= 240 &&
        maxOf(abs((color ushr 16 and 255) - (reference ushr 16 and 255)),
            abs((color ushr 8 and 255) - (reference ushr 8 and 255)), abs((color and 255) - (reference and 255))) <= tolerance

    private fun isInk(color: Int, background: Int): Boolean = color ushr 24 >= 128 &&
        maxOf(abs((color ushr 16 and 255) - (background ushr 16 and 255)),
            abs((color ushr 8 and 255) - (background ushr 8 and 255)),
            abs((color and 255) - (background and 255))) >= 16
}
