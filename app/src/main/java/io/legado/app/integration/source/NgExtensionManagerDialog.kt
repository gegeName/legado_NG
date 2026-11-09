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
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.api.source.ExtensionSummary
import kotlinx.coroutines.*

object NgExtensionManagerDialog {
    fun show(context: Context): ComponentDialog {
        val dialog = ComponentDialog(context)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NgAppTheme(updateSystemBars = false) {
                    val scope = rememberCoroutineScope()
                    val entries by remember { ExtensionFeatures.changes() }.collectAsState(emptyList())
                    var query by remember { mutableStateOf("") }
                    var repositoryUrl by remember { mutableStateOf("") }
                    var status by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    var confirmation by remember { mutableStateOf<Pair<ExtensionSummary, Boolean>?>(null) }
                    var repositoryVersion by remember { mutableIntStateOf(0) }
                    fun perform(action: suspend () -> Unit) {
                        if (busy) return
                        scope.launch {
                            busy = true
                            try { withContext(Dispatchers.IO) { action() } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { status = e.message.orEmpty() }
                            finally { busy = false }
                        }
                    }
                    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) perform {
                            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                ExtensionFeatures.installLocal(input).collect { progress ->
                                    withContext(Dispatchers.Main) { status = "本地扩展：${progress.state} ${progress.error.orEmpty()}" }
                                }
                            }
                        }
                    }
                    NgDialog(title = "Mihon / Aniyomi 扩展", actions = {
                        NgDialogTextActionButton(text = "刷新仓库", enabled = !busy, onClick = { perform { ExtensionFeatures.refresh() } })
                        NgDialogTextActionButton(text = "关闭", onClick = dialog::dismiss)
                    }) {
                        Column(Modifier.heightIn(max = 520.dp)) {
                            NgFormField(label = "搜索扩展", value = query, onValueChange = { query = it })
                            NgFormField(label = "扩展仓库索引 URL", value = repositoryUrl, onValueChange = { repositoryUrl = it })
                            NgDialogTextActionButton(text = "添加仓库", enabled = !busy && repositoryUrl.isNotBlank(), onClick = {
                                perform { ExtensionFeatures.addRepository(repositoryUrl).getOrThrow(); withContext(Dispatchers.Main) { repositoryVersion++; repositoryUrl = "" } }
                            })
                            val repositories = remember(repositoryVersion) { ExtensionFeatures.repositories() }
                            Text(repositories.joinToString("、") { it.name })
                            NgDialogTextActionButton(text = "TVBox 配置", enabled = !busy, onClick = { NgTvBoxManagerDialog.show(context) })
                            NgDialogTextActionButton(text = "扩展来源与设置", enabled = !busy, onClick = { NgExtensionSourcesDialog.show(context) })
                            NgDialogTextActionButton(text = "导入本地扩展 APK", enabled = !busy, onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) })
                            if (status.isNotBlank()) Text(status)
                            LazyColumn(Modifier.weight(1f, fill = false)) {
                                items(entries.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }, key = { it.id }) { entry ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        Text("${entry.name} ${entry.version}")
                                        Text(when (entry.state) { "loaded" -> "已加载"; "available" -> "可安装"; else -> "未加载：${entry.reason}" })
                                        Row {
                                            if (entry.state == "available" || entry.updateAvailable) NgDialogTextActionButton(text = if (entry.state == "available") "安装" else "更新", enabled = !busy, onClick = {
                                                perform { ExtensionFeatures.install(entry.id).collect { progress -> withContext(Dispatchers.Main) { status = "${entry.name}：${progress.state} ${progress.percent}% ${progress.error.orEmpty()}" } } }
                                            })
                                            if (entry.state == "notLoaded" && entry.fingerprint != null) NgDialogTextActionButton(text = "检查并信任", enabled = !busy, onClick = { confirmation = entry to true })
                                            if (entry.state != "available") NgDialogTextActionButton(text = "卸载", enabled = !busy, onClick = { confirmation = entry to false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    confirmation?.let { (entry, trust) ->
                        NgDialog(title = if (trust) "信任此扩展" else "卸载此扩展", actions = {
                            NgDialogTextActionButton(text = "取消", onClick = { confirmation = null })
                            NgDialogTextActionButton(text = "确认", onClick = {
                                confirmation = null
                                perform { if (trust) ExtensionFeatures.trust(entry.packageName, checkNotNull(entry.fingerprint)) else ExtensionFeatures.uninstall(entry.packageName) }
                            })
                        }) { Text(if (trust) "${entry.name}\n签名 SHA-256：${entry.fingerprint}\n信任后扩展代码将在应用中运行。" else entry.name) }
                    }
                }
            }
        }
        dialog.setContentView(view)
        dialog.show()
        dialog.applyNgWindow()
        return dialog
    }
}
