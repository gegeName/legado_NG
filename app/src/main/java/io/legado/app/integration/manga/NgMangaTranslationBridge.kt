package io.legado.app.integration.manga
import io.legado.app.help.book.isPdf

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.appDb
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.help.ai.*
import io.legado.app.help.http.okHttpClient
import io.legado.app.integration.image.NgOriginalImageResources
import io.legado.app.utils.GSON
import io.legado.features.adapter.MangaTranslationFeatures
import io.legado.features.adapter.MangaTranslationHost
import io.legado.features.api.host.*
import io.legado.features.api.manga.*
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object NgMangaTranslationBridge {
    private val config = object : ConfigPort {
        private val prefs get() = appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE)
        override fun get(namespace: String, key: String): String? = prefs.getString("$namespace:$key", null)
        override fun put(namespace: String, key: String, value: String?) { prefs.edit().putString("$namespace:$key", value).apply() }
    }
    var configuration: TranslationConfiguration
        get() = MangaTranslationFeatures.configuration(config)
        set(value) = MangaTranslationFeatures.saveConfiguration(config, value)
    private var instance: MangaTranslationFeatures? = null
    private data class BackgroundPage(val url: String, val chapter: Int, val index: Int)
    private val background = MangaTranslationFeatures.PreloadWindow<BackgroundPage>()
    @Synchronized private fun engine(): MangaTranslationFeatures = instance ?: MangaTranslationFeatures(object : MangaTranslationHost {
        override val context get() = appCtx
        override val filesDirectory get() = appCtx.filesDir
        override val cacheDirectory get() = appCtx.cacheDir
        override val config = NgMangaTranslationBridge.config
        override val client get() = okHttpClient
        override val log = LogPort { _, message, error -> AppLog.put(message, error) }
        override val ai: AiTextTranslationPort? get() {
            val provider = AiProviderStore.activeProvider().takeIf { it.enabled && it.apiKey.isNotBlank() && it.model.isNotBlank() } ?: return null
            val encoded = GSON.toJson(provider)
            return object : AiTextTranslationPort {
                override val identity = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
                override suspend fun structured(systemPrompt: String, userContent: String, image: File?): String {
                    check(image == null) { "当前 NG AI 文本接口未提供图片识别" }
                    check(GSON.toJson(AiProviderStore.provider(provider.id)) == encoded) { "AI 配置已变化，请重试" }
                    val response = AiManager.generateText(listOf(AiMessage(AiMessage.Role.SYSTEM, systemPrompt), AiMessage(AiMessage.Role.USER, userContent)),
                        AiTextParams(temperature = 0f, disableThinking = true, jsonResponse = true), provider.id, provider.model)
                    check(GSON.toJson(AiProviderStore.provider(provider.id)) == encoded) { "AI 配置已变化，请重试" }
                    return response.content
                }
            }
        }
    }).also { instance = it }

    suspend fun translate(book: Book, url: String, chapterId: String, index: Int, priority: ResourcePriority, force: Boolean = false,
        generation: Long = NgOriginalImageResources.generation(book.bookUrl)): TranslationFileLease {
        val settings = configuration.settings
        val stableChapter = withContext(Dispatchers.IO) {
            chapterId.toIntOrNull()?.let { appDb.bookChapterDao.getChapter(book.bookUrl, it)?.url } ?: chapterId
        }
        val input = NgOriginalImageResources.translationInput(book, url, stableChapter, index, priority, generation)
        if (book.isPdf) {
            val version = withContext(Dispatchers.IO) { NgMangaPdfBridge.fingerprint(book) }
            return engine().resolve(input.reference.copy(image = input.reference.image.copy(configurationFingerprint = version)), settings, priority, force) { NgMangaPdfBridge.resolve(book, url, priority) }
        }
        return engine().resolve(input.reference, settings, priority, force, input.original)
    }
    fun updateBackground(book: Book, pages: List<BaseMangaPage>, visible: Int, forward: Boolean, revision: Int) {
        val value = configuration
        if (!value.enabled || value.settings.preloadCount == 0) { background.clear(); return }
        val generation = NgOriginalImageResources.generation(book.bookUrl)
        val plan = NgMangaReaderBridge.prefetch(pages, visible, forward, value.settings.preloadCount)
        val candidates = plan.network.mapNotNull { key -> pages.filterIsInstance<MangaPage>().firstOrNull { it.mImageUrl == key.resource } }
            .map { BackgroundPage(it.mImageUrl, it.chapterIndex, it.index) }
        background.update("${book.bookUrl}:${book.origin}:$generation:$revision", candidates, value.settings.preloadCount, { page ->
            translate(book.copy(), page.url, page.chapter.toString(), page.index, ResourcePriority.PREFETCH, generation = generation).use { }
        }, { _, error -> AppLog.put("漫画后台翻译失败", error) })
    }
    fun stopBackground() = background.clear()
    @Synchronized fun invalidateBook(bookId: String) { background.clear(); instance?.invalidateBook(bookId) }
    fun clear() { background.clear(); engine().clear() }
    @Synchronized fun reset() { background.clear(); instance?.close(); instance = null }
}
