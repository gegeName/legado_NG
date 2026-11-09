@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.legado.features.runtime.model

import io.legado.features.runtime.ExtensionManifestContract
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class MangaExtensionRepo(
    val indexUrl: String,
    val name: String,

    val signingKeyFingerprint: String,
    val isDefault: Boolean = false,

    val kind: RepoKind = RepoKind.MANGA,
)

@Serializable
enum class RepoKind { MANGA, ANIME }

@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<NetworkExtensionSource>? = null,
)

@Serializable
data class NetworkExtensionSource(

    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String = "",
)

@Serializable
data class NetworkRepoInfo(
    @kotlinx.serialization.SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta? = null,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String = "",
        val signingKeyFingerprint: String,
    )
}

fun NetworkLegacyExtension.toAvailable(repo: MangaExtensionRepo): MangaExtension.Available {
    val storeBaseUrl = repo.indexUrl.removeSuffix("index.min.json").trimEnd('/')
    val extName = ExtensionManifestContract.stripLabelPrefix(name)
    return MangaExtension.Available(
        name = extName,
        pkgName = pkg,
        apkUrl = "$storeBaseUrl/apk/$apk",
        iconUrl = "$storeBaseUrl/icon/$pkg.png",
        libVersion = version.substringBeforeLast('.').toDouble(),
        versionCode = code,
        versionName = version,
        lang = lang,
        contentWarning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE,
        sources = sources.orEmpty().map { source ->
            MangaExtension.Available.Source(
                id = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = source.baseUrl,
            )
        }.ifEmpty {
            listOf(
                MangaExtension.Available.Source(
                    id = 0,
                    name = extName,
                    lang = lang,
                )
            )
        },
        repo = repo,
    )
}

@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources? = null,
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",

        @ProtoNumber(7) val contentWarning: Int = 0,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )
}

fun NetworkExtensionStore.ExtensionList.toAvailable(repo: MangaExtensionRepo): List<MangaExtension.Available> =
    extensions.map { extension ->
        val langs = extension.sources.map { it.language }.toSet()
        val lang = if (langs.size == 1) langs.first() else "all"
        MangaExtension.Available(
            name = ExtensionManifestContract.stripLabelPrefix(extension.name),
            pkgName = extension.packageName,
            apkUrl = extension.resources?.apkUrl.orEmpty(),
            iconUrl = extension.resources?.iconUrl.orEmpty(),
            libVersion = extension.extensionLib.toDoubleOrNull()
                ?: extension.versionName.substringBeforeLast('.').toDoubleOrNull()
                ?: 0.0,
            versionCode = extension.versionCode,
            versionName = extension.versionName,
            lang = lang,
            contentWarning = when (extension.contentWarning) {
                2 -> ContentWarning.MIXED
                3 -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            },
            sources = extension.sources.map { source ->
                MangaExtension.Available.Source(
                    id = source.id,
                    name = source.name,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            }.ifEmpty {
                listOf(
                    MangaExtension.Available.Source(
                        id = 0,
                        name = extension.name,
                        lang = lang,
                    )
                )
            },
            repo = repo,
        )
    }

