package io.legado.app.integration.source

import android.content.Context
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgTheme
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.api.source.ExtensionRepository
import io.legado.features.api.source.ExtensionSummary
import io.legado.features.api.source.InstallProgress
import kotlinx.coroutines.*

@Composable
internal fun NgExtensionManagerScreen(
    context: Context,
    model: NgExtensionManagerViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by remember { ExtensionFeatures.changes() }.collectAsState(emptyList())
    val repositories by remember { ExtensionFeatures.repositoryChanges() }.collectAsState(ExtensionFeatures.repositories())
    val downloads by model.downloads.states.collectAsState()
    val refreshState by model.repositoryRefresh.state.collectAsState()
    var repository by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<ExtensionSummary, Boolean>?>(null) }
    LaunchedEffect(repository) { model.repositoryRefresh.refresh(repository) }
    LaunchedEffect(repositories) {
        if (repository.isNotEmpty() && repositories.none { it.url == repository }) repository = ""
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        ExtensionFeatures.installLocal(input).collect { progress ->
                            withContext(Dispatchers.Main) { status = "本地扩展：${progressText(progress)}" }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "导入失败" }
            finally { importing = false }
        }
    }
    NgSourceManagementPage(title = "Mihon / Aniyomi", onBack = onBack, actions = {
        IconButton(onClick = { model.repositoryRefresh.refresh(repository) }, enabled = !refreshState.refreshing) {
            Icon(painterResource(R.drawable.ic_bgm_menu_refresh), "刷新仓库", tint = Color(NgTheme.colors.primary))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), "更多操作", tint = Color(NgTheme.colors.onSurface))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("管理仓库" to "repositories", "TVBox 配置" to "tvbox", "扩展来源与设置" to "sources").forEach { (label, section) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onNavigate(section) })
                }
                DropdownMenuItem(text = { Text(if (importing) "正在导入本地 APK…" else "导入本地扩展 APK") }, enabled = !importing, onClick = {
                    menu = false
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                })
            }
        }
    }) {
        val repositoryStatus = if (repository.isEmpty()) {
            val failures = repositories.count { it.status.startsWith("刷新失败") }
            if (failures > 0) "$failures 个仓库刷新失败，可在仓库管理查看详情" else ""
        } else repositories.firstOrNull { it.url == repository }?.status.orEmpty()
        NgExtensionCatalog(entries, repositories, downloads, refreshState.refreshing,
            listOf(status, refreshState.message.ifBlank { repositoryStatus }).filter { it.isNotBlank() }.joinToString("\n"),
            repository = repository,
            onSelectRepository = { selected ->
                status = ""
                if (selected == repository) model.repositoryRefresh.refresh(selected)
                else repository = selected
            },
            onManageRepositories = { onNavigate("repositories") },
            onInstall = { model.downloads.start(it.packageName, it.id) },
            onCancel = { model.downloads.cancel(it.packageName) },
            onTrust = { confirmation = it to true },
            onUninstall = { confirmation = it to false },
        )
    }
    confirmation?.let { (entry, trust) ->
        Dialog(onDismissRequest = { confirmation = null }) {
            NgDialog(title = if (trust) "信任此扩展" else "卸载此扩展", actions = {
                NgDialogTextActionButton(text = "取消", onClick = { confirmation = null })
                NgDialogTextActionButton(text = "确认", onClick = {
                    confirmation = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (trust) ExtensionFeatures.trust(entry.packageName, checkNotNull(entry.fingerprint))
                                else ExtensionFeatures.uninstall(entry.packageName)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = e.message ?: "操作失败" }
                    }
                })
            }) { Text(if (trust) "${entry.name}\n签名 SHA-256：${entry.fingerprint}\n确认信任后才能加载。" else entry.name, color = Color(NgTheme.colors.onSurface)) }
        }
    }
}

