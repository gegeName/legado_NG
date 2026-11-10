package io.legado.features.video

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** A rule can supply its player page by calling a browser API instead of returning a URL. */
object VideoRuleAnalysis {
    data class Page(val url: String, val html: String?)
    private class Session {
        val page = AtomicReference<Page?>(null)
    }
    private val active = ThreadLocal<Session?>()

    fun capture(url: String, html: String? = null): Boolean {
        val session = active.get() ?: return false
        if (url.isNotBlank()) session.page.set(Page(url, html))
        return true
    }

    fun captured(): Page? = active.get()?.page?.get()

    /** Dispatcher switches restore this session; concurrent reads never share a global flag. */
    suspend fun <T> withAnalysis(block: suspend () -> T): T =
        withContext(active.asContextElement(Session())) { block() }
}
