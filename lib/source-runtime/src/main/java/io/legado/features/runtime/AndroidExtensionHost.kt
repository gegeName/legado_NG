package io.legado.features.runtime

import android.content.Context
import io.legado.features.runtime.installer.ExtensionInstaller
import io.legado.features.runtime.model.InstallStep
import io.legado.features.runtime.model.MangaExtension
import io.legado.features.runtime.util.ExtensionInstallReceiver
import io.legado.features.runtime.util.ExtensionLoader
import kotlinx.coroutines.flow.Flow

class AndroidExtensionHost(context: Context) : ExtensionHost {

    private val appContext = context.applicationContext
    private val installer = ExtensionInstaller(appContext)
    private var receiver: ExtensionInstallReceiver? = null

    companion object {
        @Volatile
        private var instance: AndroidExtensionHost? = null

        fun get(context: Context): AndroidExtensionHost =
            instance ?: synchronized(this) {
                instance ?: AndroidExtensionHost(context.applicationContext).also { instance = it }
            }
    }

    @Synchronized override fun onInitialized() {
        if (receiver != null) return
        receiver = ExtensionInstallReceiver(appContext) { ExtensionRuntime.reloadExtensions() }.also { it.register() }
    }

    @Synchronized override fun close() {
        receiver?.close()
        receiver = null
        installer.close()
    }

    override suspend fun loadExtensions(
        alreadyLoaded: Map<String, MangaExtension.Loaded>,
    ): List<MangaExtension.Installed> = ExtensionLoader.loadExtensions(appContext, alreadyLoaded)

    override fun install(extension: MangaExtension.Available): Flow<InstallStep> {
        return installer.install(extension)
    }

    override fun cancelInstall(pkgName: String) {
        installer.cancelInstall(pkgName)
    }
    fun installLocal(input: java.io.InputStream) = installer.installLocal(input)

    override fun uninstall(extension: MangaExtension.Installed) {
        if (extension.isShared) {

            installer.uninstallSharedApk(extension.pkgName)
        } else {
            ExtensionLoader.uninstallPrivateExtension(appContext, extension.pkgName)
            ExtensionRuntime.reloadExtensions()
        }
    }

    override fun readExtensionIcon(pkgName: String): ByteArray? =
        ExtensionLoader.readExtensionIcon(appContext, pkgName)
}

