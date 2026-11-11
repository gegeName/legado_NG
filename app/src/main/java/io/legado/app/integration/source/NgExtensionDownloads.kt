package io.legado.app.integration.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.api.source.InstallProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ExtensionDownload(
    val progress: InstallProgress,
    val running: Boolean,
    val failureStage: ExtensionDownloadFailureStage? = null,
)

/** Package identity survives the available -> imported list transition. All access is on Main. */
internal class NgExtensionDownloads(
    private val scope: CoroutineScope,
    private val install: (String) -> Flow<InstallProgress>,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, ExtensionDownload>>(emptyMap())
    val states: StateFlow<Map<String, ExtensionDownload>> = mutableStates.asStateFlow()

    fun start(packageName: String, id: String) {
        if (jobs.containsKey(packageName)) return
        mutableStates.update {
            it + (packageName to ExtensionDownload(InstallProgress("downloading"), true, ExtensionDownloadFailureStage.DOWNLOAD))
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var failureStage = ExtensionDownloadFailureStage.DOWNLOAD
            try {
                var terminal = false
                install(id).collect { progress ->
                    terminal = progress.state in setOf("installed", "error")
                    if (progress.state == "installing") failureStage = ExtensionDownloadFailureStage.INSTALL
                    mutableStates.update {
                        it + (packageName to ExtensionDownload(
                            progress = progress,
                            running = true,
                            failureStage = failureStage.takeIf { progress.state == "error" },
                        ))
                    }
                }
                if (!terminal) {
                    mutableStates.update {
                        it + (packageName to ExtensionDownload(
                            InstallProgress("error", error = "导入未完成，请重试"),
                            false,
                            ExtensionDownloadFailureStage.INSTALL,
                        ))
                    }
                }
            } catch (e: CancellationException) {
                mutableStates.update { it + (packageName to ExtensionDownload(InstallProgress("cancelled"), false)) }
                throw e
            } catch (e: Exception) {
                mutableStates.update {
                    it + (packageName to ExtensionDownload(
                        InstallProgress("error", error = e.message ?: "下载失败"),
                        false,
                        failureStage,
                    ))
                }
            }
        }
        jobs[packageName] = job
        job.invokeOnCompletion { cause ->
            mutableStates.update { values ->
                values[packageName]?.let { current ->
                    val progress = if (cause is CancellationException && current.progress.state !in setOf("installed", "error")) InstallProgress("cancelled") else current.progress
                    values + (packageName to current.copy(progress = progress, running = false))
                } ?: values
            }
            jobs.remove(packageName)
        }
        job.start()
    }

    fun cancel(packageName: String) { jobs[packageName]?.cancel() }

    fun clear(packageName: String) {
        mutableStates.update { it - packageName }
    }
}

internal class NgExtensionManagerViewModel : ViewModel() {
    val downloads = NgExtensionDownloads(viewModelScope, ExtensionFeatures::install)
    val repositoryRefresh = NgExtensionRepositoryRefresh(viewModelScope) { repository ->
        if (repository.isEmpty()) ExtensionFeatures.refresh()
        else ExtensionFeatures.refreshRepository(repository)
    }
}
