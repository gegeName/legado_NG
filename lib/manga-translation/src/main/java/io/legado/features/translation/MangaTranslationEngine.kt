package io.legado.features.translation

import io.legado.features.api.host.*
import io.legado.features.api.manga.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MangaTranslationEngine(private val host: TranslationPlatform) : AutoCloseable {
    private val tasks = TranslationCoordinator()
    // Bounds full-page bitmaps/native OCR memory; network/text providers have their own quotas.
    private val pixels = Semaphore(1)
    private val translator = MangaPublicTranslator(host.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build())
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun identity(request: TranslationRequest, settings: TranslationSettings, aiIdentity: String): String =
        hash(request.image.bookId) + ":" + hash(request.toString() + settings.toString() + aiIdentity)
    suspend fun resolve(request: TranslationRequest, settings: TranslationSettings, priority: ResourcePriority, force: Boolean = false,
        original: suspend () -> FileLease): TranslationFileLease {
        var acquired: TranslationFileLease? = null
        try { return withContext(Dispatchers.IO) {
            val capturedHost = object : TranslationPlatform by host { override val ai = host.ai }
            tasks.resolve(identity(request, settings, capturedHost.ai?.identity.orEmpty()), priority, force) { publish ->
                pixels.withPermit {
                    currentCoroutineContext().ensureActive()
                    original().use { lease -> TranslationPipeline(capturedHost, settings, translator, publish).translate(request, lease.file, force) }
                }
            }.also { acquired = it }
        } } catch (e: Throwable) { acquired?.close(); throw e }
    }
    fun invalidateBook(bookId: String) = tasks.invalidate { it.startsWith(hash(bookId) + ":") }
    fun clear() = tasks.clear(File(host.filesDirectory, "manga-translation"))
    override fun close() = tasks.close()
}
