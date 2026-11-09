package io.legado.features.runtime

import io.legado.features.runtime.RuntimeLog
import io.legado.features.runtime.ExtensionException
import kotlinx.coroutines.Dispatchers
import io.legado.features.runtime.model.InstallStep
import io.legado.features.runtime.model.MangaExtension
import io.legado.features.runtime.runtimeHttpClient
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExtensionApkInfo(
    val pkgName: String,
    val versionCode: Long,
    val isExtension: Boolean,

    val signatures: List<String>,
)

fun checkExtensionInstallable(incoming: ExtensionApkInfo, current: ExtensionApkInfo?) {
    check(incoming.isExtension) { "${incoming.pkgName} 不是扩展" }
    check(incoming.signatures.isNotEmpty()) { "扩展未签名" }
    if (current == null) return
    check(incoming.versionCode >= current.versionCode) { "不允许降级安装" }
    check(incoming.signatures.isNotEmpty()) { "扩展未签名" }
    check(current.signatures.isNotEmpty() && incoming.signatures.containsAll(current.signatures)) {
        "与已安装扩展签名不一致"
    }
}

private val EXTENSION_PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

fun isValidExtensionPackageName(pkgName: String): Boolean = EXTENSION_PACKAGE_NAME.matches(pkgName)

fun requireValidExtensionPackageName(pkgName: String): String {
    if (!isValidExtensionPackageName(pkgName)) {
        throw ExtensionException("非法扩展包名: $pkgName")
    }
    return pkgName
}

fun safeExtensionFileNameSegment(raw: String): String =
    raw.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
        .joinToString("")

suspend fun downloadExtensionApk(url: String, target: File, onProgress: suspend (Int) -> Unit) {
    val request = Request.Builder().url(url).build()
    withRuntimeResponse(request) { response ->
        if (!response.isSuccessful) {
            throw ExtensionException("下载失败 HTTP ${response.code}: $url")
        }
        val body = response.body
        val total = body.contentLength()
        check(total <= MAX_APK_BYTES) { "扩展安装包过大" }
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var lastProgress = -1
                var written = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    written += read
                    check(written <= MAX_APK_BYTES) { "扩展安装包过大" }
                    output.write(buffer, 0, read)
                    if (total > 0) {
                        val progress = ((written * 100) / total).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                output.fd.sync()
            }
        }
    }
}

private const val MAX_APK_BYTES = 128L * 1024 * 1024

abstract class ExtensionInstallScaffold {

    private val installJobs = ConcurrentHashMap<String, Job>()
    private val installLocks = ConcurrentHashMap<String, Mutex>()
    private val localImports = ConcurrentHashMap.newKeySet<Job>()

    protected abstract fun extensionDir(): File

    protected abstract fun readApkInfo(file: File): ExtensionApkInfo?

    protected abstract fun readInstalledInfo(pkgName: String): ExtensionApkInfo?

    protected abstract fun placeApk(file: File, info: ExtensionApkInfo, replaced: Boolean)

    protected open fun onInstalled(pkgName: String) {}

    /** Local imports use the same package lock and signature/version policy as repository updates. */
    fun installLocal(input: java.io.InputStream): Flow<InstallStep> = flow {
        val job = currentCoroutineContext()[Job] ?: error("安装流程必须在协程中收集")
        localImports += job
        var temporary: File? = null
        var packageName: String? = null
        try {
            val copied = input.use { source ->
              val directory = File(extensionDir(), "local-import").apply { mkdirs() }
              val file = File.createTempFile("extension-", ".apk", directory).also { temporary = it }
              file.outputStream().use { target ->
                val buffer = ByteArray(16384); var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer); if (count < 0) break
                    size += count; require(size <= MAX_APK_BYTES) { "扩展安装包过大" }
                    target.write(buffer, 0, count)
                }
                target.fd.sync()
              }
              file
            }
            currentCoroutineContext().ensureActive()
            val info = readApkInfo(copied) ?: error("本地 APK 无法解析")
            requireValidExtensionPackageName(info.pkgName)
            packageName = info.pkgName
            installJobs.put(info.pkgName, job)?.takeUnless { it === job }?.cancel()
            installLocks.getOrPut(info.pkgName) { Mutex() }.withLock {
                emit(InstallStep.Installing)
                val installed = readInstalledInfo(info.pkgName)
                checkExtensionInstallable(info, installed)
                currentCoroutineContext().ensureActive()
                placeApk(copied, info, installed != null)
                onInstalled(info.pkgName)
                emit(InstallStep.Installed)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { emit(InstallStep.Error(e.message)) }
        finally {
            localImports -= job
            packageName?.let { installJobs.remove(it, job) }
            temporary?.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun install(extension: MangaExtension.Available): Flow<InstallStep> {
        return flow {
            val job = currentCoroutineContext()[Job] ?: error("安装流程必须在协程中收集")
            installJobs.put(extension.pkgName, job)?.takeUnless { it === job }?.cancel()
            var apkFile: File? = null
            try {
              installLocks.getOrPut(extension.pkgName) { Mutex() }.withLock {
                requireValidExtensionPackageName(extension.pkgName)
                emit(InstallStep.Downloading)

                discardInstallTemps(extensionDir(), extension.pkgName)
                val target = newInstallTempFile(extension.pkgName)
                apkFile = target
                downloadExtensionApk(extension.apkUrl, target) { emit(InstallStep.Progress(it)) }
                currentCoroutineContext().ensureActive()

                emit(InstallStep.Installing)
                val info = readApkInfo(target)
                    ?: throw ExtensionException("安装包无法解析: ${extension.pkgName}")
                check(info.pkgName == extension.pkgName) { "安装包名称与仓库记录不一致" }
                val current = readInstalledInfo(info.pkgName)
                checkExtensionInstallable(info, current)
                currentCoroutineContext().ensureActive()
                placeApk(target, info, replaced = current != null)
                emit(InstallStep.Installed)
                onInstalled(info.pkgName)
              }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {

                RuntimeLog.put("扩展安装失败: ${extension.pkgName}", e)
                emit(InstallStep.Error(e.message))
            } finally {
                installJobs.remove(extension.pkgName, job)
                apkFile?.delete()

                apkFile?.parentFile?.delete()
            }
        }.flowOn(Dispatchers.IO)
    }

    fun cancelInstall(pkgName: String) {
        installJobs[pkgName]?.cancel()
    }

    fun close() { installJobs.values.forEach { it.cancel() }; localImports.forEach { it.cancel() } }

    private fun newInstallTempFile(pkgName: String): File {
        val dir = installTempDirOf(extensionDir(), pkgName)
        dir.mkdirs()
        return File(dir, UUID.randomUUID().toString() + INSTALL_TEMP_SUFFIX)
    }

    companion object {

        private const val INSTALL_TEMP_DIR_NAME = "install-tmp"

        private const val INSTALL_TEMP_SUFFIX = ".apk"

        fun discardInstallTemps(extensionDir: File, pkgName: String) {
            installTempDirOf(extensionDir, pkgName).deleteRecursively()

            installTempRootOf(extensionDir).delete()
        }

        private fun installTempRootOf(extensionDir: File): File =
            File(extensionDir, INSTALL_TEMP_DIR_NAME)

        private fun installTempDirOf(extensionDir: File, pkgName: String): File =
            File(installTempRootOf(extensionDir), pkgName)
    }
}

