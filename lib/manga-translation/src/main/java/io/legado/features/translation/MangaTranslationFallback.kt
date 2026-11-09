package io.legado.features.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object MangaTranslationFallback {
    suspend fun translate(
        blocks: Map<String, String>,
        primary: suspend (Map<String, String>) -> Map<String, String>,
        fallback: suspend (String) -> String,
        onFailure: (String, Exception) -> Unit,
        initial: Map<String, String> = emptyMap(),
        onTranslated: suspend (Map<String, String>) -> Unit = {}
    ): Map<String, String> {
        val translated = initial.filter { (id, text) -> id in blocks && text.isNotBlank() }.toMutableMap()
        val previousByText = translated.entries.associate { (id, text) -> blocks.getValue(id) to text }
        blocks.forEach { (id, text) -> previousByText[text]?.let { translated.putIfAbsent(id, it) } }
        var lastFailure: Exception? = null
        try {
            val missing = blocks.filterKeys { it !in translated }
            if (missing.isNotEmpty()) {
                val results = primary(missing).filter { (id, text) -> id in missing && text.isNotBlank() }
                translated.putAll(results)
                onTranslated(results)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            lastFailure = error
            onFailure("primary", error)
        }
        val byText = translated.entries.associate { (id, text) -> blocks.getValue(id) to text }.toMutableMap()
        val resultLock = Mutex()
        val pending = blocks.filterKeys { it !in translated }.entries.groupBy { it.value }
        coroutineScope { pending.map { (text, entries) -> async {
            currentCoroutineContext().ensureActive()
            val missing = entries
            if (missing.isEmpty()) return@async emptyMap<String, String>()
            try {
                val result = byText[text] ?: fallback(text).also {
                    check(it.isNotBlank()) { "保底翻译返回空结果" }
                }
                val results = missing.associate { it.key to result }
                resultLock.withLock {
                    translated.putAll(results)
                    onTranslated(results)
                }
                results
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onFailure("fallback ids=${missing.joinToString { it.key }}", error)
                emptyMap<String, String>()
            }
        } }.awaitAll() }
        check(translated.isNotEmpty()) {
            "主翻译和保底翻译均未得到译文：${lastFailure?.message.orEmpty()}"
        }
        return translated
    }
}
