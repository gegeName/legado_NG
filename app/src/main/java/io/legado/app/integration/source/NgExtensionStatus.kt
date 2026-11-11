package io.legado.app.integration.source

import io.legado.features.api.source.ExtensionSummary

internal enum class NgExtensionStatus(val label: String) {
    NOT_DOWNLOADED("未下载"),
    DOWNLOADING("下载中"),
    DOWNLOADED("下载完成"),
    UPDATE_AVAILABLE("可更新"),
    DOWNLOAD_FAILED("下载失败"),
    DOWNLOAD_ERROR("下载错误"),
}

internal enum class ExtensionDownloadFailureStage {
    DOWNLOAD,
    INSTALL,
}

internal fun extensionStatus(
    entry: ExtensionSummary,
    download: ExtensionDownload?,
): NgExtensionStatus = when {
    download?.running == true -> NgExtensionStatus.DOWNLOADING
    download?.progress?.state == "error" -> when (download.failureStage) {
        ExtensionDownloadFailureStage.DOWNLOAD -> NgExtensionStatus.DOWNLOAD_FAILED
        else -> NgExtensionStatus.DOWNLOAD_ERROR
    }
    entry.updateAvailable -> NgExtensionStatus.UPDATE_AVAILABLE
    download?.progress?.state == "installed" || entry.state != "available" -> NgExtensionStatus.DOWNLOADED
    else -> NgExtensionStatus.NOT_DOWNLOADED
}
