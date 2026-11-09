package io.legado.features.translation

import kotlinx.coroutines.*

/** Window ownership only: foreground subscribers keep their shared engine task on cancellation. */
class TranslationPreloadWindow<K> : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedMapOf<K, Job>()
    private var session: String? = null
    private var closed = false

    @Synchronized fun update(session: String, candidates: List<K>, count: Int,
        translate: suspend (K) -> Unit, failed: (K, Throwable) -> Unit = { _, _ -> }) {
        check(!closed)
        if (this.session != session) { clear(); this.session = session }
        val desired = candidates.distinct().take(count.coerceIn(0, 24)).toSet()
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key !in desired) { item.value.cancel(); iterator.remove() }
        }
        for (key in desired) if (key !in jobs) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { translate(key) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed(key, e) }
            }
            jobs[key] = job
            job.start()
        }
    }
    @Synchronized fun clear() { jobs.values.forEach { it.cancel() }; jobs.clear(); session = null }
    @Synchronized override fun close() { clear(); closed = true; scope.cancel() }
}
