package io.legado.app.integration.source

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.widget.dialog.applyNgWindow
import io.legado.features.adapter.TvBoxFeatures
import io.legado.features.api.source.SourceDescriptor
import kotlinx.coroutines.*

object NgTvBoxManagerDialog {
    fun show(context: Context): ComponentDialog {
        val dialog = ComponentDialog(context)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { NgAppTheme(updateSystemBars = false) {
                val scope = rememberCoroutineScope()
                var url by remember { mutableStateOf("") }
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var configurations by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                var sources by remember { mutableStateOf<List<SourceDescriptor>>(emptyList()) }
                suspend fun refresh() {
                    val configs = TvBoxFeatures.configurations()
                    val descriptors = TvBoxFeatures.provider().sources()
                    NgExtensionSourceBridge.syncSources(descriptors, setOf("tvbox"), applyEnabled = true)
                    withContext(Dispatchers.Main) { configurations = configs; sources = descriptors }
                }
                fun perform(action: suspend () -> Unit) {
                    if (busy) return
                    scope.launch {
                        busy = true
                        try { withContext(Dispatchers.IO) { action(); refresh() }; status = "已更新" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = e.message.orEmpty() }
                        finally { busy = false }
                    }
                }
                LaunchedEffect(Unit) {
                    try { withContext(Dispatchers.IO) { refresh() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message.orEmpty() }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) perform {
                        val text = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16384)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= 8 * 1024 * 1024) { "TVBox 配置超过 8 MiB" }
                                output.write(buffer, 0, count)
                            }
                            output.toString("UTF-8")
                        }
                        TvBoxFeatures.import(text, url.ifBlank { uri.toString() })
                    }
                }
                NgDialog(title = "TVBox 配置", actions = {
                    NgDialogTextActionButton(text = "关闭", onClick = dialog::dismiss)
                }) {
                    Column(Modifier.heightIn(max = 540.dp)) {
                        NgFormField(label = "配置 URL / 本地文件的原始 URL", value = url, onValueChange = { url = it })
                        Row {
                            NgDialogTextActionButton(text = "导入 URL", enabled = !busy && url.isNotBlank(), onClick = { perform { TvBoxFeatures.importUrl(url) } })
                            NgDialogTextActionButton(text = "导入文件", enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                        }
                        TvBoxFeatures.loadProblem()?.let { Text("本地配置读取失败，原始数据已保留：$it") }
                        if (status.isNotBlank()) Text(status)
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(configurations, key = { it.first }) { (namespace, base) ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text(base)
                                    Row {
                                        NgDialogTextActionButton(text = "更新配置与 JAR", enabled = !busy && base.startsWith("http"), onClick = { perform { TvBoxFeatures.refresh(namespace) } })
                                        NgDialogTextActionButton(text = "移除配置", enabled = !busy, onClick = { perform { TvBoxFeatures.remove(namespace) } })
                                    }
                                    sources.filter { it.key.namespace == namespace }.forEach { source ->
                                        Row(Modifier.fillMaxWidth()) {
                                            Text(source.name, Modifier.weight(1f))
                                            NgDialogTextActionButton(text = "筛选", enabled = !busy && source.enabled, onClick = { NgSourceFiltersDialog.show(context, source) })
                                            NgDialogTextActionButton(text = if (source.enabled) "停用" else "启用", enabled = !busy, onClick = { perform { TvBoxFeatures.enable(source.key, !source.enabled) } })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        }
        dialog.setContentView(view); dialog.show(); dialog.applyNgWindow(); return dialog
    }
}
