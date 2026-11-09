package io.legado.features.tvbox

import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

class TvBoxHttp(val client: OkHttpClient) {
    companion object { internal val job = ThreadLocal<Job?>() }
    suspend fun <T> bound(action: () -> T): T = withContext(Dispatchers.IO) {
        val previous = job.get()
        job.set(currentCoroutineContext()[Job])
        try { currentCoroutineContext().ensureActive(); action() } finally { job.set(previous) }
    }
    @OptIn(InternalCoroutinesApi::class)
    fun <T> execute(request: Request, limit: Int = 8 * 1024 * 1024, consume: (Response, ByteArray) -> T): T {
        val owner = job.get()
        owner?.ensureActive()
        val call = client.newCall(request)
        val binding = owner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (owner.isCancelled) call.cancel() }
        try {
            return call.execute().use { response ->
                require(response.body.contentLength() <= limit) { "TVBox 响应超过大小限制" }
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        owner?.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= limit) { "TVBox 响应超过大小限制" }
                        bytes.write(buffer, 0, count)
                    }
                }
                owner?.ensureActive()
                consume(response, bytes.toByteArray())
            }
        } finally { binding?.dispose() }
    }
    fun text(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 8 * 1024 * 1024): String =
        execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build(), limit) { response, bytes ->
            check(response.isSuccessful) { "TVBox HTTP ${response.code}" }
            bytes.toString(response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }
}
