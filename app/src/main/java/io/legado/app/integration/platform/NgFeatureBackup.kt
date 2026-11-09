package io.legado.app.integration.platform

import android.content.Context
import io.legado.app.help.storage.BackupAES
import io.legado.app.help.http.okHttpClient
import io.legado.app.integration.source.NgExtensionSourceBridge
import io.legado.features.adapter.*
import io.legado.features.api.host.*
import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.File

/** Android storage/encryption adapter; archive schemas and validation belong to lib. */
object NgFeatureBackup {
    const val fileName = "featureModules.json.aes"
    private const val TRUST = "extensions:mangaExtensionTrustedSignatures"
    private val unknown get() = File(appCtx.filesDir, "feature_backup_unknown.json")
    private val port = object : FeaturePreferencePort {
        override fun snapshot(): Map<String, Map<String, PortablePreference>> {
            val directory = File(appCtx.applicationInfo.dataDir, "shared_prefs")
            val names = directory.listFiles()?.filter { it.extension == "xml" && it.nameWithoutExtension.matches(Regex("source_[A-Za-z0-9_-]{1,120}")) }?.map { it.nameWithoutExtension }.orEmpty()
            return (names + "feature_modules").associateWith { name ->
                appCtx.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterKeys { name != "feature_modules" || it != TRUST }.mapValues { (_, value) ->
                    when (value) {
                        is Boolean -> PortablePreference(PreferenceType.BOOLEAN, value.toString())
                        is Int -> PortablePreference(PreferenceType.INT, value.toString())
                        is Long -> PortablePreference(PreferenceType.LONG, value.toString())
                        is Float -> PortablePreference(PreferenceType.FLOAT, value.toString())
                        is Set<*> -> PortablePreference(PreferenceType.STRING_SET, "", value.map { it as String })
                        is String -> PortablePreference(PreferenceType.STRING, value)
                        else -> error("功能设置类型未支持")
                    }
                }
            }
        }
        override fun replace(groups: Map<String, Map<String, PortablePreference>>) {
            val before = groups.keys.associateWith { appCtx.getSharedPreferences(it, Context.MODE_PRIVATE).all }
            fun write(name: String, values: Map<String, *>) {
                val prefs = appCtx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val trust = if (name == "feature_modules") prefs.getString(TRUST, null) else null
                val edit = prefs.edit().clear()
                values.forEach { (key, value) -> if (name != "feature_modules" || key != TRUST) when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                } }
                if (trust != null) edit.putString(TRUST, trust)
                check(edit.commit()) { "写入功能设置失败：$name" }
            }
            try {
                groups.forEach { (name, entries) -> write(name, entries.mapValues { (_, p) -> when (p.type) {
                    PreferenceType.STRING -> p.value
                    PreferenceType.INT -> p.value.toInt()
                    PreferenceType.LONG -> p.value.toLong()
                    PreferenceType.FLOAT -> p.value.toFloat()
                    PreferenceType.BOOLEAN -> p.value.toBooleanStrict()
                    PreferenceType.STRING_SET -> p.values.toSet()
                } }) }
            } catch (error: Throwable) { before.forEach { (name, values) -> runCatching { write(name, values) } }; throw error }
        }
    }
    private fun preserved(): List<FeatureBackupEntry> = if (!unknown.isFile) emptyList() else {
        require(unknown.length() <= 32L * 1024 * 1024)
        GSON.fromJson(unknown.readText(), Array<FeatureBackupEntry>::class.java).toList()
    }
    fun write(root: File) {
        val json = FeatureBackupFeatures(port).encode(preserved())
        File(root, fileName).writeText(BackupAES().encryptBase64(json))
    }
    fun prepare(root: File): FeatureBackupFeatures.Restore? {
        val file = File(root, fileName)
        if (!file.isFile) return null
        require(file.length() <= 48L * 1024 * 1024) { "功能备份文件过大" }
        return FeatureBackupFeatures(port).prepare(BackupAES().decryptStr(file.readText()))
    }
    suspend fun restore(plan: FeatureBackupFeatures.Restore?) {
        if (plan == null) return
        io.legado.app.integration.manga.NgMangaReaderBridge.navigation.reset()
        io.legado.app.integration.manga.NgMangaReaderBridge.stopImages()
        io.legado.app.integration.manga.NgMangaTranslationBridge.reset()
        io.legado.app.integration.manga.NgMangaPdfBridge.reset()
        io.legado.app.integration.image.NgOriginalImageResources.invalidateAll()
        io.legado.app.integration.video.NgVideoBridge.invalidate()
        plan.apply()
        unknown.writeText(GSON.toJson(plan.preserved))
        ExtensionFeatures.restored()
        val prefs = appCtx.getSharedPreferences("feature_modules", Context.MODE_PRIVATE)
        TvBoxFeatures.close()
        TvBoxFeatures.start(appCtx, object : ConfigPort {
            override fun get(namespace: String, key: String) = prefs.getString("$namespace:$key", null)
            override fun put(namespace: String, key: String, value: String?) { prefs.edit().putString("$namespace:$key", value).apply() }
        }, okHttpClient)
        NgExtensionSourceBridge.syncSources(TvBoxFeatures.provider().sources(), setOf("tvbox"), applyEnabled = true)
    }
}
