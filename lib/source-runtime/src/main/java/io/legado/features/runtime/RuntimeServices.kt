package io.legado.features.runtime

import io.legado.features.api.host.ConfigPort
import io.legado.features.extensioncompat.ExtensionServices
import kotlinx.serialization.json.Json
import java.io.File

internal val RuntimeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
internal val runtimeHttpClient get() = ExtensionServices.require().client
internal object RuntimeLog {
    fun put(message: String, error: Throwable? = null) = ExtensionServices.require().log(message, error)
}
class ExtensionException(message: String) : IllegalStateException(message)

object RuntimeServices {
    private var config: ConfigPort? = null
    fun initialize(config: ConfigPort) { this.config = config }
    internal fun getPreference(key: String) = checkNotNull(config).get("extensions", key)
    internal fun putPreference(key: String, value: String) = checkNotNull(config).put("extensions", key, value)
    internal val directory: File get() = File(ExtensionServices.require().application.filesDir, "feature_extensions").apply { mkdirs() }
}

internal object RuntimeFilesJsonStore {
    fun readText(name: String): String? {
        require(name == File(name).name)
        return File(RuntimeServices.directory, name).also { RuntimeFiles.recover(it) }.takeIf { it.isFile }?.readText()
    }
    fun writeText(name: String, text: String) {
        require(name == File(name).name)
        val target = File(RuntimeServices.directory, name)
        val temp = File(target.parentFile, ".${name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output -> output.write(text.toByteArray()); output.fd.sync() }
            RuntimeFiles.replaceFileAtomically(temp, target)
        } finally { temp.delete() }
    }
}

internal object RuntimeFiles {
    @Synchronized fun recover(target: File) {
        val backup = File(target.parentFile, "${target.name}.replace-backup")
        if (!backup.exists()) return
        if (!target.exists()) check(backup.renameTo(target)) { "旧文件恢复失败" }
        else check(backup.delete()) { "无法清理旧备份" }
    }
    /** Both paths must be on the module's filesystem. Publishing never falls back to a partial copy. */
    @Synchronized fun replaceFileAtomically(incoming: File, target: File) {
        target.parentFile?.mkdirs()
        recover(target)
        check(incoming.isFile) { "待发布文件不存在" }
        // Android/Linux rename replaces an existing destination atomically; Windows requires a backup.
        if (incoming.renameTo(target)) return
        val backup = File(target.parentFile, "${target.name}.replace-backup")
        check(!backup.exists() || backup.delete()) { "无法清理旧备份" }
        val existed = target.exists()
        if (existed) check(target.renameTo(backup)) { "无法暂存旧文件" }
        if (!incoming.renameTo(target)) {
            if (existed) check(backup.renameTo(target)) { "发布失败且旧文件恢复失败" }
            error("无法原子发布文件")
        }
        backup.delete()
    }

    @Synchronized fun deleteWithReplaceBackup(target: File): Boolean {
        val backup = File(target.parentFile, "${target.name}.replace-backup")
        return (!target.exists() || target.delete()) && (!backup.exists() || backup.delete())
    }
}
