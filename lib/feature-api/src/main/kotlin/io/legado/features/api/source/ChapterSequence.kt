package io.legado.features.api.source

/** Converts newest-first provider TOCs to the reader's oldest-first index order. */
object ChapterSequence {
    fun oldestFirst(chapters: List<ChapterDescriptor>): List<ChapterDescriptor> =
        groups(chapters).asReversed().flatMap { group ->
            val heading = group.firstOrNull()?.takeIf(ChapterDescriptor::volume)
            val entries = if (heading == null) group else group.drop(1)
            listOfNotNull(heading) + entries.asReversed()
        }.mapIndexed { index, chapter -> chapter.copy(index = index) }

    private fun groups(chapters: List<ChapterDescriptor>): List<List<ChapterDescriptor>> {
        val result = mutableListOf<List<ChapterDescriptor>>()
        var current = mutableListOf<ChapterDescriptor>()
        chapters.forEach { chapter ->
            if (chapter.volume && current.isNotEmpty()) {
                result += current
                current = mutableListOf()
            }
            current += chapter
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
