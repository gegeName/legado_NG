package io.legado.features.translation

import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Subscription, page queue and publication ownership are independent of the Android OCR backend. */
internal class TranslationCoordinator : AutoCloseable {
    private class Entry(val identity: String, val epoch: Long) {
        val result = CompletableDeferred<TranslationPageResult>()
        var job: Job? = null
        var subscribers = 0
        var readers = 0
    }
    private val monitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = MangaTranslationQueue(3)
    private val entries = mutableMapOf<String, Entry>()
    private val readers = mutableMapOf<String, Int>()
    private val pendingDeletes = mutableSetOf<File>()
    private var changed = CompletableDeferred<Unit>()
    private var epoch = 0L
    private var closed = false

    suspend fun resolve(identity: String, priority: ResourcePriority, force: Boolean = false,
        work: suspend (publish: suspend (File, File) -> Unit) -> TranslationPageResult): TranslationFileLease {
        currentCoroutineContext().ensureActive()
        val entry = synchronized(monitor) {
            check(!closed) { "漫画翻译已关闭" }
            if (force) entries.remove(identity)?.let { it.result.cancel(); it.job?.cancel() }
            if (priority != ResourcePriority.PREFETCH) gate.prioritize(identity)
            entries.getOrPut(identity) { Entry(identity, epoch) }.also {
                it.subscribers++
                if (it.job == null) {
                    it.job = scope.launch(start = CoroutineStart.LAZY) { execute(it, priority, work) }
                    it.job?.start()
                }
            }
        }
        try {
            val result = entry.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (!current(entry)) throw CancellationException("漫画翻译会话已失效")
                check(result.file.isFile && result.file.length() > 0) { "译图缓存已失效" }
                entry.readers++
                readers[result.file.absolutePath] = (readers[result.file.absolutePath] ?: 0) + 1
            }
            return object : TranslationFileLease {
                private val released = AtomicBoolean(false)
                override val file = result.file
                override val status = result.status
                override val version = "$identity:${file.lastModified()}:${file.length()}"
                override fun close() {
                    if (!released.compareAndSet(false, true)) return
                    synchronized(monitor) {
                        entry.readers--
                        val count = (readers[file.absolutePath] ?: 1) - 1
                        if (count == 0) readers.remove(file.absolutePath) else readers[file.absolutePath] = count
                        changed.complete(Unit); changed = CompletableDeferred()
                        prune(entry)
                    }
                }
            }
        } finally { synchronized(monitor) { entry.subscribers--; prune(entry) } }
    }
    private fun current(entry: Entry) = !closed && entry.epoch == epoch && entries[entry.identity] === entry
    private fun prune(entry: Entry) {
        if (entry.subscribers == 0 && entry.readers == 0) {
            entries.remove(entry.identity, entry)
            if (!entry.result.isCompleted) { entry.result.cancel(); entry.job?.cancel() }
        }
        drainDeletes()
    }
    private suspend fun execute(entry: Entry, priority: ResourcePriority,
        work: suspend (suspend (File, File) -> Unit) -> TranslationPageResult) {
        try {
            val result = gate.withPermit(entry.identity, priority != ResourcePriority.PREFETCH) {
                currentCoroutineContext().ensureActive()
                work { temporary, target -> publish(entry, temporary, target) }
            }
            synchronized(monitor) { if (current(entry)) entry.result.complete(result) else entry.result.cancel() }
        } catch (e: Throwable) { entry.result.completeExceptionally(e) }
        finally { synchronized(monitor) { prune(entry) } }
    }
    private suspend fun publish(entry: Entry, temporary: File, target: File) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(monitor) {
                if (!current(entry) || entry.subscribers <= 0) throw CancellationException("译图发布已失效")
                if ((readers[target.absolutePath] ?: 0) > 0) changed else {
                    check(temporary.renameTo(target) || ((!target.exists() || target.delete()) && temporary.renameTo(target))) { "译图缓存发布失败" }
                    null
                }
            }
            if (wait == null) return
            wait.await()
        }
    }
    private fun drainDeletes() {
        val iterator = pendingDeletes.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if ((readers[file.absolutePath] ?: 0) == 0 && (!file.exists() || file.delete())) iterator.remove()
        }
    }
    fun invalidate(predicate: (String) -> Boolean) = synchronized(monitor) {
        entries.values.filter { predicate(it.identity) }.toList().forEach { entries.remove(it.identity, it); it.result.cancel(); it.job?.cancel() }
    }
    fun clear(directory: File) = synchronized(monitor) {
        epoch++
        entries.values.forEach { it.result.cancel(); it.job?.cancel() }; entries.clear()
        if (directory.isDirectory) directory.walkBottomUp().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { pendingDeletes += it }
        drainDeletes()
    }
    override fun close(): Unit = synchronized(monitor) {
        closed = true; epoch++
        entries.values.forEach { it.result.cancel(); it.job?.cancel() }; entries.clear()
        scope.cancel(); changed.complete(Unit)
        Unit
    }
}
