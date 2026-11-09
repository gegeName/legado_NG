
package eu.kanade.tachiyomi

import android.os.Build
import io.legado.features.extensioncompat.ExtensionServices

object AppInfo {

    fun getVersionCode(): Int = runCatching {
        val info = ExtensionServices.require().application.packageManager.getPackageInfo(ExtensionServices.require().application.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    fun getVersionName(): String = runCatching {
        ExtensionServices.require().application.packageManager.getPackageInfo(ExtensionServices.require().application.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
