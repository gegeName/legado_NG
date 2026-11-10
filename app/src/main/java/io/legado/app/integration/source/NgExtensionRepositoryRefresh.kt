package io.legado.app.integration.source

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

internal data class ExtensionRefreshState(val refreshing: Boolean = false, val message: String = "")

/** The most recently selected repository owns the visible refresh result. */
internal class NgExtensionRepositoryRefresh(
    scope: CoroutineScope,
    private val fetch: suspend (String) -> Unit,
) {
    private data class Request(val revision: Long, val repository: String)
    private var revision = 0L
    private val requests = MutableStateFlow<Request?>(null)
    private val mutableState = MutableStateFlow(ExtensionRefreshState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            requests.filterNotNull().collectLatest { request ->
                try {
                    fetch(request.repository)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request.revision == revision) mutableState.value = ExtensionRefreshState(message = e.message ?: "刷新失败")
                } finally {
                    if (request.revision == revision) mutableState.value = mutableState.value.copy(refreshing = false)
                }
            }
        }
    }

    fun refresh(repository: String) {
        mutableState.value = ExtensionRefreshState(refreshing = true)
        requests.value = Request(++revision, repository)
    }
}