@Composable
internal fun NgExtensionCatalog(
    entries: List<ExtensionSummary>,
    repositories: List<ExtensionRepository>,
    downloads: Map<String, ExtensionDownload>,
    refreshing: Boolean,
    message: String,
    repository: String,
    onSelectRepository: (String) -> Unit,
    onManageRepositories: () -> Unit,
    onInstall: (ExtensionSummary) -> Unit,
    onCancel: (ExtensionSummary) -> Unit,
    onTrust: (ExtensionSummary) -> Unit,
    onUninstall: (ExtensionSummary) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val languages = entries.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
    LaunchedEffect(languages) { if (language !in languages) language = "" }
    val visible = entries.filter { entry ->
        (repository.isEmpty() || entry.repositoryUrl == repository) &&
            (language.isEmpty() || entry.language == language) &&
            (entry.name.contains(query.trim(), true) || entry.packageName.contains(query.trim(), true)) &&
            when (filter) {
                "已导入" -> entry.state != "available"
                "可更新" -> entry.updateAvailable
                "下载中" -> downloads[entry.packageName]?.running == true
                else -> true
            }
    }.sortedWith(compareBy<ExtensionSummary> { it.name.lowercase() }.thenBy { it.id })
    val secondary = Color(NgTheme.colors.onSurfaceVariant)
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        NgSearchBar(query = query, onQueryChange = { query = it }, hint = "搜索扩展名称", variant = NgSearchBarVariant.COMPACT)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogSelector(
                label = repositories.firstOrNull { it.url == repository }?.name ?: "全部仓库",
                options = listOf("" to "全部仓库") + repositories.map { it.url to it.name },
                selected = repository, onSelect = onSelectRepository, modifier = Modifier.weight(1f), onManage = onManageRepositories,
            )
            CatalogSelector(
                label = if (language.isEmpty()) "全部语言" else languageLabel(language),
                options = listOf("" to "全部语言") + languages.map { it to languageLabel(it) },
                selected = language, onSelect = { language = it }, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已导入", "可更新", "下载中").forEach { tab ->
                FilterChip(selected = filter == tab, onClick = { filter = tab }, colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(NgTheme.colors.selectedContainer),
                    selectedLabelColor = Color(NgTheme.colors.onSurface),
                    labelColor = secondary,
                ), label = {
                    val count = downloads.values.count { it.running }
                    Text(if (tab == "下载中" && count > 0) "$tab $count" else tab, fontSize = 13.sp)
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${visible.size} 个扩展", color = secondary, style = MaterialTheme.typography.labelMedium)
            Text("下载后应用内使用", color = secondary, style = MaterialTheme.typography.labelMedium)
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 6.dp), color = secondary, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_cfg_source), null, Modifier.size(40.dp), tint = secondary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (entries.isEmpty()) "暂无扩展" else "没有符合条件的扩展", style = MaterialTheme.typography.titleSmall)
                    Text(if (entries.isEmpty()) "添加仓库或刷新仓库后查看" else "试试其他仓库、语言或关键词", color = secondary, style = MaterialTheme.typography.bodySmall)
                    if (entries.isEmpty()) TextButton(onClick = onManageRepositories) { Text("管理仓库") }
                }
            }
            items(visible, key = { it.id }) { entry ->
                NgExtensionListItem(entry, downloads[entry.packageName], onInstall, onCancel, onTrust, onUninstall)
            }
        }
    }
}

@Composable
private fun CatalogSelector(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(NgTheme.colors.onSurface), fontSize = 13.sp)
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp), tint = Color(NgTheme.colors.onSurfaceVariant))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) }, onClick = { expanded = false; onSelect(value) })
            }
            if (onManage != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("管理仓库") }, onClick = { expanded = false; onManage() })
            }
        }
    }
}

