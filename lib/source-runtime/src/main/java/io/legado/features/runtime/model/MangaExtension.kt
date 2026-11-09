package io.legado.features.runtime.model

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.Source

sealed interface MangaExtension {

    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double?
    val lang: String?
    val contentWarning: ContentWarning

    sealed interface Installed : MangaExtension {

        val isShared: Boolean

        val signatures: List<String>

        val hasUpdate: Boolean

        fun findListing(available: Collection<Available>): Available? {
            return available
                .filter { it.pkgName == pkgName && it.repo.signingKeyFingerprint.lowercase() in signatures }
                .maxWithOrNull(compareBy<Available> { it.versionCode }.thenBy { it.libVersion })
        }

        fun findUpdate(available: Collection<Available>): Available? {
            val installedLibVersion = libVersion
            return findListing(available)?.takeIf {
                it.versionCode > versionCode || (installedLibVersion != null && it.libVersion > installedLibVersion)
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        val sources: List<Source>,
        val apkUrl: String,
        val iconUrl: String,
        val repo: MangaExtensionRepo,
    ) : MangaExtension {

        @kotlinx.serialization.Serializable
        data class Source(
            val id: Long,
            val lang: String,
            val name: String,
            val baseUrl: String = "",
        )
    }

    data class Loaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        override val isShared: Boolean,
        override val signatures: List<String>,
        val pkgFactory: String?,
        val sources: List<Source>,

        val animeSources: List<AnimeSource> = emptyList(),
        override val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val repo: MangaExtensionRepo? = null,
    ) : Installed

    data class NotLoaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val isShared: Boolean,
        override val contentWarning: ContentWarning,
        override val signatures: List<String>,
        override val libVersion: Double? = null,
        override val lang: String? = null,
        override val hasUpdate: Boolean = false,
        val repo: MangaExtensionRepo? = null,
        val reason: Reason,
    ) : Installed {

        sealed interface Reason {

            data class Untrusted(val signatureHash: String) : Reason

            data object Filtered : Reason

            data object Unsigned : Reason

            data object UnsupportedLibVersion : Reason

            data object Malformed : Reason

            data class Failed(val message: String, val stackTrace: String) : Reason
        }
    }
}

enum class ContentWarning {
    SAFE, MIXED, NSFW
}

sealed class InstallStep {
    data class Progress(val progress: Int = 0) : InstallStep()
    data object Downloading : InstallStep()
    data object Installing : InstallStep()
    data object Installed : InstallStep()
    data class Error(val error: String?) : InstallStep()
}

