package io.legado.features.api.manga

import io.legado.features.api.host.ImageResourceKey
import java.io.File

data class TranslationProviderConfig(
    val deeplKey: String = "",
    val deeplEndpoint: String = "https://api-free.deepl.com/v2/translate",
    val baiduAppId: String = "",
    val baiduSecret: String = "",
    val libreEndpoint: String = "",
    val libreKey: String = "",
)

data class TranslationSettings(
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "zh-CN",
    val textEngine: String = "auto",
    val recognitionEngine: String = "mlkit",
    val preloadCount: Int = 4,
    val providers: TranslationProviderConfig = TranslationProviderConfig(),
    val maxPixels: Long = 8_000_000,
) {
    init { require(preloadCount in 0..24); require(maxPixels in 1..16_000_000) }
}

data class TranslationRequest(val image: ImageResourceKey, val chapterId: String, val pageIndex: Int)
data class TranslationConfiguration(val enabled: Boolean = false, val settings: TranslationSettings = TranslationSettings())

enum class TranslationPageStatus { COMPLETE, PARTIAL, NO_TEXT }
data class TranslationPageResult(val file: File, val status: TranslationPageStatus)
interface TranslationFileLease : io.legado.features.api.host.FileLease {
    val status: TranslationPageStatus
}

/** NG supplies its existing AI service; a text-only service explicitly lacks vision. */
interface AiTextTranslationPort {
    val identity: String
    val supportsImages: Boolean get() = false
    suspend fun structured(systemPrompt: String, userContent: String, image: File? = null): String
}
