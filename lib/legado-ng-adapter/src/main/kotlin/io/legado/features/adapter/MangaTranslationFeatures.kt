package io.legado.features.adapter

import android.content.Context
import io.legado.features.api.host.*
import io.legado.features.api.manga.*
import io.legado.features.translation.*
import okhttp3.OkHttpClient
import java.io.File

interface MangaTranslationHost {
    val context: Context
    val filesDirectory: File
    val cacheDirectory: File
    val config: ConfigPort
    val client: OkHttpClient
    val ai: AiTextTranslationPort?
    val log: LogPort
}

class MangaTranslationFeatures(host: MangaTranslationHost) : AutoCloseable {
    private val engine = MangaTranslationEngine(object : TranslationPlatform {
        override val context get() = host.context
        override val filesDirectory get() = host.filesDirectory
        override val cacheDirectory get() = host.cacheDirectory
        override val config get() = host.config
        override val client get() = host.client
        override val ai get() = host.ai
        override val log get() = host.log
    })
    suspend fun resolve(request: TranslationRequest, settings: TranslationSettings, priority: ResourcePriority, force: Boolean = false, original: suspend () -> FileLease) =
        engine.resolve(request, settings, priority, force, original)
    fun invalidateBook(bookId: String) = engine.invalidateBook(bookId)
    fun clear() = engine.clear()
    override fun close() = engine.close()
    class PreloadWindow<K> : AutoCloseable {
        private val delegate = TranslationPreloadWindow<K>()
        fun update(session: String, candidates: List<K>, count: Int, translate: suspend (K) -> Unit, failed: (K, Throwable) -> Unit) =
            delegate.update(session, candidates, count, translate, failed)
        fun clear() = delegate.clear()
        override fun close() = delegate.close()
    }
    companion object {
        fun configuration(config: ConfigPort) = TranslationPreferences(config).load()
        fun saveConfiguration(config: ConfigPort, value: TranslationConfiguration) = TranslationPreferences(config).save(value)
    }
}
