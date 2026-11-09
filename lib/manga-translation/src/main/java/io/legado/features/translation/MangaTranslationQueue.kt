package io.legado.features.translation

import kotlinx.coroutines.CompletableDeferred

internal class MangaTranslationQueue(
    private val parallelism: Int,
    private val backgroundLimit: Int = 1,
    private val fairByKey: Boolean = false
) {
    private class Entry(val key: String, var priority: Long) {
        var foreground = false
        val ready = CompletableDeferred<Unit>()
    }

    private val waiting = mutableListOf<Entry>()
    private val active = mutableSetOf<Entry>()
    private val foregroundKeys = linkedMapOf<String, Long>()
    private val lastDispatch = linkedMapOf<String, Long>()
    private var dispatchSequence = 0L
    private var running = 0
    private var backgroundRunning = 0
    private var sequence = 0L

    @Synchronized
    fun prioritize(key: String) {
        val priority = ++sequence
        waiting.filter { it.key == key }.forEach {
            it.priority = priority
            it.foreground = true
        }
        active.filter { it.key == key && !it.foreground }.forEach {
            it.foreground = true
            backgroundRunning--
        }
        foregroundKeys[key] = priority
        if (foregroundKeys.size > 256) foregroundKeys.remove(foregroundKeys.keys.first())
        dispatch()
    }

    suspend fun <T> withPermit(key: String, foreground: Boolean, action: suspend () -> T): T {
        val entry = synchronized(this) {
            val priority = foregroundKeys[key] ?: if (foreground) ++sequence else 0L
            Entry(key, priority).also {
                it.foreground = foreground || priority > 0L
                waiting.add(it)
                dispatch()
            }
        }
        try {
            entry.ready.await()
            return action()
        } finally {
            synchronized(this) {
                if (!waiting.remove(entry)) {
                    active.remove(entry)
                    running--
                    if (!entry.foreground) backgroundRunning--
                }
                dispatch()
            }
        }
    }

    private fun dispatch() {
        while (running < parallelism && waiting.isNotEmpty()) {
            val eligible = waiting.filter { it.foreground || backgroundRunning < backgroundLimit }
            val candidates = eligible.filter { it.foreground }.ifEmpty { eligible }
            val entry = if (fairByKey) {
                candidates.minWithOrNull(compareBy<Entry>(
                    { candidate -> active.count { it.key == candidate.key } },
                    { lastDispatch[it.key] ?: 0L },
                    { -it.priority }
                ))
            } else {
                candidates.maxByOrNull { it.priority }
            } ?: return
            waiting.remove(entry)
            if (fairByKey) {
                lastDispatch[entry.key] = ++dispatchSequence
                if (lastDispatch.size > 256) lastDispatch.remove(lastDispatch.keys.first())
            }
            active.add(entry)
            running++
            if (!entry.foreground) backgroundRunning++
            entry.ready.complete(Unit)
        }
    }
}
