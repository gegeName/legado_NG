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

object NgSourcePreferencesDialog {
    fun show(context: Context, source: SourceDescriptor): ComponentDialog {
        val dialog = ComponentDialog(context)
        dialog.setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { NgAppTheme(updateSystemBars = false) {
                var session by remember { mutableStateOf<SourcePreferenceSession?>(null) }
                var fields by remember { mutableStateOf<List<SourcePreferenceField>>(emptyList()) }
                var message by remember { mutableStateOf("正在读取设置…") }
                fun update(action: () -> Boolean) {
                    try {
                        message = if (action()) "已应用" else "扩展未接受此次修改"
                        fields = session?.describe().orEmpty()
                    } catch (e: Exception) { message = e.message.orEmpty() }
                }
                LaunchedEffect(source.key) {
                    var opened: SourcePreferenceSession? = null
                    try {
                        opened = SourceFeatures.provider(source.key).preferences(source.key)
                        session = opened; fields = opened?.describe().orEmpty()
                        message = if (opened == null || fields.isEmpty()) "此来源没有可配置项" else "修改后立即生效"
                        awaitCancellation()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message.orEmpty() }
                    finally { opened?.close(); session = null }
                }
                NgDialog(title = source.name + " · 设置", actions = {
                    NgDialogTextActionButton(text = "关闭", onClick = dialog::dismiss)
                }) {
                    Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                        Text(message)
                        PreferenceFields(fields, { path, value -> update { session?.set(path, value) == true } }, { path -> update { session?.activate(path) == true } })
                    }
                }
            } }
        })
        dialog.show(); dialog.applyNgWindow(); return dialog
    }
}

@Composable private fun PreferenceFields(fields: List<SourcePreferenceField>, change: (List<Int>, SourcePreferenceValue) -> Unit, activate: (List<Int>) -> Unit) {
    val arrow = painterResource(R.drawable.ic_ng_spinner_arrow_down)
    fields.forEach { field -> key(field.path) {
        val title = translateToChinese(field.title)
        val summary = translateToChinese(field.summary)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (summary.isNotBlank()) Text(summary)
            when (field.kind) {
                SourcePreferenceKind.GROUP -> { Text(title); PreferenceFields(field.children, change, activate) }
                SourcePreferenceKind.BOOLEAN -> NgFormSelectField(title, field.value.checked.toString(), listOf(NgFormSelectOption("关闭", "false"), NgFormSelectOption("开启", "true")), { if (field.enabled) change(field.path, field.value.copy(checked = it.toBoolean())) }, arrowIcon = arrow)
                SourcePreferenceKind.SELECT -> NgFormSelectField(title, field.value.text, field.choices.map { NgFormSelectOption(translateToChinese(it.second), it.first) }, { if (field.enabled) change(field.path, field.value.copy(text = it)) }, arrowIcon = arrow)
                SourcePreferenceKind.MULTISELECT -> {
                    Text(title)
                    field.choices.forEach { (value, optionTitle) ->
                        NgDialogTextActionButton(text = (if (value in field.value.selected) "✓ " else "○ ") + translateToChinese(optionTitle), enabled = field.enabled, onClick = {
                            val values = field.value.selected.let { if (value in it) it - value else it + value }
                            change(field.path, field.value.copy(selected = values))
                        })
                    }
                }
                SourcePreferenceKind.TEXT, SourcePreferenceKind.NUMBER -> {
                    var draft by remember(field.value.text) { mutableStateOf(field.value.text) }
                    NgFormField(label = title, value = draft, onValueChange = { draft = it })
                    if (field.kind == SourcePreferenceKind.NUMBER) Text("数值范围：${field.minimum}–${field.maximum}")
                    NgDialogTextActionButton(text = "应用", enabled = field.enabled && draft != field.value.text, onClick = { change(field.path, field.value.copy(text = draft)) })
                }
                SourcePreferenceKind.ACTION -> NgDialogTextActionButton(text = title, enabled = field.enabled, onClick = { activate(field.path) })
                SourcePreferenceKind.UNSUPPORTED -> Text(title + " · 此项需要扩展自定义界面")
            }
        }
    } }
}
