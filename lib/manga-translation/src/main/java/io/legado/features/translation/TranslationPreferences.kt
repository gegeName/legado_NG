package io.legado.features.translation

import com.google.gson.Gson
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.manga.TranslationConfiguration

class TranslationPreferences(private val config: ConfigPort) {
    private val gson = Gson()
    fun load(): TranslationConfiguration {
        val current = config.get("manga-translation", "settings-v2")
        if (current != null) return decode(current)
        val legacy = config.get("manga-translation", "settings-v1") ?: return TranslationConfiguration()
        val migrated = decode(legacy).let { value ->
            if (value.settings.preloadCount == 0) value.copy(settings = value.settings.copy(preloadCount = 4)) else value
        }
        save(migrated)
        return migrated
    }
    private fun decode(raw: String): TranslationConfiguration {
        require(raw.length <= 32768) { "漫画翻译配置过大" }
        val value = gson.fromJson(raw, TranslationConfiguration::class.java)
        return value.copy(settings = value.settings.copy())
    }
    fun save(value: TranslationConfiguration) {
        require(value.settings.textEngine in setOf("auto", "ai", "google", "bing", "deepl", "baidu", "libre"))
        require(value.settings.recognitionEngine in setOf("mlkit", "ai"))
        require(value.settings.sourceLanguage.isNotBlank() && value.settings.targetLanguage.isNotBlank())
        config.put("manga-translation", "settings-v2", gson.toJson(value.copy(settings = value.settings.copy())))
    }
}
