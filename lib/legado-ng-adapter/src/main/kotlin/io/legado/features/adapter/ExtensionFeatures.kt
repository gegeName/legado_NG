package io.legado.features.adapter

import android.app.Application
import android.content.Context
import android.webkit.WebView
import eu.kanade.tachiyomi.registerExtensionCompat
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.source.*
import io.legado.features.extensioncompat.ExtensionPlatform
import io.legado.features.extensioncompat.ExtensionServices
import io.legado.features.mihon.MihonProvider
import io.legado.features.aniyomi.AniyomiProvider
import io.legado.features.runtime.*
import io.legado.features.runtime.model.MangaExtension
import io.legado.features.runtime.model.InstallStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Android-only assembly port. Extension ABI types remain private to the implementation. */
interface ExtensionFeatureHost {
    val application: Application
    val client: OkHttpClient
    val config: ConfigPort
    fun userAgent(): String
    fun log(message: String, error: Throwable?)
    fun supportsWebView(context: Context): Boolean
    fun configureWebView(view: WebView, userAgent: String)
    fun isWebViewOutdated(view: WebView): Boolean
    fun syncCookies(url: String, cookie: String)
}

object ExtensionFeatures : AutoCloseable {
    private var host: ExtensionFeatureHost? = null
    private val manga = MihonProvider()
    private val anime = AniyomiProvider()
    private val sourceRevision = MutableStateFlow(0L)
    @Synchronized fun start(host: ExtensionFeatureHost) {
        if (this.host != null) return
        RuntimeServices.initialize(host.config)
        ExtensionServices.register(object : ExtensionPlatform {
            override val application = host.application
            override val client = host.client
            override fun userAgent() = host.userAgent()
            override fun log(message: String, error: Throwable?) = host.log(message, error)
            override fun supportsWebView(context: Context) = host.supportsWebView(context)
            override fun configureWebView(view: WebView, userAgent: String) = host.configureWebView(view, userAgent)
            override fun isWebViewOutdated(view: WebView) = host.isWebViewOutdated(view)
            override fun syncCookies(url: String, cookie: String) = host.syncCookies(url, cookie)
        })
        registerExtensionCompat(host.application)
        ExtensionHostProviders.register(AndroidExtensionHost.get(host.application))
        this.host = host
        ExtensionRuntime.init()
    }
    fun provider(key: SourceKey): SourceProvider = when (key.provider) {
        "mihon" -> manga
        "aniyomi" -> anime
        else -> error("未注册的扩展提供方：${key.provider}")
    }
    suspend fun sources(): List<SourceDescriptor> = withContext(Dispatchers.IO) { manga.sources() + anime.sources() }
    fun sourceChanges(): Flow<List<SourceDescriptor>> = combine(ExtensionRuntime.sources, ExtensionRuntime.animeSources, sourceRevision) { _, _, _ -> sources() }
    suspend fun openImage(ref: ImageReference): ImageStream {
        var acquired: ImageStream? = null
        try { return withContext(Dispatchers.IO) { manga.openImage(ref).also { acquired = it } } }
        catch (e: Throwable) { acquired?.close(); throw e }
    }
    fun imageConfiguration(key: SourceKey): String = ExtensionRuntime.loadedExtensions.value.values
        .firstOrNull { extension -> extension.sources.any { it.id.toString() == key.nativeId } }
        ?.let { "${it.pkgName}:${it.versionCode}:${it.signatures.joinToString()}" }.orEmpty() + ":" + preferenceFingerprint(key)
    private fun preferenceFingerprint(key: SourceKey): String {
        val app = host?.application ?: return ""
        val values = app.getSharedPreferences("source_${key.nativeId}", Context.MODE_PRIVATE).all.toSortedMap().entries.joinToString("|") { (name, value) ->
            "$name=${if (value is Set<*>) value.map { it.toString() }.sorted() else value}"
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun setEnabled(key: SourceKey, enabled: Boolean) {
        if (key.provider == "aniyomi") ExtensionRuntime.setAnimeSourceEnabled(key.nativeId.toLong(), enabled)
        else ExtensionRuntime.setSourceEnabled(key.nativeId.toLong(), enabled)
        sourceRevision.update { it + 1 }
    }
    private fun MangaExtension.summary(): ExtensionSummary = when (this) {
        is MangaExtension.Available -> ExtensionSummary("$pkgName@${repo.signingKeyFingerprint}", pkgName, name, versionName, "available", fingerprint = repo.signingKeyFingerprint)
        is MangaExtension.Loaded -> ExtensionSummary(pkgName, pkgName, name, versionName, "loaded", fingerprint = signatures.lastOrNull(), updateAvailable = hasUpdate)
        is MangaExtension.NotLoaded -> ExtensionSummary(pkgName, pkgName, name, versionName, "notLoaded", reason.toString(), (reason as? MangaExtension.NotLoaded.Reason.Untrusted)?.signatureHash, hasUpdate)
    }
    fun changes(): Flow<List<ExtensionSummary>> = combine(ExtensionRuntime.loadedExtensions, ExtensionRuntime.notLoadedExtensions, ExtensionRuntime.availableExtensions) { loaded, failed, available ->
        loaded.values.map { it.summary() } + failed.values.map { it.summary() } + available.filter { it.pkgName !in loaded && it.pkgName !in failed }.map { it.summary() }
    }
    fun repositories(): List<ExtensionRepository> = ExtensionRuntime.getRepos().map { ExtensionRepository(it.indexUrl, it.name, it.signingKeyFingerprint) }
    suspend fun refresh() = withContext(Dispatchers.IO) { ExtensionRuntime.findAvailableExtensions(); Unit }
    suspend fun addRepository(url: String) = ExtensionRuntime.addRepo(url).map { ExtensionRepository(it.indexUrl, it.name, it.signingKeyFingerprint) }
    fun removeRepository(url: String) = ExtensionRuntime.removeRepo(url)
    fun reload() = ExtensionRuntime.reloadExtensions()
    suspend fun restored() = ExtensionRuntime.onRestoreFinished()
    fun trust(packageName: String, fingerprint: String) {
        val item = ExtensionRuntime.notLoadedExtensions.value[packageName] ?: error("扩展已变化，请刷新")
        require((item.reason as? MangaExtension.NotLoaded.Reason.Untrusted)?.signatureHash == fingerprint) { "签名已变化，请重新检查" }
        ExtensionRuntime.trust(item)
    }
    fun uninstall(packageName: String) {
        val ext = ExtensionRuntime.loadedExtensions.value[packageName] ?: ExtensionRuntime.notLoadedExtensions.value[packageName] ?: error("扩展未安装")
        ExtensionRuntime.uninstallExtension(ext)
    }
    fun install(id: String): Flow<InstallProgress> {
        val installed = ExtensionRuntime.loadedExtensions.value[id] ?: ExtensionRuntime.notLoadedExtensions.value[id]
        val ext = if (installed != null) installed.findUpdate(ExtensionRuntime.availableExtensions.value)
            else ExtensionRuntime.availableExtensions.value.firstOrNull { it.summary().id == id }
        checkNotNull(ext) { "未找到安装或更新记录" }
        return ExtensionRuntime.installExtension(ext).map {
            when (it) {
                InstallStep.Downloading -> InstallProgress("downloading")
                InstallStep.Installing -> InstallProgress("installing")
                InstallStep.Installed -> InstallProgress("installed")
                is InstallStep.Progress -> InstallProgress("downloading", it.progress)
                is InstallStep.Error -> InstallProgress("error", error = it.error)
            }
        }
    }
    fun cancelInstall(packageName: String) = ExtensionRuntime.cancelInstallUpdateExtension(packageName)
    fun installLocal(input: java.io.InputStream): Flow<InstallProgress> = AndroidExtensionHost.get(checkNotNull(host).application).installLocal(input).map { step ->
        when (step) {
            InstallStep.Downloading -> InstallProgress("downloading")
            InstallStep.Installing -> InstallProgress("installing")
            InstallStep.Installed -> { reload(); InstallProgress("installed") }
            is InstallStep.Progress -> InstallProgress("downloading", step.progress)
            is InstallStep.Error -> InstallProgress("error", error = step.error)
        }
    }
    override fun close() = ExtensionRuntime.close()
}
