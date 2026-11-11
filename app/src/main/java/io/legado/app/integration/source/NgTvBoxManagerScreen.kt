package io.legado.app.integration.source

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.theme.NgTheme
import io.legado.features.adapter.TvBoxFeatures
import io.legado.features.api.source.SourceDescriptor
import kotlinx.coroutines.*

@Composable
internal fun NgTvBoxManagerScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
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
    fun perform(action: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            status = "正在处理 TVBox 配置…"
            try { status = withContext(Dispatchers.IO) { val message = action(); refresh(); message } }
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
            val imported = TvBoxFeatures.import(text, url.ifBlank { uri.toString() })
            "文件导入成功，${imported.size} 个站点"
        }
    }
    NgSourceManagementPage(title = "TVBox 仓库管理", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(NgTheme.colors.surfaceVariant).copy(alpha = 0.48f),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("添加 TVBox 仓库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "粘贴配置地址，或从本地导入配置文件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(NgTheme.colors.onSurfaceVariant),
                            )
                            NgFormField(label = "配置 URL / 原始 URL", value = url, onValueChange = { url = it })
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NgFormActionButton(
                                    text = "添加仓库",
                                    variant = NgButtonVariant.TONAL,
                                    enabled = !busy && url.isNotBlank(),
                                    onClick = {
                                        val input = url.trim()
                                        perform {
                                            val imported = TvBoxFeatures.importUrl(input)
                                            "仓库添加成功，${imported.size} 个站点"
                                        }
                                    },
                                )
                                NgDialogTextActionButton(
                                    text = "导入文件",
                                    enabled = !busy,
                                    onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                                )
                            }
                        }
                    }
                    TvBoxFeatures.loadProblem()?.let { problem ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                "本地配置读取失败，原始数据已保留：$problem",
                                Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(NgTheme.colors.onSurfaceVariant),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已保存仓库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${configurations.size} 个仓库 · ${sources.size} 个站点",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(NgTheme.colors.onSurfaceVariant),
                        )
                    }
                }
            }
            if (configurations.isEmpty()) {
                item {
                    Text(
                        "暂无 TVBox 仓库",
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(NgTheme.colors.onSurfaceVariant),
                    )
                }
            }
            items(configurations, key = { it.first }) { (namespace, base) ->
                val repositorySources = sources.filter { it.key.namespace == namespace }
                var expanded by rememberSaveable(namespace) { mutableStateOf(false) }
                TvBoxRepositoryCard(
                    title = runCatching { android.net.Uri.parse(base).host }.getOrNull() ?: "本地 TVBox 仓库",
                    address = base,
                    sourceCount = repositorySources.size,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onRefresh = { perform { TvBoxFeatures.refresh(namespace); "仓库更新成功：$base" } },
                    onRemove = { perform { TvBoxFeatures.remove(namespace); "已移除仓库：$base" } },
                    busy = busy,
                    refreshEnabled = base.startsWith("http"),
                ) {
                    repositorySources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(source.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    if (source.enabled) "已启用" else "已停用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(NgTheme.colors.onSurfaceVariant),
                                )
                            }
                            NgDialogTextActionButton(
                                text = "筛选",
                                enabled = !busy && source.enabled,
                                onClick = { NgSourceFiltersDialog.show(context, source) },
                            )
                            NgSwitchControl(
                                checked = source.enabled,
                                onCheckedChange = { enabled ->
                                    perform {
                                        TvBoxFeatures.enable(source.key, enabled)
                                        "${source.name} 已${if (enabled) "启用" else "停用"}"
                                    }
                                },
                                enabled = !busy,
                                variant = NgSwitchControlVariant.COMPACT,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvBoxRepositoryCard(
    title: String,
    address: String,
    sourceCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    busy: Boolean,
    refreshEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(NgTheme.colors.surfaceVariant).copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = Color(NgTheme.colors.primary).copy(alpha = 0.12f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("TV", color = Color(NgTheme.colors.primary), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "$sourceCount 个站点",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(NgTheme.colors.onSurfaceVariant),
                    )
                }
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起站点" else "展开站点",
                    )
                }
            }
            Text(
                address,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = Color(NgTheme.colors.onSurfaceVariant),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NgDialogTextActionButton(
                    text = "移除",
                    enabled = !busy,
                    onClick = onRemove,
                )
                NgDialogTextActionButton(
                    text = "更新配置与 JAR",
                    enabled = !busy && refreshEnabled,
                    onClick = onRefresh,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 6.dp), color = Color(NgTheme.colors.outlineVariant))
                if (sourceCount == 0) {
                    Text(
                        "此仓库暂无站点",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(NgTheme.colors.onSurfaceVariant),
                    )
                } else {
                    content()
                }
            }
        }
    }
}
