package io.legado.features.api.source

data class ExtensionRepository(val url: String, val name: String, val fingerprint: String, val isDefault: Boolean = false, val status: String = "尚未刷新")
data class ExtensionSummary(
    val id: String,
    val packageName: String,
    val name: String,
    val version: String,
    val state: String,
    val reason: String? = null,
    val fingerprint: String? = null,
    val updateAvailable: Boolean = false,
    val language: String = "",
    val repositoryUrl: String? = null,
    val iconUrl: String? = null,
)
data class InstallProgress(
    val state: String,
    /** Negative when the total size is unknown; downloadedBytes still advances. */
    val percent: Int = 0,
    val error: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
)
