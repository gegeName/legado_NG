package io.legado.features.translation

import com.google.gson.Gson
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.manga.TranslationConfiguration

class TranslationPreferences(private val config: ConfigPort) {
    private val gson = Gson()
    fun load(): TranslationConfiguration = config.get("manga-translation", "settings-v1")?.let {
        require(it.length <= 32768) { "漫画翻译配置过大" }
        val value = gson.fromJson(it, TranslationConfiguration::class.java)
        value.copy(settings = value.settings.copy())
    } ?: TranslationConfiguration()
    fun save(value: TranslationConfiguration) {
        require(value.settings.textEngine in setOf("auto", "ai", "google", "bing", "deepl", "baidu", "libre"))
        require(value.settings.recognitionEngine in setOf("mlkit", "ai"))
        require(value.settings.sourceLanguage.isNotBlank() && value.settings.targetLanguage.isNotBlank())
        config.put("manga-translation", "settings-v1", gson.toJson(value.copy(settings = value.settings.copy())))
    }
}
