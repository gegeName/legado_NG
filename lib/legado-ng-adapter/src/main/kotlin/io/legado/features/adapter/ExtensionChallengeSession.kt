package io.legado.features.adapter

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One visible challenge; only a fresh clearance for the original URL can complete it. */
class ExtensionChallengeSession(
    val url: String,
    val headers: Map<String, String>,
    private val oldClearance: String?,
) {
    private val finished = CountDownLatch(1)
    @Volatile private var successful = false
    val isFinished: Boolean get() = finished.count == 0L

    @Synchronized fun complete(cookie: String?, persist: (String) -> Unit = {}): Boolean {
        if (isFinished) return successful
        val clearance = cookie.orEmpty().split(';').firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=').trim()
            part.substringAfter('=', "").trim().takeIf { name == "cf_clearance" && it.isNotEmpty() }
        }
        if (clearance == null || clearance == oldClearance) return false
        // Do not wake the worker while the host still exposes stale cookies.
        persist(cookie.orEmpty())
        successful = true
        finished.countDown()
        return true
    }

    @Synchronized fun cancel() {
        if (!isFinished) finished.countDown()
    }

    fun await(isCancelled: () -> Boolean, timeoutMillis: Long = 180_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (!isFinished) {
                if (isCancelled() || System.nanoTime() >= deadline) return false
                finished.await(250, TimeUnit.MILLISECONDS)
            }
            return successful && !isCancelled()
        } finally {
            cancel()
        }
    }
}
