package io.legado.features.adapter

import io.legado.features.api.host.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One network owner per resource, independent subscriber cancellation and protected file leases. */
class SharedImageResources(
    private val cache: ImageCachePort,
    private val maxConcurrent: Int = 3,
    private val maxPrefetch: Int = 1,
) : ImageResourceResolver, AutoCloseable {
    init { require(maxConcurrent > 0); require(maxPrefetch in 0..maxConcurrent) }
    private class Entry(val key: ImageResourceKey, var priority: ResourcePriority, val order: Long, val isCurrent: () -> Boolean) {
        var subscribers = 0
        var readers = 0
        val result = CompletableDeferred<File>()
        var job: Job? = null
    }
    private val monitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<ImageResourceKey, Entry>()
    private val readers = mutableMapOf<String, Int>()
    private val deferredDeletes = mutableMapOf<File, () -> Unit>()
    private var leaseChanged = CompletableDeferred<Unit>()
    private var order = 0L
    private var active = 0
    private var background = 0
    private var closed = false
    private val generations = mutableMapOf<String, Long>()
    private var globalGeneration = 0L
    private val pausedBooks = mutableSetOf<String>()
    fun generation(bookId: String): Long = synchronized(monitor) { globalGeneration + (generations[bookId] ?: 0L) }
    fun resumeBook(bookId: String) { synchronized(monitor) { pausedBooks.remove(bookId) } }
    suspend fun pauseBook(bookId: String) {
        val jobs = synchronized(monitor) {
            pausedBooks.add(bookId)
            generations[bookId] = (generations[bookId] ?: 0L) + 1
            invalidateLocked { it.bookId == bookId }
        }
        jobs.joinAll()
    }

    override suspend fun resolve(key: ImageResourceKey, priority: ResourcePriority): FileLease = resolve(key, priority) { true }

    suspend fun resolve(key: ImageResourceKey, priority: ResourcePriority, isCurrent: () -> Boolean): FileLease {
        require(priority != ResourcePriority.PREFETCH || maxPrefetch > 0) { "后台图片预取已关闭" }
        currentCoroutineContext().ensureActive()
        val entry = synchronized(monitor) {
            check(!closed) { "图片资源层已关闭" }
            if (!isCurrent() || key.bookId in pausedBooks) throw CancellationException("图片会话已失效")
            val target = cache.target(key).absolutePath
            invalidateLocked { other -> other != key && cache.target(other).absolutePath == target }
            val e = entries.getOrPut(key) { Entry(key, priority, ++order, isCurrent).also { cache.cached(key)?.let(it.result::complete) } }
            e.subscribers++
            if (priority.ordinal < e.priority.ordinal) e.priority = priority
            dispatch()
            e
        }
        try {
            val file = entry.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (closed || entries[key] !== entry || !isCurrent()) throw CancellationException("图片资源请求已失效")
                entry.readers++
                readers[file.absolutePath] = (readers[file.absolutePath] ?: 0) + 1
            }
            return object : FileLease {
                private val released = AtomicBoolean(false)
                override val file = file
                override val version = "${key.configurationFingerprint}:${key.contentVersion}"
                override fun close() {
                    if (!released.compareAndSet(false, true)) return
                    synchronized(monitor) {
                        entry.readers--
                        val count = (readers[file.absolutePath] ?: 1) - 1
                        if (count == 0) readers.remove(file.absolutePath) else readers[file.absolutePath] = count
                        leaseChanged.complete(Unit)
                        leaseChanged = CompletableDeferred()
                        prune(entry)
                    }
                }
            }
        } finally {
            synchronized(monitor) { entry.subscribers--; prune(entry) }
        }
    }
    private fun prune(entry: Entry) {
        if (entry.subscribers == 0 && entry.readers == 0) {
            entries.remove(entry.key, entry)
            if (!entry.result.isCompleted) { entry.result.cancel(); entry.job?.cancel() }
        }
        drainDeletes()
    }
    /** Called with monitor held. Visible/user work can use slots reserved from background work. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun dispatch() {
        while (!closed && active < maxConcurrent) {
            val next = entries.values.asSequence().filter {
                it.subscribers > 0 && it.job == null && !it.result.isCompleted &&
                    (it.priority != ResourcePriority.PREFETCH || background < maxPrefetch)
            }.minWithOrNull(compareBy<Entry> { it.priority.ordinal }.thenBy { it.order }) ?: break
            val isBackground = next.priority == ResourcePriority.PREFETCH
            active++
            if (isBackground) background++
            next.job = scope.launch(start = CoroutineStart.ATOMIC) { execute(next, isBackground) }
        }
    }
    private suspend fun execute(entry: Entry, isBackground: Boolean) {
        var temporary: File? = null
        try {
            temporary = cache.download(entry.key)
            while (true) {
                currentCoroutineContext().ensureActive()
                val wait = synchronized(monitor) {
                    if (entries[entry.key] !== entry || entry.subscribers <= 0 || closed || !entry.isCurrent()) throw CancellationException("图片发布请求已失效")
                    if ((readers[cache.target(entry.key).absolutePath] ?: 0) > 0) leaseChanged
                    else { entry.result.complete(cache.publish(entry.key, checkNotNull(temporary))); null }
                }
                if (wait == null) break
                wait.await()
            }
        } catch (e: Throwable) { entry.result.completeExceptionally(e) }
        finally {
            try { temporary?.let(cache::discard) }
            finally {
                synchronized(monitor) {
                    active--
                    if (isBackground) background--
                    prune(entry)
                    dispatch()
                }
            }
        }
    }
    private fun invalidateLocked(predicate: (ImageResourceKey) -> Boolean): List<Job> {
        val affected = entries.values.filter { predicate(it.key) }.toList()
        affected.forEach {
            entries.remove(it.key, it)
            it.result.completeExceptionally(CancellationException("图片配置或会话已失效"))
            it.job?.cancel()
        }
        drainDeletes()
        return affected.mapNotNull { it.job }
    }
    fun invalidate(predicate: (ImageResourceKey) -> Boolean) { synchronized(monitor) { invalidateLocked(predicate) } }
    fun invalidateBook(bookId: String) = synchronized(monitor) {
        generations[bookId] = (generations[bookId] ?: 0L) + 1
        invalidateLocked { it.bookId == bookId }
        Unit
    }
    fun invalidateAll() = synchronized(monitor) {
        globalGeneration++
        invalidateLocked { true }
        Unit
    }
    suspend fun invalidateAndAwait(predicate: (ImageResourceKey) -> Boolean) {
        synchronized(monitor) { invalidateLocked(predicate) }.joinAll()
    }
    private fun unused(file: File) = (readers[file.absolutePath] ?: 0) == 0 && entries.values.none { cache.target(it.key).absolutePath == file.absolutePath }
    private fun drainDeletes() {
        val iterator = deferredDeletes.iterator()
        while (iterator.hasNext()) {
            val (file, action) = iterator.next()
            if (unused(file) && runCatching(action).isSuccess) iterator.remove()
        }
    }
    /** Deletion and acquisition share the same guard; consumers cannot acquire during removal. */
    fun deleteIfUnused(file: File, delete: () -> Unit): Boolean = synchronized(monitor) {
        if (!unused(file)) return false
        delete()
        true
    }
    fun deleteWhenUnused(file: File, delete: () -> Unit) = synchronized(monitor) {
        deferredDeletes[file] = delete
        drainDeletes()
    }
    /** Synchronous platform decoders can protect an already validated canonical file too. */
    fun borrow(file: File, version: () -> String, usable: () -> Boolean): FileLease? = synchronized(monitor) {
        if (closed || !usable()) return null
        val capturedVersion = version()
        readers[file.absolutePath] = (readers[file.absolutePath] ?: 0) + 1
        object : FileLease {
            private val released = AtomicBoolean(false)
            override val file = file
            override val version = capturedVersion
            override fun close() {
                if (!released.compareAndSet(false, true)) return
                synchronized(monitor) {
                    val count = (readers[file.absolutePath] ?: 1) - 1
                    if (count == 0) readers.remove(file.absolutePath) else readers[file.absolutePath] = count
                    leaseChanged.complete(Unit)
                    leaseChanged = CompletableDeferred()
                    drainDeletes()
                }
            }
        }
    }
    override fun close() = synchronized(monitor) {
        if (!closed) {
            closed = true
            entries.values.forEach { it.result.cancel(); it.job?.cancel() }
            entries.clear()
            scope.cancel()
            leaseChanged.complete(Unit)
        }
    }
}
