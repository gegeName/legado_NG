package io.legado.features.runtime

import io.legado.features.runtime.model.ContentWarning

object ExtensionManifestContract {

    const val EXTENSION_FEATURE = "tachiyomi.extension"
    const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"

    const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    const val ANIME_METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    const val ANIME_METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    const val ANIME_METADATA_NSFW = "tachiyomi.animeextension.nsfw"

    const val METADATA_NAME = "tachiyomix.name"
    const val METADATA_EXTENSION_LIB_STANDARD = "tachiyomi.extension-lib"
    const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    val ANIME_SUPPORTED_LIB_VERSIONS = listOf(14.0, 15.0, 16.0, 17.0)

    enum class ExtensionKind { MANGA, ANIME }

    fun isExtensionFeature(featureName: String?): Boolean =
        featureName == EXTENSION_FEATURE || featureName == ANIME_EXTENSION_FEATURE

    fun extensionKindOf(reqFeatureNames: Collection<String?>): ExtensionKind? = when {
        ANIME_EXTENSION_FEATURE in reqFeatureNames -> ExtensionKind.ANIME
        EXTENSION_FEATURE in reqFeatureNames -> ExtensionKind.MANGA
        else -> null
    }

    fun stripLabelPrefix(label: String): String =
        label.substringAfter("Aniyomi: ").substringAfter("Tachiyomi: ")

    fun parseLibVersion(declaredLib: Any?, versionName: String?): Double? {
        val parsedLib = when (declaredLib) {
            is Number -> declaredLib.toDouble()
            is String -> declaredLib.toDoubleOrNull()
            else -> null
        }?.takeUnless { it == 0.0 }
        return parsedLib ?: versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    fun parseDeclaredLibVersion(standardLib: Any?, legacyLib: Any?, versionName: String?): Double? =
        parseLibVersion(standardLib ?: legacyLib, versionName)

    fun supportedLibVersion(kind: ExtensionKind, libVersion: Double?): Double? =
        libVersion?.let { value ->
            supportedLibVersions(kind).firstOrNull { kotlin.math.abs(it - value) < 0.001 }
        }

    fun supportedLibVersions(kind: ExtensionKind): List<Double> =
        if (kind == ExtensionKind.ANIME) ANIME_SUPPORTED_LIB_VERSIONS else SUPPORTED_LIB_VERSIONS

    fun contentWarningOfDeclared(declared: Int): ContentWarning = when (declared) {
        1 -> ContentWarning.MIXED
        2 -> ContentWarning.NSFW
        else -> ContentWarning.SAFE
    }

    fun isNsfwFlag(nsfwFlag: Int?): Boolean = nsfwFlag == 1

    fun expandSourceClassNames(sourceClasses: String, pkgName: String): List<String> =
        sourceClasses.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) pkgName + it else it }
}

