package io.legado.app.integration.video

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.legado.features.adapter.WebVideoFeatures

/** Reference SmartDataSource policy: playlists, segments and encryption keys bypass disk cache. */
internal class NgVideoDataSource(private val cached: DataSource, private val upstream: DataSource) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        upstream.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        active = if (WebVideoFeatures.streamResource(dataSpec.uri.toString())) upstream else cached
        return checkNotNull(active).open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { try { active?.close() } finally { active = null } }
}
