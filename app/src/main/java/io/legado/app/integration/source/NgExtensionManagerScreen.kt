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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.util.Locale
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.ui.design.components.NgButtonVariant
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgTheme
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.startActivity
import io.legado.features.adapter.ExtensionFeatures
import io.legado.features.api.source.ExtensionRepository
import io.legado.features.api.source.ExtensionSummary
import io.legado.features.api.source.InstallProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
internal fun NgExtensionManagerScreen(
    context: Context,
    model: NgExtensionManagerViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by remember { ExtensionFeatures.changes() }.collectAsState(emptyList())
    val sources by remember { ExtensionFeatures.sourceChanges() }.collectAsState(emptyList())
    val repositories by remember { ExtensionFeatures.repositoryChanges() }.collectAsState(ExtensionFeatures.repositories())
    val downloads by model.downloads.states.collectAsState()
    val refreshState by model.repositoryRefresh.state.collectAsState()
    var repository by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var checkingWebsite by remember { mutableStateOf<String?>(null) }
    var sourceBusy by remember { mutableStateOf(false) }
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
                listOf("管理仓库" to "repositories").forEach { (label, section) ->
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
        NgExtensionCatalog(entries, sources, repositories, downloads, refreshState.refreshing,
            listOf(status, refreshState.message.ifBlank { repositoryStatus }).filter { it.isNotBlank() }.joinToString("\n"),
            repository = repository,
            onSelectRepository = { selected ->
                status = ""
                if (selected == repository) model.repositoryRefresh.refresh(selected)
                else repository = selected
            },
            onManageRepositories = { onNavigate("repositories") },
            onInstall = { model.downloads.start(it.packageName, it.id) },
            onUpdateAll = { updates ->
                scope.launch {
                    updates.forEach { entry ->
                        model.downloads.start(entry.packageName, entry.id)
                        model.downloads.states.first { states -> states[entry.packageName]?.running != true }
                    }
                }
            },
            onCancel = { model.downloads.cancel(it.packageName) },
            onTrust = { confirmation = it to true },
            onUninstall = { confirmation = it to false },
            sourceBusy = sourceBusy,
            onToggleSource = { source ->
                if (!sourceBusy) scope.launch {
                    sourceBusy = true
                    try {
                        withContext(Dispatchers.IO) {
                            ExtensionFeatures.setEnabled(source.key, !source.enabled)
                            NgExtensionSourceBridge.syncSources(
                                ExtensionFeatures.sources(),
                                setOf("mihon", "aniyomi"),
                                applyEnabled = true,
                            )
                        }
                        status = "${source.name} 已${if (source.enabled) "停用" else "启用"}"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message ?: "来源设置失败" }
                    finally { sourceBusy = false }
                }
            },
            checkingWebsitePackage = checkingWebsite,
            onCheckWebsite = { entry ->
                if (checkingWebsite == null) scope.launch {
                    checkingWebsite = entry.packageName
                    try {
                        val url = withContext(Dispatchers.IO) { ExtensionFeatures.websiteUrl(entry.packageName) }
                        if (url.isNullOrBlank()) {
                            status = "${entry.name} 没有可打开的网站首页"
                        } else {
                            context.startActivity<WebViewActivity> {
                                putExtra("title", entry.name)
                                putExtra("url", url)
                                putExtra("sourceName", entry.name)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = "打开 ${entry.name} 失败：${e.message ?: e.javaClass.simpleName}" }
                    finally { checkingWebsite = null }
                }
            },
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
                            if (!trust) model.downloads.clear(entry.packageName)
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
    sources: List<io.legado.features.api.source.SourceDescriptor>,
    repositories: List<ExtensionRepository>,
    downloads: Map<String, ExtensionDownload>,
    refreshing: Boolean,
    message: String,
    repository: String,
    onSelectRepository: (String) -> Unit,
    onManageRepositories: () -> Unit,
    onInstall: (ExtensionSummary) -> Unit,
    onUpdateAll: (List<ExtensionSummary>) -> Unit,
    onCancel: (ExtensionSummary) -> Unit,
    onTrust: (ExtensionSummary) -> Unit,
    onUninstall: (ExtensionSummary) -> Unit,
    sourceBusy: Boolean,
    onToggleSource: (io.legado.features.api.source.SourceDescriptor) -> Unit,
    checkingWebsitePackage: String?,
    onCheckWebsite: (ExtensionSummary) -> Unit,
    initialFilter: NgExtensionStatus = NgExtensionStatus.NOT_DOWNLOADED,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    val uiContext = LocalContext.current
    val languages = entries.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
    LaunchedEffect(languages) { if (language !in languages) language = "" }
    val search = query.trim()
    val matchingUpdates = entries.filter { entry ->
        (repository.isEmpty() || entry.repositoryUrl == repository) &&
            (language.isEmpty() || entry.language == language) &&
            (entry.name.contains(search, true) || entry.packageName.contains(search, true)) &&
            extensionMatchesStatus(entry, downloads[entry.packageName], NgExtensionStatus.UPDATE_AVAILABLE)
    }.sortedWith(compareBy<ExtensionSummary> { it.name.lowercase() }.thenBy { it.id })
    val visibleSources = sources.filter { source ->
        search.isEmpty() || source.name.contains(search, true) ||
            source.language.contains(search, true) || source.key.provider.contains(search, true)
    }
    val visible = entries.filter { entry ->
        (repository.isEmpty() || entry.repositoryUrl == repository) &&
            (language.isEmpty() || entry.language == language) &&
            (entry.name.contains(search, true) || entry.packageName.contains(search, true)) &&
            extensionMatchesStatus(entry, downloads[entry.packageName], filter)
    }.sortedWith(compareBy<ExtensionSummary> { it.name.lowercase() }.thenBy { it.id })
    val secondary = Color(NgTheme.colors.onSurfaceVariant)
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        NgSearchBar(query = query, onQueryChange = { query = it }, hint = "搜索扩展或来源", variant = NgSearchBarVariant.COMPACT)
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
                maxMenuHeight = 360.dp,
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NgExtensionStatus.values().forEach { tab ->
                val runningCount = downloads.values.count { it.running }
                Box {
                    FilterChip(
                        selected = filter == tab,
                        onClick = { filter = tab },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(NgTheme.colors.selectedContainer),
                            selectedLabelColor = Color(NgTheme.colors.onSurface),
                            labelColor = secondary,
                        ),
                        label = {
                            if (tab == NgExtensionStatus.DOWNLOADING && runningCount > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(tab.label, fontSize = 13.sp)
                                    CatalogCountBadge(runningCount)
                                }
                            } else {
                                Text(tab.label, fontSize = 13.sp)
                            }
                        },
                    )
                    if (tab == NgExtensionStatus.UPDATE_AVAILABLE && matchingUpdates.isNotEmpty()) {
                        CatalogCountBadge(
                            count = matchingUpdates.size,
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = (-4).dp),
                        )
                    }
                }
            }
        }
        if (filter == NgExtensionStatus.UPDATE_AVAILABLE && matchingUpdates.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                NgFormActionButton(
                    text = if (matchingUpdates.size == 1) "一键更新" else "一键更新 ${matchingUpdates.size} 项",
                    onClick = { onUpdateAll(matchingUpdates) },
                    variant = NgButtonVariant.TONAL,
                    minimumWidth = 112.dp,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${visibleSources.size} 个来源 · ${visible.size} 个扩展", color = secondary, style = MaterialTheme.typography.labelMedium)
            Text("下载后应用内使用", color = secondary, style = MaterialTheme.typography.labelMedium)
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 6.dp), color = secondary, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "managed-sources-title") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("扩展来源与设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${visibleSources.size}", color = secondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (visibleSources.isEmpty()) {
                item(key = "managed-sources-empty") {
                    Text(
                        if (sources.isEmpty()) "暂无已加载来源，请先安装并信任扩展" else "没有匹配的扩展来源",
                        Modifier.padding(vertical = 8.dp),
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(visibleSources, key = { "source:${it.key.provider}:${it.key.nativeId}" }) { source ->
                NgSourceListItem(
                    name = source.name,
                    detail = "${source.language} · ${source.key.provider}",
                    enabled = source.enabled,
                    busy = sourceBusy,
                    onToggle = { onToggleSource(source) },
                    actions = {
                        NgDialogTextActionButton(
                            text = "筛选",
                            enabled = source.enabled && !sourceBusy,
                            onClick = { NgSourceFiltersDialog.show(uiContext, source) },
                        )
                        NgDialogTextActionButton(
                            text = "设置",
                            enabled = !sourceBusy,
                            onClick = { NgSourcePreferencesDialog.show(uiContext, source) },
                        )
                    },
                )
            }
            item(key = "extensions-title") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("扩展目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${visible.size}", color = secondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_cfg_source), null, Modifier.size(40.dp), tint = secondary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (entries.isEmpty()) "暂无扩展" else "没有符合条件的扩展", style = MaterialTheme.typography.titleSmall)
                    Text(if (entries.isEmpty()) "添加仓库或刷新仓库后查看" else "试试其他仓库、语言或关键词", color = secondary, style = MaterialTheme.typography.bodySmall)
                    if (entries.isEmpty()) TextButton(onClick = onManageRepositories) { Text("管理仓库") }
                }
            }
            items(visible, key = { "extension:${it.id}" }) { entry ->
                NgExtensionListItem(
                    entry,
                    downloads[entry.packageName],
                    checkingWebsite = checkingWebsitePackage == entry.packageName,
                    onInstall = onInstall,
                    onCancel = onCancel,
                    onTrust = onTrust,
                    onUninstall = onUninstall,
                    onCheckWebsite = { onCheckWebsite(entry) },
                )
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
    maxMenuHeight: Dp? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(NgTheme.colors.onSurface), fontSize = 13.sp)
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp), tint = Color(NgTheme.colors.onSurfaceVariant))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = maxMenuHeight?.let { Modifier.heightIn(max = it) } ?: Modifier,
        ) {
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
private fun CatalogCountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(NgTheme.colors.errorContainer),
        contentColor = Color(NgTheme.colors.onErrorContainer),
        modifier = modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
    ) {
        Box(Modifier.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
            Text(count.toString(), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun NgExtensionListItem(
    entry: ExtensionSummary,
    download: ExtensionDownload?,
    checkingWebsite: Boolean,
    onInstall: (ExtensionSummary) -> Unit,
    onCancel: (ExtensionSummary) -> Unit,
    onTrust: (ExtensionSummary) -> Unit,
    onUninstall: (ExtensionSummary) -> Unit,
    onCheckWebsite: () -> Unit,
) {
    val secondary = Color(NgTheme.colors.onSurfaceVariant)
    val running = download?.running == true
    val status = extensionStatus(entry, download)
    val retry = status == NgExtensionStatus.DOWNLOAD_FAILED ||
        download?.progress?.state == "cancelled"
    val needsTrust = entry.state == "notLoaded" && entry.fingerprint != null
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCheckWebsite, enabled = !checkingWebsite, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = "在 WebView 中检查 ${entry.name}",
                    tint = secondary,
                )
            }
            ExtensionIcon(entry)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("v${entry.version} · ${languageLabel(entry.language)}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = secondary, fontSize = 12.sp)
                Text(
                    when (status) {
                        NgExtensionStatus.DOWNLOADING -> progressText(checkNotNull(download).progress)
                        NgExtensionStatus.DOWNLOAD_FAILED -> "下载失败，可重试"
                        NgExtensionStatus.UPDATE_AVAILABLE -> "有可用更新"
                        NgExtensionStatus.DOWNLOADED -> when {
                            entry.state == "loaded" -> "已加载"
                            entry.state == "notLoaded" -> entry.reason ?: "下载完成"
                            else -> "下载完成"
                        }
                        NgExtensionStatus.NOT_DOWNLOADED -> if (download?.progress?.state == "cancelled") "已取消" else "未下载"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (download?.progress?.state == "error") Color(NgTheme.colors.error) else secondary, fontSize = 12.sp,
                )
            }
            if (running || retry || needsTrust || status == NgExtensionStatus.NOT_DOWNLOADED || status == NgExtensionStatus.UPDATE_AVAILABLE) {
                NgFormActionButton(
                    text = when {
                        running -> "取消"
                        retry -> "重试"
                        needsTrust -> "信任"
                        status == NgExtensionStatus.UPDATE_AVAILABLE -> "更新"
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
                LinearProgressIndicator(progress = { progress.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().padding(start = 96.dp, bottom = 10.dp), color = Color(NgTheme.colors.primary), trackColor = Color(NgTheme.colors.surfaceContainerHigh))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 96.dp, bottom = 10.dp), color = Color(NgTheme.colors.primary), trackColor = Color(NgTheme.colors.surfaceContainerHigh))
            }
        }
        if (download?.progress?.state == "error") Text(download.progress.error ?: "下载失败", Modifier.padding(start = 96.dp, bottom = 8.dp), color = Color(NgTheme.colors.error), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(start = 96.dp), color = Color(NgTheme.colors.outlineVariant))
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
    else -> Locale.forLanguageTag(language.replace('_', '-'))
        .getDisplayName(Locale.SIMPLIFIED_CHINESE)
        .takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        ?: "未知语言（${language.uppercase()}）"
}

private fun progressText(progress: InstallProgress): String = when (progress.state) {
    "downloading" -> "正在下载 ${progress.percent}%"
    "installing" -> "正在导入应用内…"
    "installed" -> "已导入，等待加载或信任"
    "error" -> "导入失败：${progress.error ?: "未知错误"}"
    else -> progress.state
}
