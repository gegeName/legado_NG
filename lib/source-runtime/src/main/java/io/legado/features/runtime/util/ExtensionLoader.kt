package io.legado.features.runtime.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.legado.features.runtime.RuntimeLog
import kotlinx.coroutines.Dispatchers
import io.legado.features.runtime.EXTENSION_ICON_MAX_PIXEL_SIZE
import io.legado.features.runtime.ExtensionApkInfo
import io.legado.features.runtime.ExtensionInstallScaffold
import io.legado.features.runtime.ExtensionManifestContract
import io.legado.features.runtime.isValidExtensionPackageName
import io.legado.features.runtime.model.ContentWarning
import io.legado.features.runtime.model.MangaExtension
import io.legado.features.runtime.requireValidExtensionPackageName
import io.legado.features.runtime.trust.TrustHelper
import io.legado.features.runtime.RuntimeFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal object ExtensionLoader {

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val ICON_FALLBACK_SIZE = 192

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "feature_extensions/exts").apply {
        mkdirs()
        listFiles()?.filter { it.name.endsWith(".replace-backup") }?.forEach {
            RuntimeFiles.recover(File(this, it.name.removeSuffix(".replace-backup")))
        }
    }

    fun archiveApkInfo(context: Context, file: File): ExtensionApkInfo? =
        getArchivePackageInfo(context, file)?.toInstallInfo()

    fun installedApkInfo(context: Context, pkgName: String): ExtensionApkInfo? {
        requireValidExtensionPackageName(pkgName)
        val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        return if (privateFile.exists()) archiveApkInfo(context, privateFile)
        else getExtensionPackageInfoFromPkgName(context, pkgName)?.toInstallInfo()
    }

    private fun PackageInfo.toInstallInfo() = ExtensionApkInfo(
        pkgName = packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        isExtension = isPackageAnExtension(this),
        signatures = getSignatures(this).orEmpty(),
    )

    fun installPrivateExtensionFile(context: Context, file: File, pkgName: String, replaced: Boolean) {
        requireValidExtensionPackageName(pkgName)
        val target = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        RuntimeFiles.replaceFileAtomically(file, target)
        target.setReadOnly()
        if (replaced) {
            ExtensionInstallReceiver.notifyReplaced(context, pkgName)
        } else {
            ExtensionInstallReceiver.notifyAdded(context, pkgName)
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        requireValidExtensionPackageName(pkgName)
        val dir = getPrivateExtensionDir(context)
        ExtensionInstallScaffold.discardInstallTemps(dir, pkgName)
        val target = File(dir, "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (RuntimeFiles.deleteWithReplaceBackup(target)) {
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    fun getArchivePackageInfo(context: Context, file: File): PackageInfo? {
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
    }

    private const val PROBE_PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS

    fun isExtensionPackage(context: Context, pkgName: String): Boolean {
        if (pkgName.isBlank()) return false
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(pkgName, PROBE_PACKAGE_FLAGS)
            isPackageAnExtension(pkgInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            RuntimeLog.put("扩展包识别失败: $pkgName", e)
            false
        }
    }

    suspend fun loadExtensions(
        context: Context,
        alreadyLoaded: Map<String, MangaExtension.Loaded> = emptyMap(),
    ): List<MangaExtension.Installed> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {

                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.also { pkg -> pkg.applicationInfo?.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)

            .distinctBy { it.packageInfo.packageName }
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            extPkgs
                .map { info ->
                    async(start = CoroutineStart.LAZY) {
                        loadExtensionCatching(
                            context = context,
                            extensionInfo = info,
                            alreadyLoaded = alreadyLoaded[info.packageInfo.packageName],
                        )
                    }
                }
                .awaitAll()
        }
    }

    fun readExtensionIcon(context: Context, pkgName: String): ByteArray? =
        runCatching { readIconPngBytes(context, pkgName) }
            .getOrElse { e ->

                runCatching { RuntimeLog.put("扩展图标读取失败: $pkgName", e) }
                null
            }

    private fun readIconPngBytes(context: Context, pkgName: String): ByteArray? {
        val appInfo = getExtensionPackageInfoFromPkgName(context, pkgName)?.applicationInfo
            ?: return null
        val resId = appInfo.icon
        if (resId == 0) return null
        val resources = context.packageManager.getResourcesForApplication(appInfo)

        if (declaredIconTooLarge(resources, resId)) return null
        val drawable = resources.getDrawable(resId, null) ?: return null
        return drawable.toPngBytes()
    }

    private fun declaredIconTooLarge(resources: Resources, resId: Int): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeResource(resources, resId, options) }
        return options.outWidth > EXTENSION_ICON_MAX_PIXEL_SIZE ||
            options.outHeight > EXTENSION_ICON_MAX_PIXEL_SIZE
    }

    private fun Drawable.toPngBytes(): ByteArray? = runCatching {
        val width = intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_SIZE
        val height = intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_SIZE

        val tooLarge = width > EXTENSION_ICON_MAX_PIXEL_SIZE || height > EXTENSION_ICON_MAX_PIXEL_SIZE
        val targetWidth = if (tooLarge) ICON_FALLBACK_SIZE else width
        val targetHeight = if (tooLarge) ICON_FALLBACK_SIZE else height
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, targetWidth, targetHeight)
        draw(canvas)
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    }.onFailure {
        RuntimeLog.put("扩展图标编码失败", it)
    }.getOrNull()

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {

        if (!isValidExtensionPackageName(pkgName)) return null
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.also { it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath) }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(
            sharedPkg?.let { ExtensionInfo(it, isShared = true) },
            privatePkg?.let { ExtensionInfo(it, isShared = false) },
        )?.packageInfo
    }

    private suspend fun loadExtensionCatching(
        context: Context,
        extensionInfo: ExtensionInfo,
        alreadyLoaded: MangaExtension.Loaded? = null,
    ): MangaExtension.Installed {
        return try {
            loadExtension(context, extensionInfo, alreadyLoaded)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val pkgInfo = extensionInfo.packageInfo
            RuntimeLog.put("扩展加载出错: ${pkgInfo.packageName}", e)
            MangaExtension.NotLoaded(
                name = pkgInfo.packageName,
                pkgName = pkgInfo.packageName,
                versionName = pkgInfo.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
                isShared = extensionInfo.isShared,
                contentWarning = ContentWarning.SAFE,
                signatures = getSignatures(pkgInfo).orEmpty(),
                reason = MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
            )
        }
    }

    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        alreadyLoaded: MangaExtension.Loaded? = null,
    ): MangaExtension.Installed {
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo
        val metaData = appInfo?.metaData
        val pkgName = pkgInfo.packageName
        val kind = extensionKindOf(pkgInfo)

        val extName = metaData?.getString(ExtensionManifestContract.METADATA_NAME)
            ?: appInfo?.let {
                ExtensionManifestContract.stripLabelPrefix(
                    pkgManager.getApplicationLabel(it).toString()
                )
            }
            ?: pkgName
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val signatures = getSignatures(pkgInfo).orEmpty()
        val nsfwKey =
            if (kind == ExtensionManifestContract.ExtensionKind.ANIME) {
                ExtensionManifestContract.ANIME_METADATA_NSFW
            } else {
                ExtensionManifestContract.METADATA_NSFW
            }
        val contentWarning = when {
            metaData == null -> ContentWarning.SAFE
            metaData.containsKey(ExtensionManifestContract.METADATA_CONTENT_WARNING) ->
                ExtensionManifestContract.contentWarningOfDeclared(metaData.getInt(ExtensionManifestContract.METADATA_CONTENT_WARNING))
            ExtensionManifestContract.isNsfwFlag(metaData.getInt(nsfwKey)) -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        fun notLoaded(
            reason: MangaExtension.NotLoaded.Reason,
            libVersion: Double? = null,
        ) = MangaExtension.NotLoaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName.orEmpty(),
            versionCode = versionCode,
            isShared = extensionInfo.isShared,
            contentWarning = contentWarning,
            signatures = signatures,
            libVersion = libVersion,
            reason = reason,
        )

        if (appInfo == null || metaData == null) {
            RuntimeLog.put("扩展缺少应用信息: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed)
        }

        if (versionName.isNullOrEmpty()) {
            RuntimeLog.put("扩展缺少 versionName: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed)
        }

        
        val declaredLibVersion = ExtensionManifestContract.parseLibVersion(
            metaData.getFloat(ExtensionManifestContract.METADATA_EXTENSION_LIB), versionName
        )
        val libVersion = ExtensionManifestContract.supportedLibVersion(kind, declaredLibVersion)
        if (libVersion == null) {
            RuntimeLog.put(
                "扩展库版本 $declaredLibVersion 不受支持, " +
                    "仅支持 ${ExtensionManifestContract.supportedLibVersions(kind).joinToString()}: $extName"
            )
            return notLoaded(MangaExtension.NotLoaded.Reason.UnsupportedLibVersion, declaredLibVersion)
        }

        if (signatures.isEmpty()) {
            RuntimeLog.put("扩展未签名: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Unsigned, libVersion)
        } else if (!TrustHelper.isTrusted(pkgName, versionCode, signatures)) {
            RuntimeLog.put("扩展签名未受信任: $pkgName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Untrusted(signatures.last()), libVersion)
        }

        // Reloads rebuild the loader even for the same version: content, trust and signatures can change.

        val classLoader = try {
            DelegateLastClassLoaderCompat(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            RuntimeLog.put("扩展类加载器创建失败: $extName ($pkgName)", e)
            return notLoaded(
                MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
                libVersion,
            )
        }

        val sourceClassKey =
            if (kind == ExtensionManifestContract.ExtensionKind.ANIME) {
                ExtensionManifestContract.ANIME_METADATA_SOURCE_CLASS
            } else {
                ExtensionManifestContract.METADATA_SOURCE_CLASS
            }
        val sourceClasses = metaData.getString(sourceClassKey)
        if (sourceClasses.isNullOrBlank()) {
            RuntimeLog.put("扩展缺少源类声明: $extName")
            return notLoaded(MangaExtension.NotLoaded.Reason.Malformed, libVersion)
        }

        val mangaSources = ArrayList<Source>()
        val animeSources = ArrayList<AnimeSource>()
        ExtensionManifestContract.expandSourceClassNames(sourceClasses, pkgName)
            .forEach { className ->
                try {
                    when (val obj = Class.forName(className, false, classLoader)
                        .getDeclaredConstructor().newInstance()) {
                        is Source -> mangaSources += obj
                        is SourceFactory -> mangaSources += obj.createSources()
                        is AnimeSource -> animeSources += obj
                        is AnimeSourceFactory -> animeSources += obj.createSources()
                        else -> throw Exception("未知的源类类型: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    RuntimeLog.put("扩展源实例化失败: $extName ($className)", e)
                    return notLoaded(
                        MangaExtension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
                        libVersion,
                    )
                }
            }

        val langs = when {
            animeSources.isNotEmpty() -> animeSources.map { it.lang }
            else -> mangaSources.filterIsInstance<CatalogueSource>().map { it.lang }
        }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val factoryKey =
            if (kind == ExtensionManifestContract.ExtensionKind.ANIME) {
                ExtensionManifestContract.ANIME_METADATA_SOURCE_FACTORY
            } else {
                ExtensionManifestContract.METADATA_SOURCE_FACTORY
            }
        return MangaExtension.Loaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = contentWarning,
            sources = mangaSources,
            animeSources = animeSources,
            pkgFactory = metaData.getString(factoryKey),
            isShared = extensionInfo.isShared,
            signatures = signatures,
        )
    }

    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any {
            ExtensionManifestContract.isExtensionFeature(it.name)
        }
    }

    private fun extensionKindOf(pkgInfo: PackageInfo): ExtensionManifestContract.ExtensionKind {
        return ExtensionManifestContract.extensionKindOf(pkgInfo.reqFeatures.orEmpty().mapNotNull { it.name })
            ?: ExtensionManifestContract.ExtensionKind.MANGA
    }

    fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return signatures?.map { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}

private val Throwable.rootMessage: String
    get() {
        val root = generateSequence(this) { it.cause }.last()
        return listOfNotNull(root::class.simpleName, root.message).joinToString(": ")
    }

