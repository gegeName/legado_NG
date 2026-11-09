package io.legado.features.api.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Shared admission accounting for native PDF and OCR/composition working sets. */
class ImageMemoryBudget(val capacity: Long) {
    init { require(capacity > 0) }
    private val lock = Any()
    private var used = 0L
    private var changed = CompletableDeferred<Unit>()
    suspend fun acquire(estimate: Long): Closeable {
        require(estimate > 0)
        val bytes = estimate.coerceAtMost(capacity)
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(lock) {
                if (bytes <= capacity - used) { used += bytes; null } else changed
            }
            if (wait == null) {
                val released = AtomicBoolean()
                return Closeable {
                    if (released.compareAndSet(false, true)) synchronized(lock) {
                        used -= bytes
                        changed.complete(Unit); changed = CompletableDeferred()
                    }
                }
            }
            wait.await()
        }
    }
    companion object {
        /** Estimates bound overlap; they are not an Android heap measurement or allocation guarantee. */
        val process by lazy { ImageMemoryBudget((Runtime.getRuntime().maxMemory() / 3).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)) }
    }
}
