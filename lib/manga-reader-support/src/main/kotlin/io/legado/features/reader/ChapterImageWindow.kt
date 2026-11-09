package io.legado.features.reader

import io.legado.features.api.manga.ReaderSessionKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Tracks image completion separately from chapter body readiness. A move retires only old work. */
class ChapterImageWindow(private val concurrency: Int = 1) : AutoCloseable {
    init { require(concurrency > 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(concurrency)
    private class Entry { var job: Job? = null; var complete = false; var failures = 0 }
    private val monitor = Any()
    private var session: ReaderSessionKey? = null
    private val entries = mutableMapOf<Int, Entry>()

    fun update(key: ReaderSessionKey, current: Int, total: Int, count: Int,
               prefetch: suspend (Int) -> Boolean, report: (Int, Throwable) -> Unit = { _, _ -> }) {
        synchronized(monitor) {
            if (session != key) { entries.values.forEach { it.job?.cancel() }; entries.clear(); session = key }
            val window = ChapterWindowPlanner.backgroundImages(current, total, count)
            val expired = entries.keys.filter { it !in window }
            expired.forEach { entries.remove(it)?.job?.cancel() }
            // Visible chapter first, then nearest neighbours; background concurrency stays bounded.
            for (index in window.sortedBy { kotlin.math.abs(it.toLong() - current) }) {
                val entry = entries.getOrPut(index) { Entry() }
                if (entry.job != null || entry.complete || entry.failures >= 3) continue
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val complete = permits.withPermit { ensureActive(); prefetch(index) }
                        synchronized(monitor) { if (session == key && entries[index] === entry) entry.complete = complete }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        synchronized(monitor) { if (session == key && entries[index] === entry) entry.failures++ }
                        report(index, e)
                    } finally {
                        synchronized(monitor) { if (entries[index] === entry) entry.job = null }
                    }
                }
                entry.job = job
                job.start()
            }
        }
    }
    fun isComplete(key: ReaderSessionKey, chapter: Int) = synchronized(monitor) { session == key && entries[chapter]?.complete == true }
    fun clear() = synchronized(monitor) { entries.values.forEach { it.job?.cancel() }; entries.clear(); session = null }
    override fun close() { clear(); scope.cancel() }
}
