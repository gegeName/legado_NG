package io.legado.app.integration.source

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.design.components.compose.*
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
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column {
                    NgFormField(label = "配置 URL / 本地文件的原始 URL", value = url, onValueChange = { url = it })
                    Row {
                        NgDialogTextActionButton(text = "添加仓库", enabled = !busy && url.isNotBlank(), onClick = { val input = url.trim(); perform { val imported = TvBoxFeatures.importUrl(input); "仓库添加成功，${imported.size} 个站点" } })
                        NgDialogTextActionButton(text = "导入文件", enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                    }
                    TvBoxFeatures.loadProblem()?.let { Text("本地配置读取失败，原始数据已保留：$it") }
                    if (status.isNotBlank()) Text(status)
                    Text("已保存 ${configurations.size} 个仓库，${sources.size} 个站点")
                }
            }
            if (configurations.isEmpty()) item { Text("暂无 TVBox 仓库，请添加配置 URL 或导入文件。") }
            items(configurations, key = { it.first }) { (namespace, base) ->
                NgRepositoryCard(
                    title = runCatching { android.net.Uri.parse(base).host }.getOrNull() ?: "本地 TVBox 仓库",
                    address = base,
                    status = "已保存 · ${sources.count { it.key.namespace == namespace }} 个站点",
                    actions = {
                        NgDialogTextActionButton(text = "更新配置与 JAR", enabled = !busy && base.startsWith("http"), onClick = { perform { TvBoxFeatures.refresh(namespace); "仓库更新成功：$base" } })
                        NgDialogTextActionButton(text = "移除仓库", enabled = !busy, onClick = { perform { TvBoxFeatures.remove(namespace); "已移除仓库：$base" } })
                    },
                ) {
                    sources.filter { it.key.namespace == namespace }.forEach { source ->
                        NgSourceListItem(name = source.name, detail = "TVBox 站点", enabled = source.enabled, busy = busy,
                            onToggle = { perform { TvBoxFeatures.enable(source.key, !source.enabled); "${source.name} 已${if (source.enabled) "停用" else "启用"}" } },
                            actions = { NgDialogTextActionButton(text = "筛选", enabled = !busy && source.enabled, onClick = { NgSourceFiltersDialog.show(context, source) }) },
                        )
                    }
                }
            }
        }
    }
}
