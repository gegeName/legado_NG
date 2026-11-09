package io.legado.app.integration.source

import android.content.Context
import androidx.activity.ComponentDialog
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
import io.legado.features.adapter.ExtensionFeatures
import kotlinx.coroutines.*

object NgExtensionSourcesDialog {
    fun show(context: Context): ComponentDialog {
        val dialog = ComponentDialog(context)
        dialog.setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { NgAppTheme(updateSystemBars = false) {
                val scope = rememberCoroutineScope()
                val sources by remember { ExtensionFeatures.sourceChanges() }.collectAsState(emptyList())
                var query by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                NgDialog(title = "扩展来源与设置", actions = { NgDialogTextActionButton(text = "关闭", onClick = dialog::dismiss) }) {
                    Column(Modifier.heightIn(max = 520.dp)) {
                        NgFormField(label = "搜索来源", value = query, onValueChange = { query = it })
                        if (message.isNotBlank()) Text(message)
                        if (sources.isEmpty()) Text("暂无已加载来源，请先安装并信任扩展")
                        LazyColumn {
                            items(sources.filter { it.name.contains(query, true) }, key = { "${it.key.provider}:${it.key.nativeId}" }) { source ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Text("${source.name} · ${source.language} · ${source.key.provider}")
                                    Row {
                                        NgDialogTextActionButton(text = "筛选", enabled = source.enabled, onClick = { NgSourceFiltersDialog.show(context, source) })
                                        NgDialogTextActionButton(text = "设置", onClick = { NgSourcePreferencesDialog.show(context, source) })
                                        NgDialogTextActionButton(text = if (source.enabled) "停用" else "启用", enabled = !busy, onClick = {
                                            scope.launch {
                                                busy = true
                                                try { withContext(Dispatchers.IO) {
                                                    ExtensionFeatures.setEnabled(source.key, !source.enabled)
                                                    NgExtensionSourceBridge.syncSources(ExtensionFeatures.sources(), setOf("mihon", "aniyomi"), applyEnabled = true)
                                                } }
                                                catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { message = e.message.orEmpty() }
                                                finally { busy = false }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        })
        dialog.show(); dialog.applyNgWindow(); return dialog
    }
}