@Composable
internal fun NgExtensionListItem(
    entry: ExtensionSummary,
    download: ExtensionDownload?,
    onInstall: (ExtensionSummary) -> Unit,
    onCancel: (ExtensionSummary) -> Unit,
    onTrust: (ExtensionSummary) -> Unit,
    onUninstall: (ExtensionSummary) -> Unit,
) {
    val secondary = Color(NgTheme.colors.onSurfaceVariant)
    val running = download?.running == true
    val retry = download?.progress?.state in setOf("error", "cancelled")
    val needsTrust = entry.state == "notLoaded" && entry.fingerprint != null
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtensionIcon(entry)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("v${entry.version} · ${languageLabel(entry.language)}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary, fontSize = 12.sp)
                Text(
                    when {
                        running -> progressText(checkNotNull(download).progress)
                        download?.progress?.state == "error" -> "下载失败，可重试"
                        download?.progress?.state == "cancelled" -> "已取消"
                        entry.state == "loaded" -> if (entry.updateAvailable) "有可用更新" else "已加载"
                        entry.state == "notLoaded" -> entry.reason ?: "未加载"
                        download?.progress?.state == "installed" -> "已导入，正在加载…"
                        else -> "可下载"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (download?.progress?.state == "error") Color(NgTheme.colors.error) else secondary, fontSize = 12.sp,
                )
            }
            if (running || retry || needsTrust || entry.state == "available" || entry.updateAvailable) {
                NgFormActionButton(
                    text = when {
                        running -> "取消"
                        retry -> "重试"
                        needsTrust -> "信任"
                        entry.updateAvailable -> "更新"
                        else -> "下载"
                    },
                    onClick = {
                        when {
                            running -> onCancel(entry)
                            retry -> onInstall(entry)
                            needsTrust -> onTrust(entry)
                            else -> onInstall(entry)
                        }
                    },
                    variant = if (running) NgButtonVariant.NEUTRAL else NgButtonVariant.TONAL,
                    minimumWidth = 68.dp,
                )
            }
            if (entry.state != "available" && !running) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.width(36.dp)) { Icon(painterResource(R.drawable.ic_more_vert), "扩展操作", tint = secondary) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("卸载扩展") }, onClick = { menu = false; onUninstall(entry) })
                    }
                }
            }
        }
        if (running) {
            val progress = checkNotNull(download).progress
            if (progress.state == "downloading" && progress.percent > 0) {
                LinearProgressIndicator(progress = { progress.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 10.dp), color = Color(NgTheme.colors.primary), trackColor = Color(NgTheme.colors.surfaceContainerHigh))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 10.dp), color = Color(NgTheme.colors.primary), trackColor = Color(NgTheme.colors.surfaceContainerHigh))
            }
        }
        if (download?.progress?.state == "error") Text(download.progress.error ?: "下载失败", Modifier.padding(start = 56.dp, bottom = 8.dp), color = Color(NgTheme.colors.error), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(start = 56.dp), color = Color(NgTheme.colors.outlineVariant))
    }
}

@Composable
private fun ExtensionIcon(entry: ExtensionSummary) {
    val context = LocalContext.current
    val tint = NgTheme.colors.onSurfaceVariant
    val placeholder = remember(context, tint) { ContextCompat.getDrawable(context, R.drawable.ic_cfg_source)?.mutate()?.apply { setTint(tint) } }
    AndroidView(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NgTheme.shapes.mediumDp.dp)).background(Color(NgTheme.colors.surfaceContainer)),
        factory = { context -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inset = (4 * context.resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        } },
        update = { view -> Glide.with(view).load(entry.iconUrl).placeholder(placeholder).error(placeholder).into(view) },
        onRelease = { Glide.with(it).clear(it) },
    )
}

private fun languageLabel(language: String): String = when (language.lowercase()) {
    "zh" -> "中文"
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "all", "multi" -> "多语言"
    "" -> "未知语言"
    else -> language.uppercase()
}

private fun progressText(progress: InstallProgress): String = when (progress.state) {
    "downloading" -> "正在下载 ${progress.percent}%"
    "installing" -> "正在导入应用内…"
    "installed" -> "已导入，等待加载或信任"
    "error" -> "导入失败：${progress.error ?: "未知错误"}"
    else -> progress.state
}
