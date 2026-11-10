package io.legado.app.integration.source

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NgExtensionRepositoryRefreshTest {
    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) yield() }
    private fun withRefreshScope(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val refreshScope = CoroutineScope(coroutineContext + Job())
        try { refreshScope.block() } finally { refreshScope.cancel() }
    }

    @Test fun selectingRepositoriesAndAllFetchesEachRequestedScope() = withRefreshScope {
        val requests = mutableListOf<String>()
        val controller = NgExtensionRepositoryRefresh(this) { requests += it }
        for (repository in listOf("repo-a", "repo-b", "")) {
            controller.refresh(repository)
            assertTrue(controller.state.value.refreshing)
            await { !controller.state.value.refreshing }
        }
        assertEquals(listOf("repo-a", "repo-b", ""), requests)
    }

    @Test fun rapidSwitchCancelsOldFetchAndDoesNotPublishItsLateFailure() = withRefreshScope {
        val requests = mutableListOf<String>()
        val latest = CompletableDeferred<Unit>()
        var cancelled = false
        val controller = NgExtensionRepositoryRefresh(this) { repository ->
            requests += repository
            if (repository == "old") {
                try { awaitCancellation() }
                finally {
                    cancelled = true
                    // Even a non-cooperative cleanup failure belongs to the old selection.
                    throw IllegalStateException("old repository failed")
                }
            } else latest.await()
        }
        controller.refresh("old")
        await { requests == listOf("old") }
        controller.refresh("skipped")
        controller.refresh("latest")
        await { requests.last() == "latest" }
        assertTrue(cancelled)
        assertEquals(listOf("old", "latest"), requests)
        assertTrue(controller.state.value.refreshing)
        assertEquals("", controller.state.value.message)
        latest.complete(Unit)
        await { !controller.state.value.refreshing }
        assertEquals("", controller.state.value.message)
    }

    @Test fun failedSelectionShowsErrorAndSameRepositoryCanBeRetried() = withRefreshScope {
        var attempts = 0
        val controller = NgExtensionRepositoryRefresh(this) {
            if (++attempts == 1) throw IllegalStateException("repository unavailable")
        }
        controller.refresh("repo")
        await { !controller.state.value.refreshing }
        assertEquals("repository unavailable", controller.state.value.message)
        controller.refresh("repo")
        assertEquals("", controller.state.value.message)
        await { !controller.state.value.refreshing }
        assertEquals(2, attempts)
        assertEquals("", controller.state.value.message)
    }
}
