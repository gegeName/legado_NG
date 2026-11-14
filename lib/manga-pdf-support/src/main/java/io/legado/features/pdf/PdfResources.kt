package io.legado.features.pdf

import io.legado.features.api.host.FileLease
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One native render at a time; consumers own independent subscriptions and file leases. */
class PdfResources(private val root: File, private val backend: PdfRenderBackend,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private class Entry(val request: PdfPageRequest, var priority: ResourcePriority, val order: Long, val epoch: Long) {
        val result = CompletableDeferred<File>()
        var users = 0
        var leases = 0
        var running = false
    }
    private data class Failure(val count: Int, val time: Long)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val entries = linkedMapOf<PdfPageRequest, Entry>()
    private val epochs = mutableMapOf<String, Long>()
    private val readers = mutableMapOf<File, Int>()
    private val deletes = mutableSetOf<File>()
    private val failures = mutableMapOf<PdfPageRequest, Failure>()
    private val windows = mutableMapOf<PdfDocumentKey, IntRange>()
    private val documents = mutableSetOf<PdfDocumentKey>()
    private val centers = mutableMapOf<PdfDocumentKey, Int>()
    private val preload = mutableMapOf<PdfPageRequest, Job>()
    private var changed = CompletableDeferred<Unit>()
    private var sequence = 0L
    private var successes = 0L
    private var closed = false
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            for (signal in wake) {
                while (isActive) {
                    val entry = synchronized(lock) { entries.values.filter { !it.running && !it.result.isCompleted }
                        .minWithOrNull(compareBy<Entry>({ priority(it.priority) }, { it.order }))?.also { it.running = true } } ?: break
                    execute(entry)
                }
            }
        } finally { backend.close() }
    }
    init { worker.start() }
    private fun priority(value: ResourcePriority) = when (value) {
        ResourcePriority.USER_REQUEST -> 0
        ResourcePriority.VISIBLE -> 1
        ResourcePriority.NEAR_PREFETCH -> 2
        ResourcePriority.PREFETCH -> 3
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun directory(key: PdfDocumentKey) = File(root, hash(key.bookId))
    private fun target(request: PdfPageRequest) = File(directory(request.document), "${request.page}_${request.tier.name.lowercase()}_${hash(request.document.version + ":" + request.screenWidth)}.img")
    private fun current(entry: Entry) = !closed && entries[entry.request] === entry && entry.epoch == (epochs[entry.request.document.bookId] ?: 0L)
    fun generation(bookId: String): Long = synchronized(lock) { epochs[bookId] ?: 0L }
    suspend fun resolve(request: PdfPageRequest, priority: ResourcePriority, force: Boolean = false, cacheOnly: Boolean = false, expectedEpoch: Long = generation(request.document.bookId)): FileLease {
        currentCoroutineContext().ensureActive()
        val entry = synchronized(lock) {
            check(!closed) { "PDF 渲染已关闭" }
            if (expectedEpoch != (epochs[request.document.bookId] ?: 0L)) throw CancellationException("PDF 请求已失效")
            documents += request.document
            check(!cacheOnly || backend.valid(target(request))) { "PDF 预览尚未缓存" }
            if (force) { failures.remove(request); entries.remove(request)?.result?.cancel() }
            val failure = failures[request]
            check(failure == null || failure.count < 3 || now() - failure.time >= 30_000) { "PDF 渲染暂时失败，请稍后重试" }
            entries.getOrPut(request) { Entry(request, priority, sequence++, epochs[request.document.bookId] ?: 0L) }.also {
                if (priority(priority) < priority(it.priority)) it.priority = priority
                it.users++
                if (force) forced += it
                wake.trySend(Unit)
            }
        }
        try {
            val file = entry.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!current(entry)) throw CancellationException("PDF 会话已失效")
                entry.leases++; readers[file] = (readers[file] ?: 0) + 1
            }
            return object : FileLease {
                private val released = AtomicBoolean()
                override val file = file
                override val version = "${request.document.version}:${request.tier}:${file.lastModified()}:${file.length()}"
                override fun close() {
                    if (!released.compareAndSet(false, true)) return
                    synchronized(lock) {
                        entry.leases--
                        val count = (readers[file] ?: 1) - 1
                        if (count == 0) readers.remove(file) else readers[file] = count
                        changed.complete(Unit); changed = CompletableDeferred()
                        prune(entry)
                    }
                }
            }
        } finally { synchronized(lock) { entry.users--; prune(entry) } }
    }
    private val forced = mutableSetOf<Entry>()
    private fun prune(entry: Entry) {
        if (entry.users == 0 && entry.leases == 0) {
            entries.remove(entry.request, entry)
            if (!entry.result.isCompleted) entry.result.cancel()
            forced.remove(entry)
            changed.complete(Unit); changed = CompletableDeferred()
        }
        drain()
    }
    private suspend fun execute(entry: Entry) {
        var temporary: File? = null
        try {
            if (!synchronized(lock) { current(entry) }) return
            val target = target(entry.request)
            val force = synchronized(lock) { forced.remove(entry) }
            if (force || !backend.valid(target)) {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val output = File.createTempFile(".pdf-", ".tmp", target.parentFile)
                temporary = output
                // Cancellation of a consumer invalidates publication; it cannot interrupt native PdfRenderer.
                val estimate = if (entry.request.tier == PdfTier.PREVIEW) 12_000_000L else 60_000_000L
                io.legado.features.api.host.ImageMemoryBudget.process.acquire(estimate).use {
                    if (!synchronized(lock) { current(entry) }) throw CancellationException("PDF 渲染请求已过期")
                    withContext(NonCancellable) { backend.render(entry.request, output) }
                }
                check(backend.valid(output)) { "PDF 渲染缓存无效" }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val wait = synchronized(lock) {
                        if (!current(entry) || entry.users <= 0) throw CancellationException("PDF 发布已过期")
                        if ((readers[target] ?: 0) > 0) changed else {
                            check(output.renameTo(target) || ((!target.exists() || target.delete()) && output.renameTo(target))) { "PDF 发布失败" }
                            null
                        }
                    }
                    if (wait == null) break
                    wait.await()
                }
            }
            synchronized(lock) {
                if (current(entry)) {
                    failures.remove(entry.request)
                    target.setLastModified(System.currentTimeMillis())
                    entry.result.complete(target)
                    val prefix = "${entry.request.page}_${entry.request.tier.name.lowercase()}_"
                    target.parentFile?.listFiles()?.filter { it != target && it.extension == "img" && it.name.startsWith(prefix) }?.forEach(::delete)
                    if (++successes % 8 == 0L) trimCapacity(entry.request.document)
                } else entry.result.cancel()
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (error !is CancellationException && current(entry)) {
                    failures[entry.request] = Failure((failures[entry.request]?.count ?: 0) + 1, now())
                }
                entry.result.completeExceptionally(error)
            }
        } finally { temporary?.delete(); synchronized(lock) { prune(entry) } }
    }
    fun updateWindow(document: PdfDocumentKey, page: Int, total: Int, ahead: Int, screenWidth: Int, expectedEpoch: Long = generation(document.bookId)) = synchronized(lock) {
        if (closed || expectedEpoch != (epochs[document.bookId] ?: 0L)) return@synchronized
        documents += document
        val window = PdfPolicies.window(page, total, ahead)
        windows[document] = window
        val candidates = window.filter { it != page }.sortedBy { kotlin.math.abs(it - page) }.take(8)
            .map { PdfPageRequest(document, it, screenWidth, PdfTier.PREVIEW) }.toSet()
        val iterator = preload.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key.document == document && item.key !in candidates) { item.value.cancel(); iterator.remove() }
        }
        candidates.forEach { request -> if (request !in preload) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { resolve(request, ResourcePriority.PREFETCH, expectedEpoch = expectedEpoch).use { } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Foreground resolve exposes the error and supports force. */ }
            }
            preload[request] = job; job.start()
        } }
        val previous = centers[document]
        if (previous == null || kotlin.math.abs(page - previous) >= 3) {
            centers[document] = page
            scope.launch {
                synchronized(lock) {
                    if (windows[document] == window && centers[document] == page && !closed) {
                        directory(document).listFiles()?.filter { it.extension == "img" && it.name.substringBefore('_').toIntOrNull() !in window }?.forEach { delete(it) }
                    }
                }
            }
        }
    }
    private fun protected(file: File) = (readers[file] ?: 0) > 0 || entries.values.any { (it.users > 0 || it.leases > 0) && target(it.request) == file }
    private fun delete(file: File) { if (!protected(file)) file.delete() else deletes += file }
    private fun drain() { deletes.toList().forEach { if (!protected(it) && (!it.exists() || it.delete())) deletes.remove(it) } }
    private fun trimCapacity(document: PdfDocumentKey) {
        fun trim(files: List<File>, limit: Long) {
            var bytes = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                if (bytes <= limit) break
                if (!protected(file)) {
                    val size = file.length(); if (file.delete()) bytes -= size
                }
            }
        }
        trim(directory(document).listFiles()?.filter { it.extension == "img" }.orEmpty(), 300L * 1024 * 1024)
        trim(root.walkTopDown().filter { it.isFile && it.extension == "img" }.toList(), 1024L * 1024 * 1024)
    }
    fun cancelBook(bookId: String) = synchronized(lock) {
        epochs[bookId] = (epochs[bookId] ?: 0L) + 1
        entries.values.filter { it.request.document.bookId == bookId }.toList().forEach { entries.remove(it.request); it.result.cancel() }
        preload.keys.filter { it.document.bookId == bookId }.forEach { preload.remove(it)?.cancel() }
        documents.filter { it.bookId == bookId }.forEach { key ->
            windows.remove(key); centers.remove(key)
            scope.launch { synchronized(lock) { if (key !in documents) backend.release(key) } }
        }
        documents.removeAll { it.bookId == bookId }
        failures.keys.removeAll { it.document.bookId == bookId }
        changed.complete(Unit); changed = CompletableDeferred()
    }
    fun pageCount(document: PdfDocumentKey, expectedEpoch: Long = generation(document.bookId)): Int {
        synchronized(lock) {
            check(!closed)
            if (expectedEpoch != (epochs[document.bookId] ?: 0L)) throw CancellationException("PDF 页数请求已失效")
            documents += document
        }
        val result = backend.pageCount(document)
        return synchronized(lock) {
            if (closed || expectedEpoch != (epochs[document.bookId] ?: 0L)) {
                if (document !in documents) backend.release(document)
                throw CancellationException("PDF 页数请求已失效")
            }
            result
        }
    }
    fun trimMemory(critical: Boolean) = synchronized(lock) {
        preload.values.forEach { it.cancel() }; preload.clear()
        if (critical) {
            documents.map { it.bookId }.distinct().forEach(::cancelBook)
            entries.values.forEach { it.result.cancel() }; entries.clear()
            changed.complete(Unit); changed = CompletableDeferred()
        }
    }
    override fun close() = synchronized(lock) {
        closed = true
        entries.values.forEach { it.result.cancel() }; entries.clear()
        preload.values.forEach { it.cancel() }; preload.clear()
        wake.close(); scope.cancel()
        changed.complete(Unit)
        Unit
    }
}
