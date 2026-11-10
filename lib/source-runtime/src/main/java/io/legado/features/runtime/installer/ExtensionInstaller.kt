package io.legado.features.runtime.installer

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.legado.features.runtime.ExtensionApkInfo
import io.legado.features.runtime.ExtensionInstallScaffold
import io.legado.features.runtime.util.ExtensionLoader
import java.io.File

internal class ExtensionInstaller(private val context: Context) : ExtensionInstallScaffold() {

    override fun extensionDir(): File = ExtensionLoader.getPrivateExtensionDir(context)

    override fun readApkInfo(file: File): ExtensionApkInfo? =
        ExtensionLoader.archiveApkInfo(context, file)

    override fun readInstalledInfo(pkgName: String): ExtensionApkInfo? =
        ExtensionLoader.installedApkInfo(context, pkgName)

    override fun placeApk(file: File, info: ExtensionApkInfo, replaced: Boolean) {
        ExtensionLoader.installPrivateExtensionFile(context, file, info.pkgName, replaced)
    }

    override fun onInstalled(pkgName: String) {
        io.legado.features.runtime.ExtensionRuntime.reloadExtensions()
    }

    fun uninstallSharedApk(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE, "package:$pkgName".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

