package io.legado.features.api.source

data class ExtensionRepository(val url: String, val name: String, val fingerprint: String)
data class ExtensionSummary(val id: String, val packageName: String, val name: String, val version: String, val state: String, val reason: String? = null, val fingerprint: String? = null, val updateAvailable: Boolean = false)
data class InstallProgress(val state: String, val percent: Int = 0, val error: String? = null)
