package io.legado.features.runtime

import io.legado.features.runtime.model.InstallStep
import io.legado.features.runtime.model.MangaExtension
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

const val EXTENSION_ICON_MAX_PIXEL_SIZE = 512

const val EXTENSION_ICON_MAX_BYTES = 2 * 1024 * 1024

interface ExtensionHost : AutoCloseable {

    suspend fun loadExtensions(
        alreadyLoaded: Map<String, MangaExtension.Loaded>,
    ): List<MangaExtension.Installed>

    fun install(extension: MangaExtension.Available): Flow<InstallStep>

    fun cancelInstall(pkgName: String)

    fun uninstall(extension: MangaExtension.Installed)

    fun readExtensionIcon(pkgName: String): ByteArray?

    fun onInitialized() {}
    override fun close() {}
}

object ExtensionHostProviders {
    @Volatile
    private var impl: ExtensionHost? = null

    fun register(impl: ExtensionHost) {
        this.impl = impl
    }

    fun getOrNull(): ExtensionHost? = impl
}

