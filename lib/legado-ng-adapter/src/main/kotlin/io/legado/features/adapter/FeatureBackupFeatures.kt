package io.legado.features.adapter

import io.legado.features.api.host.*
import io.legado.features.backup.*

class FeatureBackupFeatures(port: FeaturePreferencePort) {
    private val archive = FeatureArchive(listOf(PreferenceContributor(port, ::validate)))
    private fun validate(groups: Map<String, Map<String, PortablePreference>>) {
        val settings = groups["feature_modules"].orEmpty()
        settings["tvbox:configs-v1"]?.let {
            require(it.type == PreferenceType.STRING)
            io.legado.features.tvbox.config.TvBoxConfigs.restore(it.value)
        }
        settings["manga-translation:settings-v1"]?.let { saved ->
            require(saved.type == PreferenceType.STRING)
            val config = object : ConfigPort {
                override fun get(namespace: String, key: String) = if (namespace == "manga-translation" && key == "settings-v1") saved.value else null
                override fun put(namespace: String, key: String, value: String?) = Unit
            }
            val store = io.legado.features.translation.TranslationPreferences(config)
            store.save(store.load())
        }
        settings.filterKeys { it.startsWith("source-filters:") }.values.forEach { saved ->
            require(saved.type == PreferenceType.STRING && saved.value.length <= 1024 * 1024)
            val values = com.google.gson.JsonParser.parseString(saved.value).asJsonArray
            require(values.size() <= 10000)
            values.forEach { element ->
                val value = element.asJsonObject
                require(value.getAsJsonArray("path").size() in 1..64)
                value.getAsJsonArray("path").forEach { require(it.asInt >= 0) }
                require(value["selected"].asInt >= 0)
                value["checked"].asBoolean; value["ascending"].asBoolean; value["text"].asString
            }
        }
    }
    fun encode(preserved: List<FeatureBackupEntry> = emptyList()) = archive.encode(preserved)
    fun entries(text: String): List<FeatureBackupEntry> = archive.parse(text)
    data class Restore(val preserved: List<FeatureBackupEntry>, private val action: () -> Unit) {
        fun apply() = action()
    }
    fun prepare(text: String): Restore = archive.prepare(text).let { Restore(it.preserved, it::apply) }
}
