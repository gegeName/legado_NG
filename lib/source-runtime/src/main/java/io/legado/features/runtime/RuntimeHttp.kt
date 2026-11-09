package io.legado.features.runtime

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The owning coroutine cancels the socket even while a blocking read is in progress. */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withRuntimeResponse(request: Request, block: suspend (Response) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val call = runtimeHttpClient.newCall(request)
    val cancellation = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
        if (it != null) call.cancel()
    }
    try {
        return call.execute().use { response ->
            context.ensureActive()
            check(response.isSuccessful) { "HTTP ${response.code}: ${request.url}" }
            block(response)
        }
    } catch (error: Exception) {
        context.ensureActive()
        throw error
    } finally { cancellation?.dispose() }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        check(output.size().toLong() + size <= limit) { "扩展数据超过大小限制" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
