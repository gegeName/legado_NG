package io.legado.app.integration.source

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.design.components.compose.*
import io.legado.features.adapter.ExtensionFeatures
import kotlinx.coroutines.*

@Composable
internal fun NgExtensionSourcesScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources by remember { ExtensionFeatures.sourceChanges() }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    NgSourceManagementPage(title = "扩展来源与设置", onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column {
                    Spacer(Modifier.height(12.dp))
                    NgSearchBar(query = query, onQueryChange = { query = it }, hint = "搜索来源", variant = NgSearchBarVariant.COMPACT)
                    if (message.isNotBlank()) Text(message)
                    if (sources.isEmpty()) Text("暂无已加载来源，请先安装并信任扩展")
                }
            }
            items(sources.filter { it.name.contains(query, true) }, key = { "${it.key.provider}:${it.key.nativeId}" }) { source ->
                NgSourceListItem(
                    name = source.name,
                    detail = "${source.language} · ${source.key.provider}",
                    enabled = source.enabled,
                    busy = busy,
                    onToggle = {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) {
                                    ExtensionFeatures.setEnabled(source.key, !source.enabled)
                                    NgExtensionSourceBridge.syncSources(ExtensionFeatures.sources(), setOf("mihon", "aniyomi"), applyEnabled = true)
                                }
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message.orEmpty() }
                            finally { busy = false }
                        }
                    },
                    actions = {
                        NgDialogTextActionButton(text = "筛选", enabled = source.enabled, onClick = { NgSourceFiltersDialog.show(context, source) })
                        NgDialogTextActionButton(text = "设置", onClick = { NgSourcePreferencesDialog.show(context, source) })
                    },
                )
            }
        }
    }
}
