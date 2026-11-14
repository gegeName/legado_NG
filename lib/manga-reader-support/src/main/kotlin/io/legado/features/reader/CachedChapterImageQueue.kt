package io.legado.features.reader

import io.legado.features.api.manga.ReaderSessionKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.milliseconds

class CachedChapterImageQueue(
    private val concurrency: Int = 3,
    private val retryDelayMillis: Long = 30_000,
    private val maxAttempts: Int = 3,
) : AutoCloseable {
    init { require(concurrency > 0 && retryDelayMillis > 0 && maxAttempts > 0) }
    private data class Failure(val attempts: Int, val retryAt: Long)
    private class Session(val key: ReaderSessionKey) {
        var current = -1
        var total = 0
        var forward = true
        var job: Job? = null
        val signal = Channel<Unit>(Channel.CONFLATED)
        val completed = mutableSetOf<Int>()
        val revisions = mutableMapOf<Int, Long>()
        val failures = mutableMapOf<String, Failure>()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private var session: Session? = null
    private fun now() = System.nanoTime() / 1_000_000

    fun update(
        key: ReaderSessionKey,
        current: Int,
        total: Int,
        newlyAvailableChapter: Int? = null,
        chapters: suspend () -> List<Int>,
        images: suspend (Int) -> List<String>?,
        download: suspend (String) -> Unit,
        report: (Int?, Throwable) -> Unit,
    ) {
        synchronized(monitor) {
            if (session?.key != key) {
                clear()
                session = Session(key)
            }
            val state = checkNotNull(session)
            val moved = state.current != current
            if (moved && state.current >= 0) state.forward = current > state.current
            if (moved || state.total != total) {
                state.job?.cancel()
                state.job = null
            }
            state.current = current
            state.total = total
            newlyAvailableChapter?.let {
                state.completed.remove(it)
                state.revisions[it] = (state.revisions[it] ?: 0) + 1
            }
            if (state.job?.isActive != true) {
                val forward = state.forward
                state.job = scope.launch { run(state, current, total, forward, chapters, images, download, report) }
            }
            if (moved || newlyAvailableChapter != null) state.signal.trySend(Unit)
        }
    }

    private suspend fun run(
        state: Session,
        current: Int,
        total: Int,
        forward: Boolean,
        chapters: suspend () -> List<Int>,
        images: suspend (Int) -> List<String>?,
        download: suspend (String) -> Unit,
        report: (Int?, Throwable) -> Unit,
    ) {
        while (currentCoroutineContext().isActive) {
            var retryAt: Long? = null
            try {
                val indices = chapters().filter { it in 0 until total && if (forward) it >= current else it <= current }
                    .sortedBy { if (forward) it else -it }
                for (index in indices) {
                    currentCoroutineContext().ensureActive()
                    val revision = synchronized(monitor) {
                        if (index in state.completed) null else state.revisions[index] ?: 0
                    } ?: continue
                    val urls = images(index)?.distinct()?.takeIf { it.isNotEmpty() } ?: continue
                    var complete = true
                    for (batch in urls.chunked(concurrency)) {
                        val results = coroutineScope {
                            batch.map { url -> async { downloadOne(state, index, url, download, report) } }.awaitAll()
                        }
                        if (results.any { !it }) complete = false
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(monitor) {
                        if (complete && (state.revisions[index] ?: 0) == revision) state.completed.add(index)
                        urls.mapNotNull { state.failures[it] }.filter { it.attempts < maxAttempts }.forEach {
                            retryAt = minOf(retryAt ?: Long.MAX_VALUE, it.retryAt)
                        }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                report(null, e)
                retryAt = now() + retryDelayMillis
            }
            val retry = retryAt
            if (retry == null) state.signal.receive()
            else withTimeoutOrNull((retry - now()).coerceAtLeast(1).milliseconds) { state.signal.receive() }
        }
    }

    private suspend fun downloadOne(
        state: Session,
        chapter: Int,
        url: String,
        download: suspend (String) -> Unit,
        report: (Int?, Throwable) -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val failure = synchronized(monitor) { state.failures[url] }
        if (failure != null && (failure.attempts >= maxAttempts || failure.retryAt > now())) return false
        return try {
            download(url)
            currentCoroutineContext().ensureActive()
            synchronized(monitor) { state.failures.remove(url) }
            true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(monitor) { state.failures[url] = Failure((failure?.attempts ?: 0) + 1, now() + retryDelayMillis) }
            report(chapter, e)
            false
        }
    }

    fun clear() = synchronized(monitor) {
        session?.job?.cancel()
        session = null
    }
    override fun close() { clear(); scope.cancel() }
}
