package io.legado.app.integration.source

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.widget.dialog.applyNgWindow
import io.legado.features.adapter.SourceFeatures
import io.legado.features.api.source.*
import kotlinx.coroutines.*

object NgSourceFiltersDialog {
    fun show(context: Context, source: SourceDescriptor): ComponentDialog {
        val dialog = ComponentDialog(context)
        dialog.setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { NgAppTheme(updateSystemBars = false) {
                var session by remember { mutableStateOf<FilterSession?>(null) }
                var fields by remember { mutableStateOf<List<FilterDescriptor>>(emptyList()) }
                var error by remember { mutableStateOf("") }
                LaunchedEffect(source.key) {
                    var acquired: FilterSession? = null
                    try {
                        val opened = withContext(Dispatchers.IO) { SourceFeatures.filters(source.key).also { acquired = it } }
                        session = opened; fields = opened.describe()
                        awaitCancellation()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message.orEmpty() }
                    finally { acquired?.close(); session = null }
                }
                NgDialog(title = source.name + " · 筛选", actions = {
                    NgDialogTextActionButton(text = "取消", onClick = dialog::dismiss)
                    NgDialogTextActionButton(text = "保存", enabled = session != null, onClick = {
                        try { session?.let(SourceFeatures::saveFilters); dialog.dismiss() }
                        catch (e: Exception) { error = e.message.orEmpty() }
                    })
                }) {
                    Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                        if (error.isNotBlank()) Text(error)
                        if (session == null && error.isBlank()) Text("正在读取筛选项…")
                        if (session != null && fields.isEmpty()) Text("此来源没有可用的筛选项")
                        NgSourceFilterFields(fields) { path, value ->
                            try { session?.set(path, value); fields = session?.describe().orEmpty() }
                            catch (e: Exception) { error = e.message.orEmpty() }
                        }
                    }
                }
            } }
        })
        dialog.show(); dialog.applyNgWindow(); return dialog
    }
}

@Composable private fun NgSourceFilterFields(fields: List<FilterDescriptor>, change: (List<Int>, FilterValue) -> Unit) {
    val arrow = painterResource(R.drawable.ic_ng_spinner_arrow_down)
    fields.forEach { field ->
        val title = translateToChinese(field.title)
        when (field.kind) {
            FilterKind.HEADER -> Text(title)
            FilterKind.SEPARATOR -> Spacer(Modifier.height(8.dp))
            FilterKind.GROUP -> { Text(title); NgSourceFilterFields(field.children, change) }
            FilterKind.TEXT -> NgFormField(label = title, value = field.value.text, onValueChange = { change(field.path, field.value.copy(text = it)) })
            FilterKind.CHECKBOX -> NgFormSelectField(title, field.value.checked.toString(), listOf(NgFormSelectOption("否", "false"), NgFormSelectOption("是", "true")), { change(field.path, field.value.copy(checked = it.toBoolean())) }, arrowIcon = arrow)
            FilterKind.TRISTATE -> NgFormSelectField(title, field.value.selected.toString(), listOf("忽略", "包含", "排除").mapIndexed { i, s -> NgFormSelectOption(s, i.toString()) }, { change(field.path, field.value.copy(selected = it.toInt())) }, arrowIcon = arrow)
            FilterKind.SELECT, FilterKind.SORT -> {
                NgFormSelectField(
                    title,
                    field.value.selected.toString(),
                    field.choices.mapIndexed { i, s -> NgFormSelectOption(translateToChinese(s), i.toString()) },
                    { change(field.path, field.value.copy(selected = it.toInt())) },
                    arrowIcon = arrow,
                )
                if (field.kind == FilterKind.SORT) NgDialogTextActionButton(text = if (field.value.ascending) "升序" else "降序", onClick = { change(field.path, field.value.copy(ascending = !field.value.ascending)) })
            }
        }
    }
}
