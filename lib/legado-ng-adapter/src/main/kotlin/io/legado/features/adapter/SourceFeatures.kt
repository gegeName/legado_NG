package io.legado.features.adapter

import io.legado.features.api.source.SourceKey
import io.legado.features.api.source.SourceProvider
import io.legado.features.api.source.FilterSession
import io.legado.features.api.host.ConfigPort
import io.legado.features.runtime.SourceFilterStore

/** One source router serves all existing NG hooks. */
object SourceFeatures {
    private var config: ConfigPort? = null
    fun configure(config: ConfigPort) { this.config = config }
    suspend fun filters(key: SourceKey, search: Boolean = false): FilterSession {
        val provider = provider(key)
        val session = if (search) provider.searchFilters(key) else provider.filters(key)
        try { return config?.let { SourceFilterStore(it).apply(session) } ?: session }
        catch (e: Throwable) { session.close(); throw e }
    }
    fun saveFilters(session: FilterSession) = SourceFilterStore(checkNotNull(config)).save(session)
    fun provider(key: SourceKey): SourceProvider = when (key.provider) {
        "tvbox" -> TvBoxFeatures.provider()
        else -> ExtensionFeatures.provider(key)
    }
}
