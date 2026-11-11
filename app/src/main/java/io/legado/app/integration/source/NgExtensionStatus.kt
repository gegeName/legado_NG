package io.legado.app.integration.source

import io.legado.features.api.source.ExtensionSummary

internal enum class NgExtensionStatus(val label: String) {
    UPDATE_AVAILABLE("可更新"),
    NOT_DOWNLOADED("未下载"),
    DOWNLOADING("下载中"),
    DOWNLOADED("下载完成"),
    DOWNLOAD_FAILED("下载失败"),
}

internal fun extensionStatus(
    entry: ExtensionSummary,
    download: ExtensionDownload?,
): NgExtensionStatus = when {
    download?.running == true -> NgExtensionStatus.DOWNLOADING
    download?.progress?.state == "error" -> NgExtensionStatus.DOWNLOAD_FAILED
    entry.updateAvailable && download?.progress?.state != "installed" -> NgExtensionStatus.UPDATE_AVAILABLE
    download?.progress?.state == "installed" || entry.state != "available" -> NgExtensionStatus.DOWNLOADED
    else -> NgExtensionStatus.NOT_DOWNLOADED
}

internal fun extensionMatchesStatus(
    entry: ExtensionSummary,
    download: ExtensionDownload?,
    filter: NgExtensionStatus,
): Boolean = if (filter == NgExtensionStatus.UPDATE_AVAILABLE) {
    entry.updateAvailable && download?.progress?.state != "installed"
} else {
    extensionStatus(entry, download) == filter
}
