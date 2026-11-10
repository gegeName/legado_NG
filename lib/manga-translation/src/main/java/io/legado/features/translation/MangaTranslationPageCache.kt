package io.legado.features.translation

import java.io.File

internal object MangaTranslationPageCache {
    data class State(
        val missingIds: Set<String>,
        val unrenderedIds: Set<String>,
        val retainedIds: Set<String>,
    ) {
        val complete: Boolean get() = missingIds.isEmpty() && unrenderedIds.isEmpty()
    }

    fun state(regionIds: Set<String>, translations: Map<String, String>, renderedIds: Set<String>): State {
        val translatedIds = regionIds.filterTo(mutableSetOf()) { !translations[it].isNullOrBlank() }
        return State(
            missingIds = regionIds - translatedIds,
            unrenderedIds = translatedIds - renderedIds,
            retainedIds = regionIds - renderedIds,
        )
    }

    fun find(directory: File, key: String, force: Boolean, preferPartial: Boolean = false): File? {
        if (force) return null
        val complete = File(directory, "$key.png")
        val partial = File(directory, "$key.partial.png")
        val candidates = if (preferPartial) listOf(partial, complete) else listOf(complete, partial)
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun target(directory: File, key: String, complete: Boolean): File =
        File(directory, if (complete) "$key.png" else "$key.partial.png")

    fun isPartial(file: File): Boolean = file.name.endsWith(".partial.png")
}
