package io.legado.features.backup

import com.google.gson.*
import io.legado.features.api.host.*

/** Archive parsing and every contributor's validation finish before the first write. */
class FeatureArchive(contributors: List<FeatureBackupContributor>) {
    private val contributors = contributors.associateBy { it.id }.also { require(it.size == contributors.size) }
    private val gson = Gson()
    fun encode(preserved: List<FeatureBackupEntry> = emptyList()): String {
        val owned = contributors.values.map { FeatureBackupEntry(it.id, it.version, it.snapshot()) }
        val retained = preserved.filter { entry -> contributors[entry.id]?.version?.let { it != entry.version } != false }
        return gson.toJson(mapOf("version" to 1, "entries" to (owned.filter { item -> retained.none { it.id == item.id } } + retained))).also(::parse)
    }
    fun parse(text: String): List<FeatureBackupEntry> {
        require(text.toByteArray().size <= 32 * 1024 * 1024) { "功能备份超过 32 MiB" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("version").asInt == 1) { "功能备份版本未支持" }
        val entries = root.getAsJsonArray("entries")
        require(entries.size() <= 128)
        val result = entries.map { element ->
            val entry = element.asJsonObject
            val id = entry.get("id").asString
            require(id.matches(Regex("[a-z][a-z0-9_-]{0,79}"))) { "功能备份条目标识无效" }
            val version = entry.get("version").asInt
            require(version > 0)
            val payload = entry.get("payload").asString
            require(payload.toByteArray().size <= 16 * 1024 * 1024) { "功能备份条目过大" }
            FeatureBackupEntry(id, version, payload)
        }
        require(result.map { it.id }.distinct().size == result.size) { "功能备份条目重复" }
        return result
    }
    data class Restore(val actions: List<PreparedFeatureRestore>, val preserved: List<FeatureBackupEntry>) {
        fun apply() { actions.forEach { it.apply() } }
    }
    fun prepare(text: String): Restore {
        val preserved = mutableListOf<FeatureBackupEntry>()
        val actions = parse(text).mapNotNull { entry ->
            val contributor = contributors[entry.id]
            if (contributor == null || entry.version != contributor.version) { preserved += entry; null }
            else contributor.prepare(entry.payload)
        }
        return Restore(actions, preserved)
    }
}

class PreferenceContributor(private val port: FeaturePreferencePort,
    private val validate: (Map<String, Map<String, PortablePreference>>) -> Unit = {}) : FeatureBackupContributor {
    override val id = "preferences"
    override val version = 1
    private val gson = Gson()
    override fun snapshot(): String = gson.toJson(port.snapshot())
    override fun prepare(payload: String): PreparedFeatureRestore {
        val root = JsonParser.parseString(payload).asJsonObject
        require(root.size() <= 1000)
        val groups = root.entrySet().associate { (group, element) ->
            require(group == "feature_modules" || group.matches(Regex("source_[A-Za-z0-9_-]{1,120}"))) { "非功能设置命名空间" }
            val entries = element.asJsonObject
            require(entries.size() <= 20000)
            group to entries.entrySet().associate { (key, value) ->
                require(key.isNotBlank() && key.length <= 4096)
                val preference = gson.fromJson(value, PortablePreference::class.java)
                require(preference.value.length <= 16 * 1024 * 1024)
                when (preference.type) {
                    PreferenceType.BOOLEAN -> require(preference.value in listOf("true", "false"))
                    PreferenceType.INT -> require(preference.value.toIntOrNull() != null)
                    PreferenceType.LONG -> require(preference.value.toLongOrNull() != null)
                    PreferenceType.FLOAT -> require(preference.value.toFloatOrNull()?.isFinite() == true)
                    PreferenceType.STRING_SET -> require(preference.values.size <= 20000)
                    PreferenceType.STRING -> Unit
                }
                key to preference
            }
        }
        validate(groups)
        return PreparedFeatureRestore { port.replace(groups) }
    }
}
